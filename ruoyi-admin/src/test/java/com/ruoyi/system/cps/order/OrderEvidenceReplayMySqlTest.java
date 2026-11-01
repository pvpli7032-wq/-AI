package com.ruoyi.system.cps.order;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.attribution.AttributionDecision;
import com.ruoyi.system.cps.attribution.AttributionMethod;
import com.ruoyi.system.cps.attribution.AttributionConfidence;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.mapper.OrderCollectionPlanMapper;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Additional acceptance against MySQL 8; shares only the original fixture, never modifies its assertions. */
public class OrderEvidenceReplayMySqlTest {
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
    private IncomingOrder input(Instant statusTime) {
        String raw = "{ \"exact\": \"原文\", \"spacing\":  1 }";
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, "replay-" + UUID.randomUUID(), "sub", "item", "pid", null,
            new BigDecimal("12.34"), new BigDecimal("1.2345"), null, "12", TIME, null, null, raw, statusTime);
        return IncomingOrder.from(line, TIME.plusSeconds(100));
    }
    @Test public void immutableReplayIsIdempotentAndDownstreamReceivesContractEvidence() {
        IncomingOrder input = input(TIME);
        var first = ingestion.ingest(input);
        assertEquals(OrderIngestionService.Outcome.APPLIED, first.outcome());
        var replayed = ingestion.replay(first.rawId());
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, replayed.outcome());
        assertEquals(first.eventKey(), replayed.eventKey());
        assertEquals(input.rawJson(), jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, replayed.rawId()));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE event_key=?", Long.class, first.eventKey()));
        AttributionService attribution = mock(AttributionService.class);
        when(attribution.decide(anyLong(), any())).thenReturn(new AttributionDecision(123L,
            AttributionMethod.ATTRIBUTION_TOKEN, AttributionConfidence.HIGH, null));
        SettlementService settlement = mock(SettlementService.class);
        OrderEventDispatcher dispatcher = new OrderEventDispatcher(ingestion, attribution, settlement);
        while (dispatcher.dispatchNext(Duration.ofSeconds(60))) { }
        org.mockito.ArgumentCaptor<OrderEvent> events = org.mockito.ArgumentCaptor.forClass(OrderEvent.class);
        verify(settlement, atLeastOnce()).onOrderEvent(events.capture());
        OrderEvent event = events.getAllValues().stream().filter(e -> first.eventKey().equals(e.eventKey())).findFirst().orElseThrow();
        assertEquals(input.rawJson(), event.line().rawResponseJson());
        assertEquals(TIME, event.line().allianceStatusTime());
        assertEquals(TIME, event.occurredAt());
    }
    @Test public void missingTimeStaysNullInReplayAndNeverCreatesAnEvent() {
        var result = ingestion.ingest(input(null));
        assertEquals(OrderIngestionService.Outcome.REJECTED, result.outcome());
        String snapshot = jdbc.queryForObject("SELECT input_json FROM cps_order_replay_input WHERE raw_id=?", String.class, result.rawId());
        assertNull(JSON.parseObject(snapshot).getInstant("allianceStatusTime"));
        var replay = ingestion.replay(result.rawId());
        assertEquals(OrderIngestionService.Outcome.REJECTED, replay.outcome());
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE raw_id IN (?,?)", Long.class, result.rawId(), replay.rawId()));
    }
    @Test public void backfillHasItsOwnStableDailyCycleAndDoesNotAdvanceIncrementalCheckpoint() throws Exception {
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(jdbc.getDataSource());
        var sessions = factory.getObject(); sessions.getConfiguration().addMapper(OrderCollectionPlanMapper.class);
        var mapper = new SqlSessionTemplate(sessions).getMapper(OrderCollectionPlanMapper.class);
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey(ConfiguredOrderCollectionSettings.POLICY_KEY)).thenReturn(
            "{\"enabled\":true,\"feeds\":[{\"platform\":\"PDD\",\"taskType\":\"BACKFILL\",\"windowSeconds\":600,"
            + "\"overlapSeconds\":60,\"maxWindows\":1,\"lagSeconds\":0,\"lookbackSeconds\":86400}]}");
        var early = new ConfiguredOrderCollectionSettings(configs, mapper, Clock.fixed(TIME, ZoneOffset.UTC));
        var first = early.pending(OrderCollectionRetryQueue.TaskType.BACKFILL).get(0);
        var later = new ConfiguredOrderCollectionSettings(configs, mapper, Clock.fixed(TIME.plusSeconds(14400), ZoneOffset.UTC));
        assertEquals(first.window(), later.pending(OrderCollectionRetryQueue.TaskType.BACKFILL).get(0).window());
        early.completed(first);
        assertEquals(first.window().to().minusSeconds(60), later.pending(OrderCollectionRetryQueue.TaskType.BACKFILL).get(0).window().from());
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_collection_checkpoint WHERE platform='PDD' AND task_type='INCREMENTAL'", Long.class));
        when(configs.selectConfigByKey(ConfiguredOrderCollectionSettings.POLICY_KEY)).thenReturn("{\"enabled\":false}");
        assertThrows(IllegalStateException.class, () -> later.pending(OrderCollectionRetryQueue.TaskType.BACKFILL));
    }

    @Test public void overlappingWindowsResumeFromDurableCheckpointAndUseExplicitOwnership() throws Exception {
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(jdbc.getDataSource());
        var sessions = factory.getObject(); sessions.getConfiguration().addMapper(OrderCollectionPlanMapper.class);
        var mapper = new SqlSessionTemplate(sessions).getMapper(OrderCollectionPlanMapper.class);
        ISysConfigService configs = mock(ISysConfigService.class);
        String policy = "{\"enabled\":true,\"feeds\":[{\"platform\":\"TBK\",\"taskType\":\"INCREMENTAL\","
            + "\"windowSeconds\":600,\"overlapSeconds\":60,\"maxWindows\":2,\"lagSeconds\":0,\"initialFrom\":\"2026-01-01T00:00:00Z\"}],"
            + "\"ownership\":{\"TBK\":{\"pids\":[\"pid\"],\"siteIds\":[\"site\"]}}}";
        when(configs.selectConfigByKey(ConfiguredOrderCollectionSettings.POLICY_KEY)).thenReturn(policy);
        var settings = new ConfiguredOrderCollectionSettings(configs, mapper, Clock.fixed(TIME, ZoneOffset.UTC));
        List<OrderCollectionSettings.Work> work = settings.pending(OrderCollectionRetryQueue.TaskType.INCREMENTAL);
        assertEquals(2, work.size());
        assertTrue(work.get(1).window().from().isBefore(work.get(0).window().to()));
        assertThrows(IllegalStateException.class, () -> settings.completed(work.get(1))); // cannot skip failed first window
        settings.completed(work.get(0)); settings.completed(work.get(0)); // same completion is idempotent
        var restarted = new ConfiguredOrderCollectionSettings(configs, mapper, Clock.fixed(TIME, ZoneOffset.UTC));
        var resumed = restarted.pending(OrderCollectionRetryQueue.TaskType.INCREMENTAL);
        assertEquals(work.get(1).window(), resumed.get(0).window());
        IncomingOrder incoming = input(TIME);
        assertTrue(settings.belongsToSystem(work.get(0), new OrderCollectionFeed.Entry(incoming, "site", false, null)));
        assertFalse(settings.belongsToSystem(work.get(0), new OrderCollectionFeed.Entry(incoming, "other-site", false, null)));
        assertFalse(settings.belongsToSystem(work.get(0), new OrderCollectionFeed.Entry(incoming, null, false, null)));
        settings.auditRejected(work.get(0), new OrderCollectionFeed.Entry(incoming, "other-site", false, null));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_collection_rejection WHERE run_id=?", Long.class, work.get(0).runId().toString()));
    }
}
