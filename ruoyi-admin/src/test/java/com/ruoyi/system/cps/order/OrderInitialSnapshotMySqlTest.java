package com.ruoyi.system.cps.order;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;

/** First collection is the real provider snapshot, never a fabricated history. Real MySQL 8. */
public class OrderInitialSnapshotMySqlTest {
    private static JdbcTemplate jdbc;
    private static OrderIngestionService service;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456789Z");

    @BeforeClass public static void setup() throws Exception {
        OrderIngestionMySqlTest.setup();
        jdbc = (JdbcTemplate) fixture("jdbc");
        service = (OrderIngestionService) fixture("service");
    }
    private static Object fixture(String name) throws Exception {
        Field field = OrderIngestionMySqlTest.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(null);
    }
    @AfterClass public static void cleanup() throws Exception { OrderIngestionMySqlTest.cleanup(); }

    private static IncomingOrder input(String order, String code, Instant time, OrderStatus target) {
        String raw = "{\"order\":\"" + order + "\",\"status\":\"" + code + "\"}";
        IncomingOrder input = IncomingOrder.from(new UnionOrderLine(Platform.TBK, order, "sub", "item",
            "mm_1_2_3", null, new BigDecimal("100"), new BigDecimal("3"), new BigDecimal("3"),
            code, null, null, null, raw, time), TIME.plusSeconds(86400));
        return target == null ? input : input.resolved(target, false, "TBK_REFUND");
    }

    private static void initial(String code, OrderStatus target, boolean resolved) {
        String order = "snapshot-" + UUID.randomUUID();
        IncomingOrder input = input(order, code, TIME, resolved ? target : null);
        var result = service.ingest(input);
        assertEquals(OrderIngestionService.Outcome.APPLIED, result.outcome());
        long id = result.orderLineId();
        assertEquals(target.name(), jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, id));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, id));
        assertNull(jdbc.queryForObject("SELECT from_status FROM cps_order_event WHERE event_key=?", String.class, result.eventKey()));
        assertEquals(target.name(), jdbc.queryForObject("SELECT to_status FROM cps_order_event WHERE event_key=?", String.class, result.eventKey()));
        assertEquals(Long.valueOf(TIME.getEpochSecond()), jdbc.queryForObject("SELECT cps_status_second FROM sys_order_commission WHERE id=?", Long.class, id));
        assertEquals(Integer.valueOf(TIME.getNano()), jdbc.queryForObject("SELECT cps_status_nano FROM sys_order_commission WHERE id=?", Integer.class, id));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM sys_order_commission WHERE id=? AND paid_time IS NULL AND received_time IS NULL AND settle_time IS NULL AND cps_dispute_previous IS NULL", Long.class, id));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM sys_order_commission WHERE id=? AND cps_paid_second IS NULL AND cps_paid_nano IS NULL AND cps_settled_second IS NULL AND cps_settled_nano IS NULL AND cps_fast_settlement IS NULL", Long.class, id));
        assertNull(jdbc.queryForObject("SELECT previous_status FROM cps_order_event WHERE event_key=?", String.class, result.eventKey()));
        assertEquals(Long.valueOf(TIME.getEpochSecond()), jdbc.queryForObject("SELECT occurred_second FROM cps_order_event_outbox WHERE event_key=?", Long.class, result.eventKey()));
        assertEquals(Integer.valueOf(TIME.getNano()), jdbc.queryForObject("SELECT occurred_nano FROM cps_order_event_outbox WHERE event_key=?", Integer.class, result.eventKey()));
        assertEquals(input.rawJson(), jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, result.rawId()));
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, service.replay(result.rawId()).outcome());
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event_outbox WHERE event_key=?", Long.class, result.eventKey()));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, id));
    }

    @Test public void firstReceivedIsNotRebuiltFromPaid() { initial("14", OrderStatus.RECEIVED, false); }
    @Test public void firstSettledIsNotRebuiltFromPaidOrReceived() { initial("3", OrderStatus.SETTLED, false); }
    @Test public void firstPartialRefundIsNotRebuiltFromDispute() { initial("2", OrderStatus.PARTIAL_REFUND, true); }
    @Test public void firstFullRefundIsNotRebuiltFromDispute() { initial("13", OrderStatus.FULL_REFUND, true); }
    @Test public void firstInvalidKeepsItsNonSettlingState() { initial("13", OrderStatus.INVALID, false); }

    @Test public void firstSettledPreservesSuppliedProviderTimesWithoutSyntheticEvents() {
        String order = "supplied-times-" + UUID.randomUUID();
        Instant paid = TIME.minusSeconds(90000);
        Instant received = TIME.minusSeconds(3600).plusNanos(1);
        Instant settled = TIME.minusSeconds(60).plusNanos(2);
        String raw = "{\"order\":\"" + order + "\",\"status\":\"3\"}";
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, order, "sub", "item", "mm_1_2_3", null,
            new BigDecimal("100"), new BigDecimal("3"), new BigDecimal("3"), "3",
            paid, received, settled, raw, TIME);
        var result = service.ingest(IncomingOrder.from(line, TIME.plusSeconds(86400)));
        assertEquals(OrderIngestionService.Outcome.APPLIED, result.outcome());
        long id = result.orderLineId();
        assertEquals("SETTLED", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT from_status FROM cps_order_event WHERE event_key=?", String.class, result.eventKey()));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, id));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM sys_order_commission WHERE id=? AND paid_time IS NOT NULL AND received_time IS NOT NULL AND settle_time IS NOT NULL", Long.class, id));
        assertEquals(Long.valueOf(paid.getEpochSecond()), jdbc.queryForObject("SELECT cps_paid_second FROM sys_order_commission WHERE id=?", Long.class, id));
        assertEquals(Integer.valueOf(paid.getNano()), jdbc.queryForObject("SELECT cps_paid_nano FROM sys_order_commission WHERE id=?", Integer.class, id));
        assertEquals(Long.valueOf(settled.getEpochSecond()), jdbc.queryForObject("SELECT cps_settled_second FROM sys_order_commission WHERE id=?", Long.class, id));
        assertEquals(Integer.valueOf(settled.getNano()), jdbc.queryForObject("SELECT cps_settled_nano FROM sys_order_commission WHERE id=?", Integer.class, id));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT cps_fast_settlement FROM sys_order_commission WHERE id=?", Integer.class, id));
        var snapshot = com.alibaba.fastjson2.JSON.parseObject(jdbc.queryForObject("SELECT line_snapshot FROM cps_order_event_outbox WHERE event_key=?", String.class, result.eventKey()));
        assertEquals(paid, snapshot.getInstant("paidAt"));
        assertEquals(received, snapshot.getInstant("receivedAt"));
        assertEquals(settled, snapshot.getInstant("settledAt"));
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, service.replay(result.rawId()).outcome());
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, id));
    }

    @Test public void firstDisputedCannotRestoreAnUnknownPreviousState() {
        String order = "unknown-previous-" + UUID.randomUUID();
        var disputed = service.ingest(input(order, "4", TIME, OrderStatus.DISPUTED));
        assertEquals(OrderIngestionService.Outcome.APPLIED, disputed.outcome());
        long id = disputed.orderLineId();
        assertNull(jdbc.queryForObject("SELECT from_status FROM cps_order_event WHERE event_key=?", String.class, disputed.eventKey()));
        assertNull(jdbc.queryForObject("SELECT previous_status FROM cps_order_event WHERE event_key=?", String.class, disputed.eventKey()));
        assertNull(jdbc.queryForObject("SELECT cps_dispute_previous FROM sys_order_commission WHERE id=?", String.class, id));
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, service.replay(disputed.rawId()).outcome());
        IncomingOrder restore = input(order, "5", TIME.plusSeconds(1), null).resolved(null, true, "TBK_REFUND");
        var rejected = service.ingest(restore);
        assertEquals(OrderIngestionService.Outcome.REJECTED, rejected.outcome());
        assertEquals("DISPUTED", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT cps_dispute_previous FROM sys_order_commission WHERE id=?", String.class, id));
        assertEquals(Long.valueOf(TIME.getEpochSecond()), jdbc.queryForObject("SELECT cps_status_second FROM sys_order_commission WHERE id=?", Long.class, id));
        assertEquals(Integer.valueOf(TIME.getNano()), jdbc.queryForObject("SELECT cps_status_nano FROM sys_order_commission WHERE id=?", Integer.class, id));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, id));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event_outbox o JOIN cps_order_event e ON e.id=o.event_id WHERE e.order_line_id=?", Long.class, id));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, rejected.rawId()));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM sys_order_commission WHERE id=? AND paid_time IS NULL AND received_time IS NULL AND settle_time IS NULL", Long.class, id));
    }

    @Test public void paidToDisputedRemainsAnException() {
        String order = "paid-dispute-" + UUID.randomUUID();
        var paid = service.ingest(input(order, "12", TIME, null));
        assertEquals(OrderIngestionService.Outcome.APPLIED, paid.outcome());
        var rejected = service.ingest(input(order, "4", TIME.plusSeconds(1), OrderStatus.DISPUTED));
        assertEquals(OrderIngestionService.Outcome.REJECTED, rejected.outcome());
        assertEquals("PAID", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, paid.orderLineId()));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, paid.orderLineId()));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, rejected.rawId()) > 0);
    }

    @Test public void firstSettledWithoutAllianceTimeIsStillRejectedAndNeverUsesFetchTime() {
        var rejected = service.ingest(input("missing-time-" + UUID.randomUUID(), "3", null, null));
        assertEquals(OrderIngestionService.Outcome.REJECTED, rejected.outcome());
        String replay = jdbc.queryForObject("SELECT input_json FROM cps_order_replay_input WHERE raw_id=?", String.class, rejected.rawId());
        assertNull(com.alibaba.fastjson2.JSON.parseObject(replay).getInstant("allianceStatusTime"));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE raw_id=?", Long.class, rejected.rawId()));
    }
}
