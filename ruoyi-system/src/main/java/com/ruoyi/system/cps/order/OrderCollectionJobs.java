package com.ruoyi.system.cps.order;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Quartz-facing entry points. Missing real providers fail explicitly, never report a no-op as success. */
@Component("orderCollectionJobs")
public class OrderCollectionJobs {
    private final ObjectProvider<OrderCollectionFeed> feeds;
    private final ObjectProvider<OrderCollectionSettings> settings;
    private final ObjectProvider<OrderIngestionService> ingestion;
    private final ObjectProvider<OrderEventDispatcher> dispatcher;
    private final ObjectProvider<OrderCollectionRetryQueue> retries;

    public OrderCollectionJobs(ObjectProvider<OrderCollectionFeed> feeds, ObjectProvider<OrderCollectionSettings> settings,
            ObjectProvider<OrderIngestionService> ingestion, ObjectProvider<OrderEventDispatcher> dispatcher,
            ObjectProvider<OrderCollectionRetryQueue> retries) {
        this.feeds = feeds; this.settings = settings; this.ingestion = ingestion;
        this.dispatcher = dispatcher; this.retries = retries;
    }
    public void incremental() { orchestrator().run(OrderCollectionRetryQueue.TaskType.INCREMENTAL); }
    public void backfill() { orchestrator().run(OrderCollectionRetryQueue.TaskType.BACKFILL); }
    public void afterSale() {
        OrderCollectionOrchestrator service = orchestrator();
        RuntimeException failure = null;
        try { service.run(OrderCollectionRetryQueue.TaskType.REFUND); }
        catch (RuntimeException e) { failure = e; }
        // Retry the shared queue only once: an immediately retryable failed refund
        // must not prevent the independent penalty window from being attempted.
        try { service.run(OrderCollectionRetryQueue.TaskType.PENALTY, false); }
        catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
    private OrderCollectionOrchestrator orchestrator() {
        OrderCollectionSettings config = settings.getIfAvailable();
        OrderIngestionService inbox = ingestion.getIfAvailable();
        OrderEventDispatcher outbox = dispatcher.getIfAvailable();
        OrderCollectionRetryQueue retry = retries.getIfAvailable();
        if (config == null || inbox == null || outbox == null || retry == null)
            throw new IllegalStateException("订单采集缺少配置、入站、出箱或重试服务");
        return new OrderCollectionOrchestrator(feeds.orderedStream().toList(), config, inbox, outbox, retry);
    }
}
