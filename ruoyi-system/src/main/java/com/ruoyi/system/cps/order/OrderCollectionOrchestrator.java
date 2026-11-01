package com.ruoyi.system.cps.order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;

/** Bridges only verified feed data to durable ingestion; no synthetic raw payload or event time. */
public class OrderCollectionOrchestrator {
    private final List<OrderCollectionFeed> feeds;
    private final OrderCollectionSettings settings;
    private final OrderIngestionService ingestion;
    private final OrderEventDispatcher dispatcher;
    private final OrderCollectionRetryQueue retries;

    public OrderCollectionOrchestrator(List<OrderCollectionFeed> feeds, OrderCollectionSettings settings,
            OrderIngestionService ingestion, OrderEventDispatcher dispatcher, OrderCollectionRetryQueue retries) {
        this.feeds = List.copyOf(Objects.requireNonNull(feeds));
        this.settings = Objects.requireNonNull(settings);
        this.ingestion = Objects.requireNonNull(ingestion);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.retries = Objects.requireNonNull(retries);
        if (feeds.isEmpty()) throw new IllegalStateException("未配置订单采集数据源");
    }

    public void run(OrderCollectionRetryQueue.TaskType type) {
        run(type, true);
    }

    void run(OrderCollectionRetryQueue.TaskType type, boolean includeRetries) {
        // Outbox redelivery must not depend on the presence of a new source window.
        drainOutbox();
        if (includeRetries) retryPending();
        List<OrderCollectionSettings.Work> work = settings.pending(Objects.requireNonNull(type));
        if (work == null) throw new IllegalStateException("未配置订单采集窗口");
        for (OrderCollectionSettings.Work item : work) {
            validate(item);
            if (item.type() != type) throw new IllegalStateException("订单采集窗口类型不匹配");
            scan(item, null, true);
            settings.completed(item);
        }
        drainOutbox();
    }

    public void retryPending() {
        int limit = settings.retryLimit();
        int lease = settings.retryLeaseSeconds();
        int attempts = settings.retryContentionAttempts();
        if (limit <= 0 || lease <= 0 || attempts <= 0) throw new IllegalStateException("未配置失败窗口重试参数");
        for (int i = 0; i < limit; i++) {
            OrderCollectionRetry item = retries.claimNext(lease, attempts);
            if (item == null) return;
            try {
                OrderCollectionSettings.Work work = new OrderCollectionSettings.Work(UUID.randomUUID(),
                    Platform.valueOf(item.getPlatform()), OrderCollectionRetryQueue.TaskType.valueOf(item.getTaskType()),
                    new TimeWindow(item.getWindowFrom().toInstant(ZoneOffset.UTC), item.getWindowTo().toInstant(ZoneOffset.UTC)));
                scan(work, item.getPageCursor(), false);
                retries.complete(item.getId(), item.getLeaseToken());
            } catch (RuntimeException failure) {
                try { retries.fail(item.getId(), item.getLeaseToken(), settings.nextRetryAt(), "COLLECTION_FAILED"); }
                catch (RuntimeException recordFailure) { failure.addSuppressed(recordFailure); }
                throw failure;
            }
        }
    }

    private void scan(OrderCollectionSettings.Work work, String initialCursor, boolean recordFailure) {
        validate(work);
        String cursor = initialCursor;
        Set<String> visited = new HashSet<>();
        if (cursor != null) visited.add(cursor);
        try {
            OrderCollectionFeed selected = null;
            for (OrderCollectionFeed feed : feeds) if (feed.supports(work.platform(), work.type())) {
                if (selected != null) throw new IllegalStateException("存在多个匹配的订单采集数据源");
                selected = feed;
            }
            if (selected == null) throw new IllegalStateException("未配置该平台的数据源");
            while (true) {
                OrderCollectionFeed.ResultPage page = selected.fetch(work.platform(), work.type(), work.window(), cursor);
                if (page == null || page.entries() == null) throw new IllegalStateException("订单分页结果不完整");
                boolean rejected = false;
                for (OrderCollectionFeed.Entry entry : page.entries()) {
                    if (entry == null || entry.incoming() == null)
                        throw new IllegalStateException("订单原始内容或状态时间缺失");
                    IncomingOrder incoming = entry.incoming();
                    if (incoming.rawJson() == null || incoming.fetchedAt() == null)
                        throw new IllegalStateException("订单原始内容或抓取时间缺失");
                    if (incoming.line() == null || incoming.line().platform() != work.platform()
                        || incoming.allianceStatusTime() == null) {
                        long rawId = ingestion.retainRaw(work.platform(), incoming.rawJson(), incoming.fetchedAt());
                        ingestion.recordSourceException(rawId, "订单行、平台或状态时间无效");
                        rejected = true; continue;
                    }
                    if (!settings.belongsToSystem(work, entry)) { settings.auditRejected(work, entry); continue; }
                    if (entry.recoveryRequired() || entry.recoveredAmount() != null) {
                        ingestion.retainRaw(work.platform(), entry.incoming().rawJson(), entry.incoming().fetchedAt());
                        throw new IllegalStateException("追回金额尚不能由订单入站契约持久化");
                    }
                    OrderIngestionService.Result result = ingestion.ingest(entry.incoming());
                    if (result == null || result.outcome() == OrderIngestionService.Outcome.REJECTED) rejected = true;
                }
                if (rejected || !page.completenessConfirmed())
                    throw new IllegalStateException("分页完整性未确认，窗口保留失败待补偿");
                String next = page.nextCursor();
                if (next == null) return;
                if (next.isBlank() || !visited.add(next)) throw new IllegalStateException("订单分页游标无效或重复");
                cursor = next;
            }
        } catch (RuntimeException failure) {
            if (recordFailure) {
                try { retries.record(work.runId(), work.platform(), work.type(),
                    LocalDateTime.ofInstant(work.window().from(), ZoneOffset.UTC),
                    LocalDateTime.ofInstant(work.window().to(), ZoneOffset.UTC), cursor, "COLLECTION_FAILED"); }
                catch (RuntimeException persistence) { failure.addSuppressed(persistence); }
            }
            throw failure;
        }
    }

    private void drainOutbox() {
        Duration lease = settings.outboxLease();
        int limit = settings.outboxLimit();
        if (lease == null || lease.isZero() || lease.isNegative() || limit <= 0)
            throw new IllegalStateException("未配置订单事件投递参数");
        for (int i = 0; i < limit; i++) if (!dispatcher.dispatchNext(lease)) return;
    }

    private static void validate(OrderCollectionSettings.Work work) {
        if (work == null || work.runId() == null || work.platform() == null || work.type() == null
            || work.window() == null || work.window().from() == null || work.window().to() == null
            || !work.window().from().isBefore(work.window().to()))
            throw new IllegalArgumentException("订单采集窗口无效");
    }
}
