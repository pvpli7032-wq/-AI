package com.ruoyi.system.cps.order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;

/** Durable configuration/window provider. Time windows must overlap for incremental collection. */
public interface OrderCollectionSettings {
    record Work(UUID runId, Platform platform, OrderCollectionRetryQueue.TaskType type, TimeWindow window) {}
    List<Work> pending(OrderCollectionRetryQueue.TaskType type);
    /** False for absent, unknown or unverified PID/siteId; never assume ownership. */
    boolean belongsToSystem(Work work, OrderCollectionFeed.Entry entry);
    /** Record rejected, non-owned items for audit without ingesting them as our orders. */
    void auditRejected(Work work, OrderCollectionFeed.Entry entry);
    /** Advance a durable checkpoint only after every page was processed. */
    void completed(Work work);
    int retryLeaseSeconds();
    int retryContentionAttempts();
    int retryLimit();
    LocalDateTime nextRetryAt();
    Duration outboxLease();
    int outboxLimit();
}
