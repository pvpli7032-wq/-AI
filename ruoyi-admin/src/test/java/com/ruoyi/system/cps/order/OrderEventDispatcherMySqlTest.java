package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
import com.ruoyi.system.cps.mapper.OrderEventMapper;
import com.ruoyi.system.cps.mapper.OrderIngestMapper;
import com.ruoyi.system.cps.mapper.OrderRawMapper;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.attribution.AttributionDecision;
import com.ruoyi.system.cps.attribution.AttributionMethod;
import com.ruoyi.system.cps.attribution.AttributionConfidence;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.OrderStatus;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.Assert.*;

/** Uses actual migrations, MyBatis and a dedicated MySQL 8 schema. */
public class OrderEventDispatcherMySqlTest
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
        database = "line10_dispatch_" + UUID.randomUUID().toString().replace("-", "");
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
            assertTrue(database.matches("line10_dispatch_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = connection.createStatement())
            {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test
    public void retriesStagesAcrossRestartsWithoutLosingOrderOrSnapshot() throws Exception
    {
        String order = "dispatch-" + UUID.randomUUID();
        String raw1 = "{ \"source\": \"original-paid\" }";
        String raw2 = "{ \"source\": \"original-received\" }";
        Instant paidAt = Instant.parse("2026-01-01T01:02:03.123456789Z");
        Instant receivedAt = Instant.parse("2026-01-02T04:05:06.987654321Z");
        Instant status1 = paidAt.plusNanos(23);
        Instant status2 = receivedAt.plusNanos(34);
        UnionOrderLine paid = new UnionOrderLine(Platform.TBK, order, "sub1", "item1", "pid1", "token1",
            new BigDecimal("12.50"), new BigDecimal("1.2345"), null, "12", paidAt, null, null);
        UnionOrderLine received = new UnionOrderLine(Platform.TBK, order, "sub1", "item1", "pid1", "token1",
            new BigDecimal("12.50"), new BigDecimal("1.2345"), new BigDecimal("1.1111"), "14", paidAt, receivedAt, null);
        var first = service.ingest(new IncomingOrder(raw1, TIME.plusSeconds(5), status1, paid));
        var second = service.ingest(new IncomingOrder(raw2, TIME.plusSeconds(10), status2, received));
        assertEquals(OrderIngestionService.Outcome.APPLIED, first.outcome());
        assertEquals(OrderIngestionService.Outcome.APPLIED, second.outcome());
        assertEquals(raw1, jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, first.rawId()));
        assertEquals(raw2, jdbc.queryForObject("SELECT payload_text FROM cps_order_raw WHERE id=?", String.class, second.rawId()));

        AttributionService attribution = mock(AttributionService.class);
        SettlementService settlement = mock(SettlementService.class);
        when(attribution.decide(anyLong(), any(UnionOrderLine.class)))
            .thenThrow(new IllegalStateException("synthetic attribution failure"))
            .thenReturn(new AttributionDecision(123L, AttributionMethod.ATTRIBUTION_TOKEN, AttributionConfidence.HIGH, null));
        doThrow(new IllegalStateException("synthetic settlement failure")).doNothing().when(settlement).onOrderEvent(any(OrderEvent.class));
        OrderEventDispatcher dispatcher = new OrderEventDispatcher(service, attribution, settlement);
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchNext(Duration.ofSeconds(30)));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM cps_order_event_outbox WHERE event_key=?", String.class, first.eventKey()));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT attribution_done FROM cps_order_event_outbox WHERE event_key=?", Integer.class, first.eventKey()));
        verifyNoInteractions(settlement);
        assertThrows(IllegalStateException.class, () -> new OrderEventDispatcher(service, attribution, settlement).dispatchNext(Duration.ofSeconds(30)));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT attribution_done FROM cps_order_event_outbox WHERE event_key=?", Integer.class, first.eventKey()));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT settlement_done FROM cps_order_event_outbox WHERE event_key=?", Integer.class, first.eventKey()));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM cps_order_event_outbox WHERE event_key=?", String.class, second.eventKey()));

        assertTrue(new OrderEventDispatcher(service, attribution, settlement).dispatchNext(Duration.ofSeconds(30)));
        assertEquals("DISPATCHED", jdbc.queryForObject("SELECT status FROM cps_order_event_outbox WHERE event_key=?", String.class, first.eventKey()));
        verify(attribution, times(2)).decide(eq(first.orderLineId()), any(UnionOrderLine.class));
        assertTrue(new OrderEventDispatcher(service, attribution, settlement).dispatchNext(Duration.ofSeconds(30)));
        assertFalse(new OrderEventDispatcher(service, attribution, settlement).dispatchNext(Duration.ofSeconds(30)));
        assertEquals("DISPATCHED", jdbc.queryForObject("SELECT status FROM cps_order_event_outbox WHERE event_key=?", String.class, second.eventKey()));
        verify(attribution, times(2)).decide(eq(first.orderLineId()), any(UnionOrderLine.class));
        ArgumentCaptor<OrderEvent> captured = ArgumentCaptor.forClass(OrderEvent.class);
        verify(settlement, times(3)).onOrderEvent(captured.capture());
        var events = captured.getAllValues();
        assertEquals(first.eventKey(), events.get(0).eventKey());
        assertEquals(first.eventKey(), events.get(1).eventKey());
        assertEquals(second.eventKey(), events.get(2).eventKey());
        assertEquals(status1, events.get(0).occurredAt());
        assertEquals(status2, events.get(2).occurredAt());
        assertEquals(OrderStatus.PAID, events.get(2).fromStatus());
        assertEquals(OrderStatus.RECEIVED, events.get(2).toStatus());
        assertEquals(first.rawId(), events.get(0).rawId().longValue());
        assertEquals(second.rawId(), events.get(2).rawId().longValue());
        assertEquals(Platform.TBK, events.get(0).line().platform());
        assertEquals(paidAt, events.get(2).line().paidAt());
        assertEquals(receivedAt, events.get(2).line().receivedAt());
        assertEquals(0, new BigDecimal("1.2345").compareTo(events.get(2).line().estimatedCommission()));
        assertEquals(0, new BigDecimal("1.1111").compareTo(events.get(2).line().actualCommission()));
        assertEquals("pid1", events.get(2).line().pid());
        assertEquals("token1", events.get(2).line().attributionToken());
    }
}
