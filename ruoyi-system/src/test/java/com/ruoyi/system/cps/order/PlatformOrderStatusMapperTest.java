package com.ruoyi.system.cps.order;

import org.junit.Test;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.union.adapter.Platform;
import static org.junit.Assert.*;

public class PlatformOrderStatusMapperTest
{
    private final PlatformOrderStatusMapper mapper = new PlatformOrderStatusMapper();

    @Test
    public void mapsDocumentedTaobaoCodesSeparately()
    {
        assertEquals(OrderStatus.PAID, mapper.map(Platform.TBK, "12"));
        assertEquals(OrderStatus.INVALID, mapper.map(Platform.TBK, "13"));
        assertEquals(OrderStatus.RECEIVED, mapper.map(Platform.TBK, "14"));
        assertEquals(OrderStatus.SETTLED, mapper.map(Platform.TBK, "3"));
    }

    @Test
    public void mapsDocumentedPddCodesSeparately()
    {
        assertEquals(OrderStatus.PAID, mapper.map(Platform.PDD, "0"));
        assertEquals(OrderStatus.RECEIVED, mapper.map(Platform.PDD, "2"));
        assertEquals(OrderStatus.SETTLED, mapper.map(Platform.PDD, "5"));
    }

    @Test
    public void unknownOrAmbiguousCodesAreRejected()
    {
        for (String status : new String[] {"-1", "1", "3", "4", "8", "unknown"})
            assertThrows(IllegalArgumentException.class, () -> mapper.map(Platform.PDD, status));
        for (String status : new String[] {"16", "17", "3", "unknown"})
            assertThrows(IllegalArgumentException.class, () -> mapper.map(Platform.JD, status));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(Platform.TBK, "unknown"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(null, "12"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(Platform.TBK, null));
    }
}
