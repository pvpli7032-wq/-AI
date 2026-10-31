package com.ruoyi.system.cps;

import java.time.Instant;
import org.junit.Test;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.union.adapter.Platform;
import static org.junit.Assert.*;

public class OrderEventKeyTest {
    @Test public void repeatedFetchesShareKeyButDistinctEventsAndOrdersDoNot() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        String key = OrderEvent.generateEventKey(Platform.TBK, "order-1", "sub-1", "disputed", time);
        assertEquals(key, OrderEvent.generateEventKey(Platform.TBK, "order-1", "sub-1", "disputed", time));
        assertNotEquals(key, OrderEvent.generateEventKey(Platform.TBK, "order-1", "sub-1", "disputed", time.plusSeconds(1)));
        assertNotEquals(key, OrderEvent.generateEventKey(Platform.TBK, "order-2", "sub-1", "disputed", time));
        assertNotEquals(key, OrderEvent.generateEventKey(Platform.TBK, "order-1", "sub-2", "disputed", time));
        assertEquals(64, key.length());
        assertThrows(NullPointerException.class, () -> OrderEvent.generateEventKey(Platform.TBK, "order-1", "sub-1", "disputed", null));
    }
}
