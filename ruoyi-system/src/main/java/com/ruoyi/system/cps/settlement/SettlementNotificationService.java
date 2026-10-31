package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional notification outbox only: never reads or writes account balances. */
@Service
public class SettlementNotificationService
{
    public static final String BINDING_FAILURE = "BINDING_FAILURE";
    public static final String REBATE_INVALID = "REBATE_INVALID";
    public static final String REFUND = "REFUND";
    public static final String WHOLE_MONTH_PENALTY = "WHOLE_MONTH_PENALTY";
    private static final Set<String> REASONS = Set.of(BINDING_FAILURE, REBATE_INVALID, REFUND, WHOLE_MONTH_PENALTY);
    private static final BigDecimal LIMIT = new BigDecimal("9999999999999999.99");
    private final SettlementNotificationRepository repository;
    private final ObjectProvider<SettlementNotificationChannel> channels;
    private final TransactionTemplate transactions;

    public SettlementNotificationService(SettlementNotificationRepository repository,
            ObjectProvider<SettlementNotificationChannel> channels, PlatformTransactionManager transactionManager)
    {
        this.repository = repository;
        this.channels = channels;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(30);
    }

    /**
     * Call through the Spring bean INSIDE the ledger transaction, after its balance
     * mutation. balanceAfter is the caller's snapshot, not recomputed here. No
     * delivery occurs here or in an after-commit callback. A rollback removes this
     * row as well. Keys must uniquely identify the recipient and ledger event;
     * they are stored only as SHA-256 digests and never forwarded to the channel.
     * orderLineId is an internal surrogate (nullable for whole-month events).
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void enqueue(String businessKey, Long userId, Long orderLineId, String reason,
                        BigDecimal lossAmount, BigDecimal balanceAfter)
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        {
            throw new IllegalStateException("Notification enqueue requires a writable ledger transaction");
        }
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 512
                || userId == null || userId <= 0 || (orderLineId != null && orderLineId <= 0)
                || reason == null || !REASONS.contains(reason))
        {
            throw new IllegalArgumentException("Invalid notification identity or reason code");
        }
        BigDecimal loss = money(lossAmount);
        if (lossAmount.signum() < 0)
        {
            throw new IllegalArgumentException("Loss amount must be nonnegative");
        }
        repository.insert(hash(businessKey), userId, orderLineId, reason, loss, money(balanceAfter));
    }

    /**
     * Bounded retry entry point for a future unfreeze task; invoke OUTSIDE any
     * transaction. Returns rows acknowledged SENT, not attempted/assumed deliveries.
     * Without an adapter this returns zero and leaves durable PENDING rows alone.
     * Each claim and acknowledgement has its own short transaction; network I/O
     * is between them. Expired claims may overlap a slow/crashed sender, so the
     * adapter MUST honor the stable destination idempotency key.
     */
    public int retryPending(int batchSize)
    {
        if (batchSize < 1 || batchSize > 100)
        {
            throw new IllegalArgumentException("Batch size must be between 1 and 100");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive())
        {
            throw new IllegalStateException("Notification delivery must run outside a transaction");
        }
        SettlementNotificationChannel channel = channels.getIfAvailable();
        if (channel == null)
        {
            return 0;
        }
        int sent = 0;
        for (int i = 0; i < batchSize; i++)
        {
            SettlementNotificationRepository.Claim claim = transactions.execute(status -> repository.claim());
            if (claim == null)
            {
                break;
            }
            try
            {
                channel.deliver(claim.message());
            }
            catch (Exception failure)
            {
                transactions.executeWithoutResult(status -> repository.retry(claim));
                if (failure instanceof InterruptedException)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            // If acknowledgement fails, leave the lease recoverable; retry with the SAME key.
            if (Boolean.TRUE.equals(transactions.execute(status -> repository.delivered(claim))))
            {
                sent++;
            }
        }
        return sent;
    }

    private static BigDecimal money(BigDecimal amount)
    {
        if (amount == null || amount.abs().compareTo(LIMIT) > 0)
        {
            throw new IllegalArgumentException("Invalid notification amount");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String hash(String key)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible)
        {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
