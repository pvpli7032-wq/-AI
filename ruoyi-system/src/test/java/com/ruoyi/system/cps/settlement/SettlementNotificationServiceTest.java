package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SettlementNotificationServiceTest
{
    private SettlementNotificationRepository repository;
    private ObjectProvider<SettlementNotificationChannel> channels;
    private SettlementNotificationChannel channel;
    private SettlementNotificationService service;
    private TestTransactions manager;

    @Before
    @SuppressWarnings("unchecked")
    public void setup()
    {
        repository = mock(SettlementNotificationRepository.class);
        channels = mock(ObjectProvider.class);
        channel = mock(SettlementNotificationChannel.class);
        manager = new TestTransactions();
        SettlementNotificationService target = new SettlementNotificationService(repository, channels, manager);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        service = (SettlementNotificationService) proxy.getProxy();
    }

    @Test
    public void mandatoryTransactionRejectsUncoordinatedEnqueue()
    {
        assertThrows(IllegalTransactionStateException.class, () -> enqueue("REFUND"));
        verifyNoInteractions(repository, channels);
    }

    @Test
    public void allFourReasonsArePrivateRoundedSnapshotsAndNeverSend()
    {
        for (String reason : new String[] {"BINDING_FAILURE", "REBATE_INVALID", "REFUND", "WHOLE_MONTH_PENALTY"})
        {
            new TransactionTemplate(manager).executeWithoutResult(status -> enqueue(reason));
        }
        verify(repository, times(4)).insert(matches("[0-9a-f]{64}"), eq(10L), isNull(), anyString(),
                eq(new BigDecimal("1.24")), eq(new BigDecimal("-2.35")));
        verifyNoInteractions(channels);
    }

    @Test
    public void rejectsFreeTextPiiNegativeLossAndReadOnlyTransactions()
    {
        // Direct target isolates input validation from the transaction test double.
        SettlementNotificationService target = new SettlementNotificationService(repository, channels, manager);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThrows(IllegalArgumentException.class, () -> target.enqueue("key", 1L, 1L,
                    "raw order 123 / phone", BigDecimal.ONE, BigDecimal.ZERO));
            assertThrows(IllegalArgumentException.class, () -> target.enqueue("key", 1L, 1L,
                    "REFUND", new BigDecimal("-1"), BigDecimal.ZERO));
        });
        TransactionTemplate readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.executeWithoutResult(status ->
                target.enqueue("key", 1L, null, "REFUND", BigDecimal.ONE, BigDecimal.ZERO)));
        verifyNoInteractions(repository);
    }

    @Test
    public void missingAdapterLeavesPendingAndReturnsZero()
    {
        assertEquals(0, service.retryPending(10));
        verifyNoInteractions(repository);
    }

    @Test
    public void workerRejectsAmbientTransactionsAndUnboundedBatches()
    {
        assertThrows(IllegalArgumentException.class, () -> service.retryPending(0));
        assertThrows(IllegalArgumentException.class, () -> service.retryPending(101));
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> service.retryPending(1)));
        verifyNoInteractions(channels, repository);
    }

    @Test
    public void claimAndAckAreTransactionsButChannelIsNotAndBatchIsBounded() throws Exception
    {
        when(channels.getIfAvailable()).thenReturn(channel);
        SettlementNotificationRepository.Claim claim = claim();
        AtomicInteger committedBeforeSend = new AtomicInteger();
        when(repository.claim()).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return claim;
        });
        when(repository.delivered(claim)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            committedBeforeSend.set(manager.commits);
            return null;
        }).when(channel).deliver(claim.message());
        assertEquals(1, service.retryPending(1));
        assertEquals(1, committedBeforeSend.get());
        assertEquals(2, manager.commits);
        verify(repository, times(1)).claim();
        verify(channel).deliver(claim.message());
    }

    @Test
    public void ambiguousDeliveryRetriesWithSameKeyAndStaleAckDoesNotCount() throws Exception
    {
        when(channels.getIfAvailable()).thenReturn(channel);
        SettlementNotificationRepository.Claim claim = claim();
        when(repository.claim()).thenReturn(claim).thenReturn(null);
        doThrow(new Exception("provider private payload")).when(channel).deliver(claim.message());
        assertEquals(0, service.retryPending(2));
        verify(repository).retry(claim);
        verify(repository, never()).delivered(any());
        reset(channel);
        when(repository.claim()).thenReturn(claim);
        when(repository.delivered(claim)).thenReturn(false);
        assertEquals(0, service.retryPending(1));
        verify(channel).deliver(claim.message());
    }

    @Test
    public void acknowledgementFailureRemainsRecoverableAndDoesNotResetClaim() throws Exception
    {
        when(channels.getIfAvailable()).thenReturn(channel);
        SettlementNotificationRepository.Claim claim = claim();
        when(repository.claim()).thenReturn(claim);
        when(repository.delivered(claim)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.retryPending(1));
        verify(channel).deliver(claim.message());
        verify(repository, never()).retry(any());
        assertEquals(1, manager.rollbacks);
    }

    @Test
    public void interruptionStopsBatchAndPreservesInterruptFlag() throws Exception
    {
        when(channels.getIfAvailable()).thenReturn(channel);
        SettlementNotificationRepository.Claim claim = claim();
        when(repository.claim()).thenReturn(claim);
        doThrow(new InterruptedException()).when(channel).deliver(claim.message());
        try
        {
            assertEquals(0, service.retryPending(100));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(repository, times(1)).claim();
            verify(repository).retry(claim);
        }
        finally
        {
            Thread.interrupted();
        }
    }

    @Test
    public void payloadHasOnlyPrivateRoutingAndSafeContentAndRedactedDiagnostics()
    {
        assertEquals(java.util.Set.of("idempotencyKey", "userId", "reason", "lossAmount", "balanceAfter"),
                java.util.Arrays.stream(SettlementNotificationChannel.Message.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
        assertEquals("SettlementNotification.Message[redacted]", claim().message().toString());
    }

    private void enqueue(String reason)
    {
        service.enqueue("private-ledger-event", 10L, null, reason, new BigDecimal("1.235"), new BigDecimal("-2.345"));
    }

    private SettlementNotificationRepository.Claim claim()
    {
        return new SettlementNotificationRepository.Claim(1L, "lease-token",
                new SettlementNotificationChannel.Message("stable-opaque-key", 10L, "REFUND",
                        BigDecimal.ONE, BigDecimal.ZERO));
    }

    /** Only tests service transaction boundaries, NOT SQL/locking behavior. */
    static class TestTransactions extends AbstractPlatformTransactionManager
    {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction()
        {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected boolean isExistingTransaction(Object tx) { return Boolean.TRUE.equals(tx); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
