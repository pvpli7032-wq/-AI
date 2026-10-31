package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.util.List;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;

/** Provider must supply actual upstream payload and status time; normalized records alone are insufficient. */
public interface OrderCollectionFeed {
    record Entry(IncomingOrder incoming, String siteId, boolean recoveryRequired, BigDecimal recoveredAmount) {}
    record ResultPage(List<Entry> entries, String nextCursor) {}
    boolean supports(Platform platform, OrderCollectionRetryQueue.TaskType type);
    ResultPage fetch(Platform platform, OrderCollectionRetryQueue.TaskType type, TimeWindow window, String cursor);
}
