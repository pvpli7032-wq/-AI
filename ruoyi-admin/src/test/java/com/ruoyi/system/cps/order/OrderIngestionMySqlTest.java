package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.mapper.OrderEventMapper;
import com.ruoyi.system.cps.mapper.OrderIngestMapper;
import com.ruoyi.system.cps.mapper.OrderRawMapper;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;

/** Uses actual migrations, MyBatis and a dedicated MySQL 8 schema. */
public class OrderIngestionMySqlTest
{
    private static String adminUrl, user, password, database;
    private static JdbcTemplate jdbc;
    private static OrderIngestionService service;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeClass
    public static void setup() throws Exception
    {
        adminUrl = System.getenv("LINE4_MYSQL_URL");
        assertTrue("Real MySQL URL required", adminUrl != null && adminUrl.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line10_ingest_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement())
        {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = adminUrl.indexOf('?');
        String path = query < 0 ? adminUrl : adminUrl.substring(0, query);
        String options = query < 0 ? "" : adminUrl.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        DriverManagerDataSource source = new DriverManagerDataSource(
            (slash < 0 ? path : path.substring(0, slash)) + "/" + database + options, user, password) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (Statement statement = connection.createStatement()) { statement.execute("SET time_zone = '+08:00'"); }
                return connection;
            }
        };
        Flyway.configure().dataSource(source).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        SqlSessionFactory factory = bean.getObject();
        assertNotNull(factory);
        factory.getConfiguration().addMapper(OrderRawMapper.class);
        factory.getConfiguration().addMapper(OrderIngestMapper.class);
        factory.getConfiguration().addMapper(OrderEventMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        service = new OrderIngestionService(new DataSourceTransactionManager(source), session.getMapper(OrderRawMapper.class),
            session.getMapper(OrderIngestMapper.class), session.getMapper(OrderEventMapper.class),
            new PlatformOrderStatusMapper(), new OrderTransitionValidator());
    }

    @AfterClass
    public static void cleanup() throws Exception
    {
        if (database != null)
        {
            assertTrue(database.matches("line10_ingest_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = connection.createStatement())
            {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test
    public void deferredCaseRetainsExactProviderTextWithoutCreatingAnEvent()
    {
        String raw = "{ \"case\": \"原始维权\", \"value\": 12 }";
        long id = service.retainRaw(Platform.TBK, raw, TIME);
        assertEquals(raw, jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE raw_id=?", Long.class, id).longValue());
    }

    @Test
    public void duplicateAndSubordersPreserveRawAndOnlyOneEventPerKey()
    {
        String order = "line10-" + UUID.randomUUID();
        String raw = "{ \"source\": \"真实原文\", \"order\": 17 }";
        IncomingOrder first = incoming(raw, order, "a", "12", TIME);
        var applied = service.ingest(first);
        assertEquals(OrderIngestionService.Outcome.APPLIED, applied.outcome());
        var duplicate = service.ingest(first);
        assertEquals(OrderIngestionService.Outcome.DUPLICATE, duplicate.outcome());
        assertEquals(applied.eventKey(), duplicate.eventKey());
        assertNotEquals(applied.rawId(), duplicate.rawId());
        assertEquals(raw, jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, applied.rawId()));
        assertEquals(raw, jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, duplicate.rawId()));
        var other = service.ingest(incoming("{\"sub\":\"b\"}", order, "b", "12", TIME));
        assertEquals(OrderIngestionService.Outcome.APPLIED, other.outcome());
        assertNotEquals(applied.orderLineId(), other.orderLineId());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM sys_order_commission WHERE platform_type='tbk' AND order_id=?", Long.class, order).longValue());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE event_key=?", Long.class, applied.eventKey()).longValue());
        assertNull(jdbc.queryForObject("SELECT agent_user_id FROM sys_order_commission WHERE id=?", Long.class, applied.orderLineId()));
    }

    @Test
    public void sameEventKeyRequiresMatchingBusinessDataButNotIdenticalPageJson()
    {
        String order = "line10-conflict-" + UUID.randomUUID();
        Instant paidAt = TIME.plusNanos(123456789);
        UnionOrderLine original = new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a",
            new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt, null, null);
        var first = service.ingest(new IncomingOrder("{\"page\":1}", TIME, TIME, original));
        assertEquals(OrderIngestionService.Outcome.APPLIED, first.outcome());
        UnionOrderLine equivalent = new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a",
            new BigDecimal("12.500"), new BigDecimal("1.23450"), null, "12", paidAt, null, null);
        assertEquals(OrderIngestionService.Outcome.DUPLICATE,
            service.ingest(new IncomingOrder("{\"page\":2}", TIME.plusSeconds(1), TIME, equivalent)).outcome());
        List<UnionOrderLine> conflicts = List.of(
            new UnionOrderLine(Platform.TBK, order, "a", "item-b", "pid-a", "token-a", new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt, null, null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-b", "token-a", new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt, null, null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-b", new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt, null, null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a", new BigDecimal("12.51"), new BigDecimal("1.2345"), null, "12", paidAt, null, null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a", new BigDecimal("12.50"), new BigDecimal("1.2346"), null, "12", paidAt, null, null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a", new BigDecimal("12.50"), new BigDecimal("1.2345"), new BigDecimal("0.0001"), "12", paidAt, null, null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a", new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt.plusNanos(1), null, null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a", new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt, TIME.plusSeconds(1), null),
            new UnionOrderLine(Platform.TBK, order, "a", "item-a", "pid-a", "token-a", new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt, null, TIME.plusSeconds(1)));
        for (UnionOrderLine conflict : conflicts)
        {
            var rejected = service.ingest(new IncomingOrder("{\"page\":3}", TIME.plusSeconds(2), TIME, conflict));
            assertEquals(OrderIngestionService.Outcome.REJECTED, rejected.outcome());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, rejected.rawId()).longValue());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE event_key=?", Long.class, first.eventKey()).longValue());
        assertEquals("item-a", jdbc.queryForObject("SELECT item_id FROM sys_order_commission WHERE id=?", String.class, first.orderLineId()));
        assertEquals("pid-a", jdbc.queryForObject("SELECT pid FROM sys_order_commission WHERE id=?", String.class, first.orderLineId()));
        assertEquals(0, new BigDecimal("12.50").compareTo(jdbc.queryForObject("SELECT order_amount FROM sys_order_commission WHERE id=?", BigDecimal.class, first.orderLineId())));
    }

    @Test
    public void illegalAndOutOfOrderEventsAreAuditedWithoutRollingBackEvidence()
    {
        String order = "line10-" + UUID.randomUUID();
        var initial = service.ingest(incoming("{\"kind\":\"paid\"}", order, "1", "12", TIME));
        assertEquals(OrderIngestionService.Outcome.APPLIED, initial.outcome());
        // PAID -> SETTLED is now legal. Keep this negative case on the forbidden PAID -> DISPUTED edge.
        var illegal = service.ingest(incoming("{\"kind\":\"disputed\",\"refund_status\":4}", order, "1", "4", TIME.plusSeconds(10))
            .resolved(OrderStatus.DISPUTED, false, "TBK_REFUND"));
        assertEquals(OrderIngestionService.Outcome.REJECTED, illegal.outcome());
        assertEquals("{\"kind\":\"disputed\",\"refund_status\":4}", jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, illegal.rawId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, illegal.rawId()).longValue());
        assertEquals("PAID", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, initial.orderLineId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, initial.orderLineId()).longValue());
        var received = service.ingest(incoming("{\"kind\":\"received\"}", order, "1", "14", TIME.plusSeconds(20)));
        assertEquals(OrderIngestionService.Outcome.APPLIED, received.outcome());
        var outOfOrder = service.ingest(incoming("{\"kind\":\"late-old\"}", order, "1", "12", TIME.plusSeconds(5)));
        assertEquals(OrderIngestionService.Outcome.REJECTED, outOfOrder.outcome());
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?", String.class, initial.orderLineId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, outOfOrder.rawId()).longValue());
        var unknown = service.ingest(incoming("{\"kind\":\"unknown\"}", order, "1", "999", TIME.plusSeconds(30)));
        assertEquals(OrderIngestionService.Outcome.REJECTED, unknown.outcome());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, unknown.rawId()).longValue());
        var malformed = service.ingest(incoming("not json", order, "1", "12", TIME));
        assertEquals(OrderIngestionService.Outcome.REJECTED, malformed.outcome());
        assertEquals("not json", jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, malformed.rawId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, malformed.rawId()).longValue());
    }

    @Test
    public void outboxReleaseAndAcknowledgementRetainRetryability()
    {
        String order = "line10-" + UUID.randomUUID();
        var result = service.ingest(incoming("{\"test\":\"outbox\"}", order, "a", "12", TIME));
        assertEquals(OrderIngestionService.Outcome.APPLIED, result.outcome());
        // Other tests may leave pending events, so claim until this event is reached.
        OrderIngestionService.Claim target = null;
        for (var claim : service.claimPending(100, Duration.ofSeconds(30)))
        {
            if (result.eventKey().equals(claim.event().get("event_key"))) target = claim;
            else assertTrue(service.release(claim.id(), claim.token()));
        }
        assertNotNull(target);
        assertFalse(service.acknowledge(target.id(), "wrong-token"));
        assertTrue(service.release(target.id(), target.token()));
        OrderIngestionService.Claim retried = null;
        for (var claim : service.claimPending(100, Duration.ofSeconds(30)))
        {
            if (result.eventKey().equals(claim.event().get("event_key"))) retried = claim;
            else assertTrue(service.release(claim.id(), claim.token()));
        }
        assertNotNull(retried);
        assertNotEquals(target.token(), retried.token());
        assertFalse(service.acknowledge(retried.id(), retried.token()));
        assertFalse(service.markSettlementDone(retried.id(), retried.token()));
        assertTrue(service.markAttributionDone(retried.id(), retried.token()));
        assertTrue(service.release(retried.id(), retried.token()));
        OrderIngestionService.Claim third = null;
        for (var claim : service.claimPending(100, Duration.ofSeconds(30)))
        {
            if (result.eventKey().equals(claim.event().get("event_key"))) third = claim;
            else assertTrue(service.release(claim.id(), claim.token()));
        }
        assertNotNull(third);
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT attribution_done FROM cps_order_event_outbox WHERE id=?", Integer.class, third.id()));
        assertTrue(service.markSettlementDone(third.id(), third.token()));
        assertTrue(service.acknowledge(third.id(), third.token()));
        assertFalse(service.acknowledge(third.id(), third.token()));
        assertEquals("DISPATCHED", jdbc.queryForObject("SELECT status FROM cps_order_event_outbox WHERE id=?", String.class, third.id()));
    }

    @Test
    public void settlementUpdatesKnownFieldsAndFlagsFastOrUnknownWithoutRoundingTime()
    {
        Instant paid = Instant.parse("2026-01-01T00:00:00.123456789Z");
        Instant receivedAt = paid.plusSeconds(60);
        for (long seconds : new long[] { 86399, 86400, -1 })
        {
            String order = "line10-timing-" + UUID.randomUUID();
            Instant settled = seconds < 0 ? null : paid.plusSeconds(seconds);
            UnionOrderLine paidLine = new UnionOrderLine(Platform.TBK, order, "1", "old-item", null, null,
                new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paid, null, null);
            var initial = service.ingest(new IncomingOrder("{\"stage\":1}", paid, paid, paidLine));
            assertEquals(OrderIngestionService.Outcome.APPLIED, initial.outcome());
            UnionOrderLine received = new UnionOrderLine(Platform.TBK, order, "1", "new-item", "new-pid", null,
                new BigDecimal("12.50"), null, null, "14", null, receivedAt, null);
            assertEquals(OrderIngestionService.Outcome.APPLIED,
                service.ingest(new IncomingOrder("{\"stage\":2}", receivedAt, receivedAt, received)).outcome());
            UnionOrderLine finalLine = new UnionOrderLine(Platform.TBK, order, "1", null, null, null,
                new BigDecimal("12.50"), null, new BigDecimal("1.1111"), "3", paid, receivedAt, settled);
            var finished = service.ingest(new IncomingOrder("{\"stage\":3}", paid.plusSeconds(90000), paid.plusSeconds(90000), finalLine));
            assertEquals(OrderIngestionService.Outcome.APPLIED, finished.outcome());
            assertEquals("new-item", jdbc.queryForObject("SELECT item_id FROM sys_order_commission WHERE id=?", String.class, initial.orderLineId()));
            assertEquals("new-pid", jdbc.queryForObject("SELECT pid FROM sys_order_commission WHERE id=?", String.class, initial.orderLineId()));
            assertEquals(0, new BigDecimal("1.2345").compareTo(jdbc.queryForObject("SELECT estimated_commission FROM sys_order_commission WHERE id=?", BigDecimal.class, initial.orderLineId())));
            assertEquals(0, new BigDecimal("1.1111").compareTo(jdbc.queryForObject("SELECT actual_commission FROM sys_order_commission WHERE id=?", BigDecimal.class, initial.orderLineId())));
            assertNotNull(jdbc.queryForObject("SELECT received_time FROM sys_order_commission WHERE id=?", java.sql.Timestamp.class, initial.orderLineId()));
            if (settled != null) assertNotNull(jdbc.queryForObject("SELECT settle_time FROM sys_order_commission WHERE id=?", java.sql.Timestamp.class, initial.orderLineId()));
            Integer fast = jdbc.queryForObject("SELECT cps_fast_settlement FROM sys_order_commission WHERE id=?", Integer.class, initial.orderLineId());
            if (seconds < 0) assertNull(fast); else assertEquals(Integer.valueOf(seconds < 86400 ? 1 : 0), fast);
            assertEquals(paid.toString(), jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(line_snapshot,'$.paidAt')) FROM cps_order_event_outbox WHERE event_key=?", String.class, finished.eventKey()));
            if (settled != null) assertEquals(settled.toString(), jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(line_snapshot,'$.settledAt')) FROM cps_order_event_outbox WHERE event_key=?", String.class, finished.eventKey()));
            assertEquals(Integer.valueOf(paid.getNano()), jdbc.queryForObject("SELECT cps_paid_nano FROM sys_order_commission WHERE id=?", Integer.class, initial.orderLineId()));
        }
    }

    @Test
    public void sameStatusWithNewTimestampIsAuditedForReview()
    {
        String order = "line10-same-" + UUID.randomUUID();
        var first = service.ingest(incoming("{\"n\":1}", order, "a", "12", TIME));
        var second = service.ingest(incoming("{\"n\":2}", order, "a", "12", TIME.plusSeconds(1)));
        assertEquals(OrderIngestionService.Outcome.APPLIED, first.outcome());
        assertEquals(OrderIngestionService.Outcome.REJECTED, second.outcome());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_ingest_exception WHERE raw_id=?", Long.class, second.rawId()).longValue());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_event WHERE order_line_id=?", Long.class, first.orderLineId()).longValue());
    }

    private static IncomingOrder incoming(String raw, String order, String sub, String status, Instant time)
    {
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, order, sub, "test-item", null, null,
            new BigDecimal("12.50"), new BigDecimal("1.2345"), null, status, TIME, null, null);
        return new IncomingOrder(raw, TIME.plusSeconds(60), time, line);
    }
}
