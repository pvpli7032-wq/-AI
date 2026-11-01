package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.mapper.InviteBindingMapper;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.union.adapter.Platform;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** One small batch exercises production retry transactions against real MySQL 8. */
public class AttributionRetryQueueMySqlTest {
    private static final long USER_ID = 89101L;
    private static final String PID = "TEST_RETRY_TBK_DEFAULT";
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static OrderAttributionService service;
    private static final List<OrderEvent> deliveries = new ArrayList<>();
    private static final Set<Long> failingOrders = new HashSet<>();

    @BeforeClass public static void setup() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with real MySQL 8 required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line9_retry_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        String databaseUrl = (slash < 0 ? path : path.substring(0, slash)) + "/" + database + options;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(databaseUrl, user, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        AttributionSettings settings = new AttributionSettings(jdbc);
        PidPoolService pids = new PidPoolService(jdbc, settings, manager);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/cps/InviteBindingMapper.xml"));
        InviteBindingService invites = new InviteBindingService(
                new SqlSessionTemplate(factory.getObject()).getMapper(InviteBindingMapper.class), null);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("settlement", (SettlementService) event -> {
            deliveries.add(event);
            if (failingOrders.contains(event.orderLineId())) throw new IllegalStateException("synthetic retry failure");
        });
        service = new OrderAttributionService(jdbc, pids, new AttributionTokenService(jdbc), settings,
                invites, beans.getBeanProvider(SettlementService.class), manager);
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,?,'TEST RETRY USER','0','0')",
                USER_ID, "TEST_RETRY_USER");
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)", USER_ID, "TEST_RETRY_OPENID");
        pids.register(Platform.TBK, PID, PidType.DEFAULT, true, 1L, "synthetic retry fixture");
    }

    @AfterClass public static void cleanup() throws Exception {
        if (database == null) return;
        assertTrue(database.matches("line9_retry_[a-f0-9]{32}"));
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test public void failedHeadYieldsToLaterTaskAndRetriesItsOriginalEventWithoutRedeliveringSuccess() {
        long blocked = enqueue("TEST_RETRY_OLDER");
        long healthy = enqueue("TEST_RETRY_LATER");
        String originalKey = jdbc.queryForObject(
                "SELECT event_key FROM cps_attribution_settlement_outbox WHERE order_line_id=?", String.class, blocked);
        Instant originalTime = jdbc.queryForObject(
                "SELECT created_at FROM cps_attribution_settlement_outbox WHERE order_line_id=?", Timestamp.class, blocked).toInstant();
        failingOrders.add(blocked);

        assertEquals(0, service.retrySettlement(1));
        assertPending(blocked, 1);
        assertPending(healthy, 0);
        assertEquals(1, deliveries.size());
        assertEquals(Long.valueOf(blocked), deliveries.get(0).orderLineId());

        assertEquals("The older failure must not consume the next one-item batch", 1, service.retrySettlement(1));
        assertEquals("SENT", status(healthy));
        assertPending(blocked, 1);
        assertEquals(2, deliveries.size());
        assertEquals(Long.valueOf(healthy), deliveries.get(1).orderLineId());

        assertEquals(0, service.retrySettlement(1));
        assertPending(blocked, 2);
        assertEquals(3, deliveries.size());
        assertEquals(Long.valueOf(blocked), deliveries.get(2).orderLineId());

        failingOrders.remove(blocked);
        assertEquals(1, service.retrySettlement(1));
        assertEquals("SENT", status(blocked));
        assertEquals(Integer.valueOf(3), attempts(blocked));
        assertEquals(0, service.retrySettlement(1));
        assertEquals(4, deliveries.size());
        assertEquals(1, deliveries.stream().filter(event -> event.orderLineId().equals(healthy)).count());
        List<OrderEvent> retried = deliveries.stream().filter(event -> event.orderLineId().equals(blocked)).toList();
        assertEquals(3, retried.size());
        for (OrderEvent event : retried) {
            assertEquals(originalKey, event.eventKey());
            assertEquals(originalTime, event.occurredAt());
            assertEquals("ATTRIBUTION", event.source());
        }
        assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution_settlement_outbox", Integer.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow", Integer.class));
    }

    private static long enqueue(String orderNo) {
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,item_id,pid,paid_time,line_status,agent_user_id,order_amount,estimated_commission,actual_commission,raw_status) VALUES (?,'tbk','','TEST_RETRY_ITEM',?,?,'PAID',0,100,10,10,'test-status')",
                orderNo, PID, Timestamp.from(AttributionData.now(jdbc).minusSeconds(5)));
        long id = jdbc.queryForObject("SELECT id FROM sys_order_commission WHERE order_id=? AND platform_type='tbk' AND sub_order_no=''", Long.class, orderNo);
        assertEquals(Long.valueOf(USER_ID), service.manualAssign(id, USER_ID, 1L, "synthetic retry assignment").userId());
        return id;
    }

    private static void assertPending(long orderId, int expectedAttempts) {
        assertEquals("PENDING", status(orderId));
        assertEquals(Integer.valueOf(expectedAttempts), attempts(orderId));
    }

    private static String status(long orderId) {
        return jdbc.queryForObject("SELECT status FROM cps_attribution_settlement_outbox WHERE order_line_id=?", String.class, orderId);
    }

    private static Integer attempts(long orderId) {
        return jdbc.queryForObject("SELECT attempts FROM cps_attribution_settlement_outbox WHERE order_line_id=?", Integer.class, orderId);
    }
}
