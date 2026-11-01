package com.ruoyi.system.cps.order;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OrderCollectionOrchestratorTest {
    private OrderCollectionFeed feed;
    private OrderCollectionSettings settings;
    private OrderIngestionService ingestion;
    private OrderEventDispatcher dispatcher;
    private OrderCollectionRetryQueue retries;
    private OrderCollectionOrchestrator runner;
    private OrderCollectionSettings.Work work;
    private OrderCollectionFeed.Entry entry;
    private final Instant start = Instant.parse("2020-01-01T00:00:00Z");
    private final Instant end = Instant.parse("2020-01-01T01:00:00Z");

    @Before public void setup() {
        feed = mock(OrderCollectionFeed.class);
        settings = mock(OrderCollectionSettings.class);
        ingestion = mock(OrderIngestionService.class);
        dispatcher = mock(OrderEventDispatcher.class);
        retries = mock(OrderCollectionRetryQueue.class);
        runner = new OrderCollectionOrchestrator(List.of(feed), settings, ingestion, dispatcher, retries);
        work = new OrderCollectionSettings.Work(UUID.randomUUID(), Platform.TBK,
            OrderCollectionRetryQueue.TaskType.INCREMENTAL, new TimeWindow(start, end));
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, "test-order", "", "item", "our-pid", null,
            new BigDecimal("1.00"), null, null, "test-status", start, null, null);
        entry = new OrderCollectionFeed.Entry(new IncomingOrder("{\"real\":true}", end, start, line), "our-site", false, null);
        when(settings.retryLimit()).thenReturn(1);
        when(settings.retryLeaseSeconds()).thenReturn(60);
        when(settings.retryContentionAttempts()).thenReturn(5);
        when(settings.outboxLease()).thenReturn(Duration.ofSeconds(60));
        when(settings.outboxLimit()).thenReturn(5);
        when(settings.pending(OrderCollectionRetryQueue.TaskType.INCREMENTAL)).thenReturn(List.of(work));
        when(settings.belongsToSystem(any(), any())).thenReturn(true);
        when(feed.supports(Platform.TBK, OrderCollectionRetryQueue.TaskType.INCREMENTAL)).thenReturn(true);
        when(ingestion.ingest(any())).thenReturn(new OrderIngestionService.Result(1L, 2L, "event", OrderIngestionService.Outcome.APPLIED));
    }

    @Test public void completePaginationUsesActualRawStatusTimeAndOnlyThenAdvancesCheckpoint() {
        when(feed.fetch(eq(Platform.TBK), any(), any(), isNull()))
            .thenReturn(new OrderCollectionFeed.ResultPage(List.of(entry), "next+/="));
        when(feed.fetch(eq(Platform.TBK), any(), any(), eq("next+/=")))
            .thenReturn(new OrderCollectionFeed.ResultPage(List.of(entry), null));
        runner.run(OrderCollectionRetryQueue.TaskType.INCREMENTAL);
        verify(ingestion, times(2)).ingest(entry.incoming());
        verify(settings).completed(work);
        verify(dispatcher, times(2)).dispatchNext(Duration.ofSeconds(60));
    }

    @Test public void failedPageIsPersistedAndCheckpointDoesNotAdvance() {
        when(feed.fetch(eq(Platform.TBK), any(), any(), isNull()))
            .thenReturn(new OrderCollectionFeed.ResultPage(List.of(entry), "page-2"));
        when(feed.fetch(eq(Platform.TBK), any(), any(), eq("page-2")))
            .thenThrow(new IllegalStateException("synthetic private upstream message"));
        assertThrows(IllegalStateException.class, () -> runner.run(OrderCollectionRetryQueue.TaskType.INCREMENTAL));
        verify(retries).record(work.runId(), Platform.TBK, work.type(), LocalDateTime.ofInstant(start, java.time.ZoneOffset.UTC),
            LocalDateTime.ofInstant(end, java.time.ZoneOffset.UTC), "page-2", "COLLECTION_FAILED");
        verify(settings, never()).completed(any());
    }

    @Test public void retryStartsAtPersistedCursorAndAcknowledgesOnlyAfterSuccess() {
        OrderCollectionRetry retry = new OrderCollectionRetry();
        retry.setId(19L); retry.setLeaseToken(UUID.randomUUID().toString());
        retry.setPlatform("TBK"); retry.setTaskType("INCREMENTAL"); retry.setPageCursor("page+/=");
        retry.setWindowFrom(LocalDateTime.of(2020, 1, 1, 0, 0)); retry.setWindowTo(LocalDateTime.of(2020, 1, 1, 1, 0));
        when(retries.claimNext(60, 5)).thenReturn(retry);
        when(feed.fetch(eq(Platform.TBK), any(), any(), eq("page+/=")))
            .thenReturn(new OrderCollectionFeed.ResultPage(List.of(entry), null));
        runner.retryPending();
        verify(ingestion).ingest(entry.incoming());
        verify(retries).complete(19L, retry.getLeaseToken());
    }

    @Test public void unknownOwnershipIsAuditedAndNeverIngested() {
        when(settings.belongsToSystem(work, entry)).thenReturn(false);
        when(feed.fetch(eq(Platform.TBK), any(), any(), isNull()))
            .thenReturn(new OrderCollectionFeed.ResultPage(List.of(entry), null));
        runner.run(work.type());
        verify(settings).auditRejected(work, entry);
        verifyNoInteractions(ingestion);
    }

    @Test public void recoveryAmountCannotBeDroppedAndWindowIsPersisted() {
        OrderCollectionFeed.Entry refund = new OrderCollectionFeed.Entry(entry.incoming(), "our-site", true, new BigDecimal("0.10"));
        when(feed.fetch(eq(Platform.TBK), any(), any(), isNull()))
            .thenReturn(new OrderCollectionFeed.ResultPage(List.of(refund), null));
        assertThrows(IllegalStateException.class, () -> runner.run(work.type()));
        verify(ingestion).retainRaw(Platform.TBK, entry.incoming().rawJson(), entry.incoming().fetchedAt());
        verify(ingestion, never()).ingest(any());
        verify(retries).record(eq(work.runId()), eq(Platform.TBK), eq(work.type()), any(), any(), isNull(), eq("COLLECTION_FAILED"));
        verify(settings, never()).completed(any());
    }

    @Test public void incompleteOrderWithRealRawIsRetainedBeforeRetry() {
        UnionOrderLine foreignPlatform = new UnionOrderLine(Platform.JD, "order", "", "item", "pid", null,
            BigDecimal.ONE, null, null, "status", start, null, null);
        IncomingOrder[] invalid = {
            new IncomingOrder("{\"raw\":1}", end, start, null),
            new IncomingOrder("{\"raw\":2}", end, null, entry.incoming().line()),
            new IncomingOrder("{\"raw\":3}", end, start, foreignPlatform)
        };
        for (IncomingOrder source : invalid) {
            when(feed.fetch(eq(Platform.TBK), any(), any(), isNull()))
                .thenReturn(new OrderCollectionFeed.ResultPage(List.of(new OrderCollectionFeed.Entry(source, null, false, null)), null));
            assertThrows(IllegalStateException.class, () -> runner.run(work.type()));
            verify(ingestion).retainRaw(Platform.TBK, source.rawJson(), end);
        }
        verify(ingestion, never()).ingest(any());
        verify(retries, times(3)).record(eq(work.runId()), eq(Platform.TBK), eq(work.type()), any(), any(), isNull(), eq("COLLECTION_FAILED"));
        verify(settings, never()).completed(any());
    }

    @Test public void absentRawOrFetchTimeIsNeverFabricated() {
        IncomingOrder[] invalid = {
            new IncomingOrder(null, end, start, entry.incoming().line()),
            new IncomingOrder("{\"raw\":1}", null, start, entry.incoming().line())
        };
        for (IncomingOrder source : invalid) {
            when(feed.fetch(eq(Platform.TBK), any(), any(), isNull()))
                .thenReturn(new OrderCollectionFeed.ResultPage(List.of(new OrderCollectionFeed.Entry(source, null, false, null)), null));
            assertThrows(IllegalStateException.class, () -> runner.run(work.type()));
        }
        verifyNoInteractions(ingestion);
        verify(retries, times(2)).record(eq(work.runId()), eq(Platform.TBK), eq(work.type()), any(), any(), isNull(), eq("COLLECTION_FAILED"));
        verify(settings, never()).completed(any());
    }

    @Test public void absentFeedFailsRatherThanReturningSuccess() {
        assertThrows(IllegalStateException.class, () -> new OrderCollectionOrchestrator(List.of(), settings, ingestion, dispatcher, retries));
    }

    @Test public void emptyNewWindowsStillRetryPendingOutbox() {
        when(settings.pending(work.type())).thenReturn(List.of());
        when(dispatcher.dispatchNext(Duration.ofSeconds(60))).thenReturn(true, false, false);
        runner.run(work.type());
        verify(dispatcher, times(3)).dispatchNext(Duration.ofSeconds(60));
        verifyNoInteractions(ingestion);
        verify(settings, never()).completed(any());
    }

    @Test public void failedRetrySchedulesTheConfiguredNextAttempt() {
        OrderCollectionRetry retry = new OrderCollectionRetry();
        retry.setId(19L); retry.setLeaseToken(UUID.randomUUID().toString());
        retry.setPlatform("TBK"); retry.setTaskType("INCREMENTAL"); retry.setPageCursor("page-2");
        retry.setWindowFrom(LocalDateTime.of(2020, 1, 1, 0, 0)); retry.setWindowTo(LocalDateTime.of(2020, 1, 1, 1, 0));
        LocalDateTime next = LocalDateTime.of(2020, 1, 2, 0, 0);
        when(settings.nextRetryAt()).thenReturn(next);
        when(retries.claimNext(60, 5)).thenReturn(retry);
        when(feed.fetch(eq(Platform.TBK), any(), any(), eq("page-2")))
            .thenThrow(new IllegalStateException("synthetic failure"));
        assertThrows(IllegalStateException.class, runner::retryPending);
        verify(retries).fail(19L, retry.getLeaseToken(), next, "COLLECTION_FAILED");
        verify(retries, never()).complete(anyLong(), anyString());
    }

    @Test public void afterSaleAttemptsPenaltyWhenRefundFails() {
        OrderCollectionSettings.Work refund = new OrderCollectionSettings.Work(UUID.randomUUID(), Platform.TBK,
            OrderCollectionRetryQueue.TaskType.REFUND, new TimeWindow(start, end));
        OrderCollectionSettings.Work penalty = new OrderCollectionSettings.Work(UUID.randomUUID(), Platform.TBK,
            OrderCollectionRetryQueue.TaskType.PENALTY, new TimeWindow(start, end));
        when(settings.pending(refund.type())).thenReturn(List.of(refund));
        when(settings.pending(penalty.type())).thenReturn(List.of(penalty));
        when(feed.supports(Platform.TBK, refund.type())).thenReturn(true);
        when(feed.supports(Platform.TBK, penalty.type())).thenReturn(true);
        when(feed.fetch(eq(Platform.TBK), eq(refund.type()), any(), isNull()))
            .thenThrow(new IllegalStateException("synthetic refund failure"));
        when(feed.fetch(eq(Platform.TBK), eq(penalty.type()), any(), isNull()))
            .thenReturn(new OrderCollectionFeed.ResultPage(List.of(), null));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("feed", feed); beans.registerSingleton("settings", settings);
        beans.registerSingleton("ingestion", ingestion); beans.registerSingleton("dispatcher", dispatcher);
        beans.registerSingleton("retries", retries);
        OrderCollectionJobs jobs = new OrderCollectionJobs(beans.getBeanProvider(OrderCollectionFeed.class),
            beans.getBeanProvider(OrderCollectionSettings.class), beans.getBeanProvider(OrderIngestionService.class),
            beans.getBeanProvider(OrderEventDispatcher.class), beans.getBeanProvider(OrderCollectionRetryQueue.class));
        assertThrows(IllegalStateException.class, jobs::afterSale);
        verify(settings).completed(penalty);
        verify(retries).record(eq(refund.runId()), eq(Platform.TBK), eq(refund.type()), any(), any(), isNull(), eq("COLLECTION_FAILED"));
    }
}
