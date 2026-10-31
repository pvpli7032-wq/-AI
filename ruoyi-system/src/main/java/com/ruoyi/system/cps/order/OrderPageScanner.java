package com.ruoyi.system.cps.order;

import java.util.HashSet;
import java.util.Set;
import com.ruoyi.union.adapter.Page;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.adapter.UnionAdapter;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Pages are handed to a durable sink before their cursor is advanced. */
public final class OrderPageScanner
{
    @FunctionalInterface
    public interface PageSink
    {
        void persist(Page<UnionOrderLine> page, String requestedCursor);
    }

    @FunctionalInterface
    public interface FailureSink
    {
        /** Implementations must persist the window and cursor for later retry. */
        void record(TimeWindow window, String cursor, RuntimeException failure);
    }

    public void scan(UnionAdapter adapter, TimeWindow window, String initialCursor,
        PageSink sink, FailureSink failures)
    {
        if (adapter == null || window == null || window.from() == null || window.to() == null
            || !window.from().isBefore(window.to()) || sink == null || failures == null)
        {
            throw new IllegalArgumentException("订单采集参数或时间窗口无效");
        }
        String cursor = initialCursor;
        Set<String> visited = new HashSet<>();
        if (cursor != null) visited.add(cursor);
        try
        {
            while (true)
            {
                Page<UnionOrderLine> page = adapter.fetchOrders(window, cursor);
                if (page == null || page.items() == null)
                    throw new IllegalStateException("联盟订单分页返回不完整");
                for (UnionOrderLine line : page.items())
                {
                    if (line == null || line.platform() != adapter.platform())
                        throw new IllegalStateException("订单分页包含错误的平台");
                }
                sink.persist(page, cursor);
                String next = page.nextCursor();
                if (next == null) return;
                if (next.isBlank() || !visited.add(next))
                    throw new IllegalStateException("订单分页游标无效或重复");
                cursor = next;
            }
        }
        catch (RuntimeException failure)
        {
            try { failures.record(window, cursor, failure); }
            catch (RuntimeException recordFailure) { failure.addSuppressed(recordFailure); }
            throw failure;
        }
    }
}
