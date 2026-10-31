package com.ruoyi.system.cps.order;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.mapper.OrderAuxiliaryEvidenceMapper;
import com.ruoyi.system.cps.mapper.OrderCollectionPlanMapper;
import com.ruoyi.system.cps.mapper.OrderRawMapper;
import com.ruoyi.system.cps.mapper.OrderIngestMapper;
import com.ruoyi.system.cps.mapper.OrderEventMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real MySQL 8 migration/state/outbox tests; original acceptance assertions remain untouched. */
public class OrderSupplementMySqlTest {
    private static JdbcTemplate jdbc;
    private static OrderIngestionService service;
    private static SqlSessionTemplate session;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    @BeforeClass public static void setup() throws Exception {
        OrderIngestionMySqlTest.setup();
        service = (OrderIngestionService) fixture("service"); jdbc = (JdbcTemplate) fixture("jdbc");
        var bean = new SqlSessionFactoryBean(); bean.setDataSource(jdbc.getDataSource()); var factory = bean.getObject();
        for (Class<?> mapper : new Class<?>[]{OrderAuxiliaryEvidenceMapper.class, OrderCollectionPlanMapper.class,
                OrderRawMapper.class, OrderIngestMapper.class, OrderEventMapper.class}) factory.getConfiguration().addMapper(mapper);
        session = new SqlSessionTemplate(factory);
    }
    private static Object fixture(String name) throws Exception {
        Field field = OrderIngestionMySqlTest.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    @AfterClass public static void cleanup() throws Exception { OrderIngestionMySqlTest.cleanup(); }
    private static IncomingOrder input(Platform platform, String order, String code, Instant time, String fee) {
        String raw = "{ \"order\": \"" + order + "\", \"code\": \"" + code + "\", \"fee\": \"" + fee + "\" }";
        return IncomingOrder.from(new UnionOrderLine(platform, order, "sub", "item", "mm_1_2_3", null,
            new BigDecimal("100"), new BigDecimal("3"), fee == null ? null : new BigDecimal(fee), code,
            TIME.minusSeconds(3600), null, null, raw, time), TIME.plusSeconds(86400));
    }
    @Test public void jdActualFeeOnlyChangeEmitsDurableEventAndReplayIsIdempotent() {
        String order = "fee-" + UUID.randomUUID();
        var first = service.ingest(input(Platform.JD, order, "16", TIME, "1").resolved(OrderStatus.PAID, false, "JD_V2"));
        var changed = service.ingest(input(Platform.JD, order, "16", TIME.plusSeconds(1), "2").resolved(OrderStatus.PAID, false, "JD_V2"));
        assertEquals(OrderIngestionService.Outcome.APPLIED, first.outcome());
        assertEquals(OrderIngestionService.Outcome.APPLIED, changed.outcome());
        assertEquals("ALLIANCE_COMMISSION", jdbc.queryForObject("SELECT source FROM cps_order_event WHERE event_key=?", String.class, changed.eventKey()));
        assertEquals(0, new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT actual_commission FROM sys_order_commission WHERE id=?", BigDecimal.class, changed.orderLineId())));
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, service.replay(changed.rawId()).outcome());
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, changed.orderLineId()));
        assertEquals("PAID", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, changed.orderLineId()));
    }
    @Test public void directRefundCannotCreateSyntheticDisputeAndRestoreUsesThePersistedPreviousState() {
        String order = "case-" + UUID.randomUUID();
        var paid = service.ingest(input(Platform.TBK, order, "12", TIME, "3"));
        var illegal = service.ingest(input(Platform.TBK, order, "2", TIME.plusSeconds(1), "3").resolved(OrderStatus.FULL_REFUND, false, "TBK_REFUND"));
        assertEquals(OrderIngestionService.Outcome.REJECTED, illegal.outcome());
        assertEquals("PAID", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, paid.orderLineId()));
        assertEquals(OrderIngestionService.Outcome.APPLIED, service.ingest(input(Platform.TBK, order, "14", TIME.plusSeconds(2), "3")).outcome());
        assertEquals(OrderIngestionService.Outcome.APPLIED, service.ingest(input(Platform.TBK, order, "4", TIME.plusSeconds(3), "3").resolved(OrderStatus.DISPUTED, false, "TBK_REFUND")).outcome());
        var restored = service.ingest(input(Platform.TBK, order, "3", TIME.plusSeconds(4), "3").resolved(null, true, "TBK_REFUND"));
        assertEquals(OrderIngestionService.Outcome.APPLIED, restored.outcome());
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, paid.orderLineId()));
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, service.replay(restored.rawId()).outcome());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, illegal.rawId()) > 0);
    }
    @Test public void jdCommissionEventDuringDisputeKeepsTheOriginalRestorationState() {
        String order = "disputed-fee-" + UUID.randomUUID();
        service.ingest(input(Platform.JD, order, "16", TIME, "3").resolved(OrderStatus.PAID, false, "JD_V2"));
        service.ingest(input(Platform.JD, order, "17", TIME.plusSeconds(1), "3").resolved(OrderStatus.RECEIVED, false, "JD_V2"));
        service.ingest(input(Platform.JD, order, "17", TIME.plusSeconds(2), "3").resolved(OrderStatus.DISPUTED, false, "JD_V2"));
        var fee = service.ingest(input(Platform.JD, order, "17", TIME.plusSeconds(3), "2").resolved(OrderStatus.DISPUTED, false, "JD_V2"));
        assertEquals(OrderIngestionService.Outcome.APPLIED, fee.outcome());
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT cps_dispute_previous FROM sys_order_commission WHERE id=?", String.class, fee.orderLineId()));
        var restored = service.ingest(input(Platform.JD, order, "17", TIME.plusSeconds(4), "2").resolved(null, true, "JD_V2"));
        assertEquals(OrderIngestionService.Outcome.APPLIED, restored.outcome());
    }
    @Test public void switchDoesNotBypassLegalPathsAndMissingTimesStayNull() {
        OrderIngestionService enabled = new OrderIngestionService(new DataSourceTransactionManager(jdbc.getDataSource()),
            session.getMapper(OrderRawMapper.class), session.getMapper(OrderIngestMapper.class), session.getMapper(OrderEventMapper.class),
            new PlatformOrderStatusMapper(() -> true), new OrderTransitionValidator());
        String order = "switch-" + UUID.randomUUID();
        var paid = enabled.ingest(input(Platform.TBK, order, "12", TIME, "3"));
        var blocked = enabled.ingest(input(Platform.TBK, order, "14", TIME.plusSeconds(1), "3"));
        assertEquals(OrderIngestionService.Outcome.REJECTED, blocked.outcome());
        assertEquals("PAID", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, paid.orderLineId()));
        service.ingest(input(Platform.TBK, order, "14", TIME.plusSeconds(2), "3"));
        var legal = enabled.ingest(input(Platform.TBK, order, "14", TIME.plusSeconds(3), "3"));
        assertEquals(OrderIngestionService.Outcome.APPLIED, legal.outcome());
        assertEquals("SETTLED", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, paid.orderLineId()));
        var missing = enabled.ingest(input(Platform.TBK, "missing-" + UUID.randomUUID(), "14", null, "3"));
        assertEquals(OrderIngestionService.Outcome.REJECTED, missing.outcome());
        String json = jdbc.queryForObject("SELECT input_json FROM cps_order_replay_input WHERE raw_id=?", String.class, missing.rawId());
        assertNull(com.alibaba.fastjson2.JSON.parseObject(json).getInstant("allianceStatusTime"));
        assertEquals("false", jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?", String.class, PlatformOrderStatusMapper.TBK_RECEIVED_AS_SETTLED));
    }
    @Test public void automatedPenaltyImportsAreIdempotentAndDoNotTouchOrdersOrEvents() {
        long orders = jdbc.queryForObject("SELECT COUNT(*) FROM sys_order_commission", Long.class);
        long events = jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event", Long.class);
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey(ConfiguredOrderCollectionSettings.POLICY_KEY)).thenReturn(
            "{\"ownership\":{\"TBK\":{\"pids\":[\"mm_1_2_3\"],\"siteIds\":[\"2\"]}}}");
        var settings = new ConfiguredOrderCollectionSettings(configs, session.getMapper(OrderCollectionPlanMapper.class), Clock.fixed(TIME, ZoneOffset.UTC));
        String row = "{\"tb_trade_id\":\"penalty-test\",\"tk_pub_id\":1,\"tk_site_id\":2,\"tk_adzone_id\":3,\"punish_status\":0,\"violation_type\":\"违规订单\"}";
        String raw = "{\"code\":0,\"data\":{\"total_count\":1,\"results\":[" + row + "]}}";
        var client = new DtkOrderEvidenceClient("test", "secret", Duration.ofSeconds(1), (uri, timeout) -> raw, Clock.fixed(TIME, ZoneOffset.UTC));
        var feed = new DtkAdditionalOrderCollectionFeed(client, service, settings, session.getMapper(OrderAuxiliaryEvidenceMapper.class),
            ZoneOffset.UTC, Clock.fixed(TIME, ZoneOffset.UTC), null, 0);
        TimeWindow window = new TimeWindow(TIME.minusSeconds(86400), TIME);
        assertTrue(feed.fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.PENALTY, window, null).completenessConfirmed());
        assertTrue(feed.fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.PENALTY, window, null).completenessConfirmed());
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_punish_import WHERE file='DTK_SYNC'", Long.class));
        assertEquals("违规订单", jdbc.queryForObject("SELECT violation_type FROM cps_punish_import WHERE file='DTK_SYNC'", String.class));
        assertEquals(orders, jdbc.queryForObject("SELECT COUNT(*) FROM sys_order_commission", Long.class).longValue());
        assertEquals(events, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event", Long.class).longValue());
    }
    @Test public void penaltyHourlyCyclesRescanOldOrderCreationWindows() {
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey(ConfiguredOrderCollectionSettings.POLICY_KEY)).thenReturn(
            "{\"enabled\":true,\"feeds\":[{\"platform\":\"TBK\",\"taskType\":\"PENALTY\",\"windowSeconds\":86400,"
            + "\"overlapSeconds\":60,\"maxWindows\":1,\"lagSeconds\":0,\"lookbackSeconds\":2592000}]}");
        var plans = session.getMapper(OrderCollectionPlanMapper.class);
        var first = new ConfiguredOrderCollectionSettings(configs, plans, Clock.fixed(TIME, ZoneOffset.UTC));
        var work = first.pending(OrderCollectionRetryQueue.TaskType.PENALTY).get(0); first.completed(work);
        var later = new ConfiguredOrderCollectionSettings(configs, plans, Clock.fixed(TIME.plusSeconds(3600), ZoneOffset.UTC));
        var next = later.pending(OrderCollectionRetryQueue.TaskType.PENALTY).get(0);
        assertEquals(work.window().from().plusSeconds(3600), next.window().from());
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_collection_checkpoint WHERE task_type='PENALTY'", Long.class));
    }
    @Test public void quartzSchedulesAreSeededSeparatelyAndPausedUntilExplicitEnablement() {
        assertEquals(Long.valueOf(3), jdbc.queryForObject("SELECT COUNT(*) FROM sys_job WHERE invoke_target LIKE 'cpsOrderSyncTask.%' AND status='1' AND concurrent='1'", Long.class));
        assertEquals("0 0 * * * ?", jdbc.queryForObject("SELECT cron_expression FROM sys_job WHERE invoke_target='cpsOrderSyncTask.afterSale()'", String.class));
    }
}
