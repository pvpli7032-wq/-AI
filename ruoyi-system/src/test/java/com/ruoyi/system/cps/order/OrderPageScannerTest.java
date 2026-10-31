package com.ruoyi.system.cps.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import com.ruoyi.union.adapter.Page;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.adapter.UnionAdapter;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OrderPageScannerTest
{
    private final TimeWindow window = new TimeWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));

    @Test public void visitsEveryPageAndPersistsBeforeAdvancing()
    {
        UnionAdapter adapter = mock(UnionAdapter.class);
        when(adapter.platform()).thenReturn(Platform.TBK);
        UnionOrderLine item = new UnionOrderLine(Platform.TBK, "o", "1", "i", "p", null, null, null, null, "12", null, null, null);
        when(adapter.fetchOrders(window, null)).thenReturn(new Page<>(List.of(item), "next"));
        when(adapter.fetchOrders(window, "next")).thenReturn(new Page<>(List.of(item), null));
        List<String> cursors = new ArrayList<>();
        new OrderPageScanner().scan(adapter, window, null, (page, cursor) -> cursors.add(cursor),
            (w, c, e) -> fail("unexpected failure"));
        assertEquals(2, cursors.size());
        assertNull(cursors.get(0));
        assertEquals("next", cursors.get(1));
        verify(adapter).fetchOrders(window, "next");
    }

    @Test public void recordsFailedWindowAndResumableCursor()
    {
        UnionAdapter adapter = mock(UnionAdapter.class);
        when(adapter.fetchOrders(window, null)).thenReturn(new Page<>(List.of(), "next"));
        when(adapter.fetchOrders(window, "next")).thenThrow(new IllegalStateException("unavailable"));
        List<String> failed = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> new OrderPageScanner().scan(adapter, window, null,
            (page, cursor) -> {}, (w, cursor, error) -> { assertEquals(window, w); failed.add(cursor); }));
        assertEquals(List.of("next"), failed);
    }

    @Test public void refusesRepeatedCursorAndDoesNotLoop()
    {
        UnionAdapter adapter = mock(UnionAdapter.class);
        when(adapter.fetchOrders(window, null)).thenReturn(new Page<>(List.of(), "same"));
        when(adapter.fetchOrders(window, "same")).thenReturn(new Page<>(List.of(), "same"));
        List<String> failed = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> new OrderPageScanner().scan(adapter, window, null,
            (page, cursor) -> {}, (w, cursor, error) -> failed.add(cursor)));
        assertEquals(List.of("same"), failed);
        verify(adapter, times(2)).fetchOrders(eq(window), nullable(String.class));
    }
}
