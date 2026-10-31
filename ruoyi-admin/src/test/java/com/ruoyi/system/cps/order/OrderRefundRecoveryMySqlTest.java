package com.ruoyi.system.cps.order;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.attribution.AttributionDecision;
import com.ruoyi.system.cps.attribution.AttributionMethod;
import com.ruoyi.system.cps.attribution.AttributionConfidence;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.mapper.OrderAuxiliaryEvidenceMapper;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real MySQL, immutable replay and actual dispatcher boundary; no skipped or H2 substitutes. */
public class OrderRefundRecoveryMySqlTest {
    private static OrderIngestionService ingestion;
    private static JdbcTemplate jdbc;
    private static final Instant TIME = Instant.parse("2026-01-01T01:00:00.123456789Z");
    @BeforeClass public static void setup() throws Exception {
        OrderIngestionMySqlTest.setup();
        ingestion = (OrderIngestionService) fixture("service");
        jdbc = (JdbcTemplate) fixture("jdbc");
    }
    private static Object fixture(String name) throws Exception {
        Field field = OrderIngestionMySqlTest.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    @AfterClass public static void cleanup() throws Exception { OrderIngestionMySqlTest.cleanup(); }
    private static IncomingOrder input(Platform platform, String order, String code, Instant at, String actual, String raw) {
        return IncomingOrder.from(new UnionOrderLine(platform, order, "sub", "item", "mm_1_2_3", null,
            new BigDecimal("100"), new BigDecimal("7.7777"), actual == null ? null : new BigDecimal(actual),
            code, null, null, null, raw, at), TIME.plusSeconds(100));
    }
    private static JSONObject snapshot(String key) {
        return JSON.parseObject(jdbc.queryForObject("SELECT line_snapshot FROM cps_order_event_outbox WHERE event_key=?", String.class, key));
    }
    private static void money(String expected, BigDecimal actual) {
        assertNotNull(actual); assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
    private static String id() { return "refund-" + UUID.randomUUID(); }
    @Test public void paidToSettledAndEveryReceivedSettledRefundPathRetainRealHistoryAndReplay() {
        for (Platform platform : List.of(Platform.TBK, Platform.JD, Platform.PDD)) {
            String direct = id();
            var paid = ingestion.ingest(input(platform, direct, "paid", TIME, "5", "{\"stage\":1}").resolved(OrderStatus.PAID, false, "TEST"));
            assertEquals(OrderIngestionService.Outcome.APPLIED, paid.outcome());
            var settled = ingestion.ingest(input(platform, direct, "settled", TIME.plusSeconds(1), "5", "{\"stage\":2}").resolved(OrderStatus.SETTLED, false, "TEST"));
            assertEquals(OrderIngestionService.Outcome.APPLIED, settled.outcome());
            assertEquals(OrderIngestionService.Outcome.DUPLICATE, ingestion.replay(settled.rawId()).outcome());
            assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, paid.orderLineId()));
            for (OrderStatus from : List.of(OrderStatus.RECEIVED, OrderStatus.SETTLED)) {
                for (OrderStatus to : List.of(OrderStatus.PARTIAL_REFUND, OrderStatus.FULL_REFUND)) {
                    String order = id();
                    var original = ingestion.ingest(input(platform, order, "original", TIME, "5.4321", "{\"original\":true}").resolved(from, false, "TEST"));
                    assertEquals(OrderIngestionService.Outcome.APPLIED, original.outcome());
                    String raw = "{ \"refund_fee\":100, \"tk_commission_fee_refund_pub\":99, \"exact\":  true }";
                    IncomingOrder refund = input(platform, order, "refund", TIME.plusSeconds(1), "0.1111", raw).resolved(to, false, "TEST");
                    var result = ingestion.ingest(refund);
                    assertEquals(OrderIngestionService.Outcome.APPLIED, result.outcome());
                    JSONObject saved = snapshot(result.eventKey());
                    money("5.4321", saved.getBigDecimal("refundBasisAmount"));
                    if (to == OrderStatus.FULL_REFUND) money("5.4321", saved.getBigDecimal("recoveredAmount"));
                    else {
                        assertNull(saved.getBigDecimal("recoveredAmount"));
                        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=? AND reason LIKE '%线11暂停转人工%'", Long.class, result.rawId()) > 0);
                    }
                    assertEquals(raw, jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, result.rawId()));
                    assertEquals(OrderIngestionService.Outcome.DUPLICATE, ingestion.replay(result.rawId()).outcome());
                    assertEquals(from.name(), jdbc.queryForObject("SELECT from_status FROM cps_order_event WHERE event_key=?", String.class, result.eventKey()));
                    assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, result.orderLineId()));
                    assertNull(jdbc.queryForObject("SELECT cps_dispute_previous FROM sys_order_commission WHERE id=?", String.class, result.orderLineId()));
                }
            }
        }
    }
    @Test public void fullRefundWithoutOriginalActualCommissionDoesNotInventFromRefundOrEstimate() {
        String order = id();
        ingestion.ingest(input(Platform.TBK, order, "original", TIME, null, "{}").resolved(OrderStatus.RECEIVED, false, "TEST"));
        var result = ingestion.ingest(input(Platform.TBK, order, "refund", TIME.plusSeconds(1), "99", "{\"refund_fee\":100}").resolved(OrderStatus.FULL_REFUND, false, "TEST"));
        assertEquals(OrderIngestionService.Outcome.APPLIED, result.outcome());
        assertNull(snapshot(result.eventKey()).getBigDecimal("recoveredAmount"));
        assertNull(snapshot(result.eventKey()).getBigDecimal("refundBasisAmount"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=? AND reason LIKE '%缺原订单实际联盟佣金%'", Long.class, result.rawId()) > 0);
        var firstSeen = ingestion.ingest(input(Platform.JD, id(), "refund", TIME, "99", "{}").resolved(OrderStatus.FULL_REFUND, false, "TEST"));
        assertEquals(OrderIngestionService.Outcome.APPLIED, firstSeen.outcome());
        assertNull(snapshot(firstSeen.eventKey()).getBigDecimal("recoveredAmount"));
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, ingestion.replay(result.rawId()).outcome());
    }
    @Test public void actualOutboxDeliveryAndRetryPreserveFullAmountPartialNullAndExactEvidence() {
        String fullOrder = id(), partialOrder = id();
        ingestion.ingest(input(Platform.TBK, fullOrder, "original", TIME, "5.4321", "{}").resolved(OrderStatus.SETTLED, false, "TEST"));
        ingestion.ingest(input(Platform.TBK, partialOrder, "original", TIME, "5.4321", "{}").resolved(OrderStatus.RECEIVED, false, "TEST"));
        String raw = "{ \"refund_fee\":100, \"tk_commission_fee_refund_pub\":17, \"spacing\":  true }";
        var full = ingestion.ingest(input(Platform.TBK, fullOrder, "refund", TIME.plusSeconds(1), "0", raw).resolved(OrderStatus.FULL_REFUND, false, "TEST"));
        var partial = ingestion.ingest(input(Platform.TBK, partialOrder, "refund", TIME.plusSeconds(1), "0", raw).resolved(OrderStatus.PARTIAL_REFUND, false, "TEST"));
        AttributionService attribution = mock(AttributionService.class);
        when(attribution.decide(anyLong(), any())).thenReturn(new AttributionDecision(123L, AttributionMethod.ATTRIBUTION_TOKEN, AttributionConfidence.HIGH, null));
        SettlementService settlement = mock(SettlementService.class);
        var failedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            OrderEvent event = call.getArgument(0);
            if (full.eventKey().equals(event.eventKey()) && !failedOnce.getAndSet(true)) throw new IllegalStateException("simulated downstream failure");
            return null;
        }).when(settlement).onOrderEvent(any());
        var dispatcher = new OrderEventDispatcher(ingestion, attribution, settlement);
        assertThrows(IllegalStateException.class, () -> { while (dispatcher.dispatchNext(Duration.ofSeconds(60))) { } });
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM cps_order_event_outbox WHERE event_key=?", String.class, full.eventKey()));
        var restarted = new OrderEventDispatcher(ingestion, attribution, settlement);
        while (restarted.dispatchNext(Duration.ofSeconds(60))) { }
        var capture = org.mockito.ArgumentCaptor.forClass(OrderEvent.class);
        verify(settlement, atLeastOnce()).onOrderEvent(capture.capture());
        List<OrderEvent> fullEvents = capture.getAllValues().stream().filter(e -> full.eventKey().equals(e.eventKey())).toList();
        assertEquals(2, fullEvents.size());
        for (OrderEvent event : fullEvents) {
            money("5.4321", event.recoveredAmount()); assertEquals(raw, event.line().rawResponseJson());
            assertEquals(TIME.plusSeconds(1), event.occurredAt()); assertEquals(full.rawId(), event.rawId().longValue());
        }
        OrderEvent partialEvent = capture.getAllValues().stream().filter(e -> partial.eventKey().equals(e.eventKey())).findFirst().orElseThrow();
        assertNull(partialEvent.recoveredAmount()); assertEquals(raw, partialEvent.line().rawResponseJson());
        assertEquals("DISPATCHED", jdbc.queryForObject("SELECT status FROM cps_order_event_outbox WHERE event_key=?", String.class, full.eventKey()));
    }
    @Test public void seller14And15ArchiveDedicatedExceptionsWithoutOrderOrEventChanges() throws Exception {
        String order = id();
        var original = ingestion.ingest(input(Platform.TBK, order, "original", TIME.minusSeconds(100), "5", "{}").resolved(OrderStatus.SETTLED, false, "TEST"));
        var bean = new SqlSessionFactoryBean(); bean.setDataSource(jdbc.getDataSource());
        var factory = bean.getObject(); factory.getConfiguration().addMapper(OrderAuxiliaryEvidenceMapper.class);
        var evidence = new SqlSessionTemplate(factory).getMapper(OrderAuxiliaryEvidenceMapper.class);
        for (String code : List.of("14", "15")) {
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event", Long.class);
            String raw = "{\"code\":0,\"data\":{\"total_count\":1,\"results\":[{\"tb_trade_parent_id\":\"" + order
                + "\",\"tb_trade_id\":\"sub\",\"tk_pub_id\":1,\"refund_status\":\"" + code
                + "\",\"refund_fee\":100,\"tb_trade_finish_price\":100,\"modified_time\":\"2026-01-01 01:00:00\"}]}}";
            var client = new DtkOrderEvidenceClient("test", "secret", Duration.ofSeconds(1), (uri, timeout) -> raw, Clock.fixed(TIME, ZoneOffset.UTC));
            var feed = new DtkAdditionalOrderCollectionFeed(client, ingestion, mock(OrderCollectionSettings.class), evidence,
                ZoneOffset.UTC, Clock.fixed(TIME, ZoneOffset.UTC), null, 0);
            var page = feed.fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.REFUND,
                new TimeWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z")), null);
            assertFalse(page.completenessConfirmed()); assertTrue(page.entries().isEmpty());
            assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event", Long.class).longValue());
            assertEquals("SETTLED", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, original.orderLineId()));
            money("5", jdbc.queryForObject("SELECT actual_commission FROM sys_order_commission WHERE id=?", BigDecimal.class, original.orderLineId()));
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception e JOIN cps_order_raw r ON r.id=e.raw_id WHERE r.payload_text=? AND e.reason LIKE '%卖家侧补扣%'", Long.class, raw) > 0);
        }
    }
}