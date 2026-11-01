package com.ruoyi.system.cps.order;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.ruoyi.system.cps.attribution.AttributionDecision;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.settlement.SettlementService;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OrderEventDispatcherTest
{
    private static Map<String,Object> event(boolean attributionDone, boolean settlementDone)
    {
        Map<String,Object> row = new HashMap<>();
        row.put("line_snapshot", "{\"platform\":\"tbk\",\"orderNo\":\"o\",\"subOrderNo\":\"1\",\"rawStatus\":\"12\",\"paidAt\":\"2026-01-01T00:00:00.123456789Z\"}");
        row.put("order_line_id", 10L); row.put("event_key", "event-key"); row.put("to_status", "PAID");
        row.put("occurred_second", 1767225600L); row.put("occurred_nano", 123456789);
        row.put("attribution_done", attributionDone); row.put("settlement_done", settlementDone);
        return row;
    }

    @Test public void dispatchesFirstOrderToBothBoundaries()
    {
        OrderIngestionService inbox = mock(OrderIngestionService.class);
        AttributionService attribution = mock(AttributionService.class);
        SettlementService settlement = mock(SettlementService.class);
        when(inbox.claimPending(eq(1), any())).thenReturn(List.of(new OrderIngestionService.Claim(5L, "token", event(false, false))));
        when(attribution.decide(eq(10L), any())).thenReturn(mock(AttributionDecision.class));
        when(inbox.markAttributionDone(5L, "token")).thenReturn(true);
        when(inbox.markSettlementDone(5L, "token")).thenReturn(true);
        when(inbox.acknowledge(5L, "token")).thenReturn(true);
        assertTrue(new OrderEventDispatcher(inbox, attribution, settlement).dispatchNext(Duration.ofSeconds(30)));
        verify(attribution).decide(eq(10L), any());
        verify(settlement).onOrderEvent(argThat((OrderEvent e) -> e.eventKey().equals("event-key")
            && e.occurredAt().getNano() == 123456789 && e.line().paidAt().getNano() == 123456789));
        verify(inbox).acknowledge(5L, "token");
    }

    @Test public void failedSettlementIsReleasedAndSuccessfulAttributionIsNotRepeated()
    {
        OrderIngestionService inbox = mock(OrderIngestionService.class);
        AttributionService attribution = mock(AttributionService.class);
        SettlementService settlement = mock(SettlementService.class);
        when(inbox.claimPending(eq(1), any())).thenReturn(List.of(new OrderIngestionService.Claim(5L, "token", event(true, false))));
        doThrow(new IllegalStateException("retry")).when(settlement).onOrderEvent(any());
        assertThrows(IllegalStateException.class, () -> new OrderEventDispatcher(inbox, attribution, settlement).dispatchNext(Duration.ofSeconds(30)));
        verifyNoInteractions(attribution);
        verify(inbox).release(5L, "token");
        verify(inbox, never()).acknowledge(anyLong(), anyString());
    }
}
