package com.ruoyi.system.cps.risk;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.ISysConfigService;

/** Real MySQL, production migrations and transactional services; no in-memory SQL substitute. */
public class RiskHealthMySqlTest {
    private static String adminUrl, mysqlUser, mysqlPassword, database;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private Map<String, String> settings;
    private RiskService risk;
    private HealthMonitorService health;
    private MutableClock clock;

    @BeforeClass
    public static void setupDatabase() throws Exception {
        adminUrl = System.getenv("LINE4_MYSQL_URL");
        assertTrue("Real MySQL 8 is required", adminUrl != null && adminUrl.startsWith("jdbc:mysql://"));
        mysqlUser = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        mysqlPassword = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line12_risk_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
             Statement statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = adminUrl.indexOf('?');
        String path = query < 0 ? adminUrl : adminUrl.substring(0, query);
        String options = query < 0 ? "" : adminUrl.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        DriverManagerDataSource source = new DriverManagerDataSource(
            (slash < 0 ? path : path.substring(0, slash)) + "/" + database + options, mysqlUser, mysqlPassword);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
    }

    @AfterClass
    public static void cleanupDatabase() throws Exception {
        if (database == null) return;
        assertTrue(database.matches("line12_risk_[a-f0-9]{32}"));
        try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Before
    public void reset() {
        for (String table : List.of("cps_risk_signal", "cps_risk_review", "cps_risk_user_state", "cps_risk_device_event",
            "cps_health_observation", "cps_health_alert", "cps_health_alert_state", "sys_order_commission",
            "sys_agent_commission_account", "sys_union_platform")) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM sys_user WHERE user_id>=880001");
        settings = new HashMap<>();
        for (String key : List.of("cps.risk.device.newUsers", "cps.risk.device.windowMinutes", "cps.risk.invite.sameItemBuyers",
            "cps.risk.invite.lowPrice", "cps.risk.orders.windowDays", "cps.risk.refund.minimumOrders",
            "cps.health.channel.minimumBalance", "cps.health.refund.rateMultiplier", "cps.health.refund.minimumOrders")) settings.put(key, "0");
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey(anyString())).thenAnswer(call -> settings.get(call.getArgument(0)));
        risk = transactional(new RiskService(jdbc, configs));
        clock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        health = transactional(new HealthMonitorService(jdbc, configs, manager, clock));
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }

    private void user(long id) {
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag,create_time) VALUES (?,?,?,'0','0',NOW())",
            id, "fixture-" + id, "synthetic");
    }

    private void order(String key, long buyer, Long inviter, String item, String state) {
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,agent_user_id,buyer_user_id,"
            + "referrer_user_id,item_id,order_amount,line_status,paid_time,create_time) VALUES (?,'tbk','',?,?,?,?,1.00,?,NOW(),NOW())",
            key, buyer, buyer, inviter, item, state);
    }

    @Test
    public void bannedUsersCannotWithdrawAndManualReviewDoesNotChangeMoney() {
        user(880001);
        jdbc.update("INSERT INTO sys_agent_commission_account(user_id,balance) VALUES (880001,37.25)");
        assertFalse(risk.evaluateWithdrawal(880001).blocked());
        risk.review(880001, true, true, true, "synthetic review", 1);
        RiskDecision decision = risk.evaluateWithdrawal(880001);
        assertTrue(decision.blocked());
        assertTrue(decision.manualReview());
        assertTrue(decision.rewardsPaused());
        assertEquals(new BigDecimal("37.25"), jdbc.queryForObject("SELECT balance FROM sys_agent_commission_account WHERE user_id=880001", BigDecimal.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_risk_review", Long.class));
        risk.review(880001, false, false, false, "synthetic clearance", 1);
        assertFalse(risk.evaluateWithdrawal(880001).blocked());
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=880001");
        assertTrue(risk.evaluateWithdrawal(880001).blocked());
        assertTrue(risk.evaluateWithdrawal(999999).blocked());
    }

    @Test
    public void decisionSerializesConcurrentBanEvenWhenRiskGuardDidNotExist() throws Exception {
        user(880001);
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_risk_user_state", Long.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch decisionRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reviewerStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            Future<Boolean> admission = pool.submit(() -> transaction.execute(status -> {
                boolean blocked = risk.evaluateWithdrawal(880001).blocked();
                decisionRead.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return blocked;
            }));
            assertTrue(decisionRead.await(10, TimeUnit.SECONDS));
            Future<?> review = pool.submit(() -> {
                reviewerStarted.countDown();
                risk.review(880001, true, true, true, "concurrent synthetic ban", 1);
            });
            assertTrue(reviewerStarted.await(10, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> review.get(300, TimeUnit.MILLISECONDS));
            release.countDown();
            assertFalse(admission.get(10, TimeUnit.SECONDS));
            review.get(10, TimeUnit.SECONDS);
            assertTrue(risk.evaluateWithdrawal(880001).blocked());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void deviceEvidenceIsIdempotentAndUnchangedEvidenceDoesNotUndoManualClearance() {
        user(880001); user(880002);
        settings.put("cps.risk.device.newUsers", "2");
        settings.put("cps.risk.device.windowMinutes", "60");
        LocalDateTime observed = LocalDateTime.now().minusMinutes(1).withNano(0);
        String hash = "a".repeat(64);
        risk.recordDeviceRegistration(880001, hash, "registration-a", observed, 1);
        risk.recordDeviceRegistration(880001, hash, "registration-a", observed, 1);
        assertThrows(ServiceException.class, () -> risk.recordDeviceRegistration(880002, hash, "registration-a", observed, 1));
        risk.recordDeviceRegistration(880002, hash, "registration-b", observed, 1);
        assertEquals(2, risk.scan());
        assertTrue(risk.rewardsPaused(880001));
        assertTrue(risk.rewardsPaused(880002));
        assertEquals(0, risk.scan());
        risk.review(880001, false, false, false, "verified synthetic evidence", 1);
        assertEquals(0, risk.scan());
        assertFalse(risk.rewardsPaused(880001));
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_risk_device_event", Long.class));
    }

    @Test
    public void lowPriceClusterHoldsSnapshotInviterRewards() {
        user(880001); user(880002); user(880003);
        settings.put("cps.risk.invite.sameItemBuyers", "2");
        settings.put("cps.risk.invite.lowPrice", "2.00");
        settings.put("cps.risk.orders.windowDays", "7");
        order("cluster-a", 880002, 880001L, "synthetic-item", "PAID");
        order("cluster-b", 880003, 880001L, "synthetic-item", "PAID");
        assertEquals(1, risk.scan());
        assertTrue(risk.evaluateWithdrawal(880001).manualReview());
        assertFalse(risk.evaluateWithdrawal(880001).blocked());
        assertFalse(risk.rewardsPaused(880002));
    }

    @Test
    public void allRefundedOrdersHoldBuyerAndInviterButMixedOrdersDoNot() {
        user(880001); user(880002);
        settings.put("cps.risk.refund.minimumOrders", "2");
        settings.put("cps.risk.orders.windowDays", "7");
        order("refund-a", 880002, 880001L, "synthetic-a", "FULL_REFUND");
        order("refund-b", 880002, 880001L, "synthetic-b", "SETTLED");
        assertEquals(0, risk.scan());
        jdbc.update("UPDATE sys_order_commission SET line_status='FULL_REFUND' WHERE order_id='refund-b'");
        assertEquals(2, risk.scan());
        assertTrue(risk.rewardsPaused(880001));
        assertTrue(risk.rewardsPaused(880002));
        assertEquals(0, risk.scan());
    }

    @Test
    public void zeroDisablesOnlyConfiguredAbuseSignalsAndInvalidThresholdFails() {
        user(880001);
        order("disabled", 880001, null, "synthetic-a", "FULL_REFUND");
        assertEquals(0, risk.scan());
        assertFalse(risk.rewardsPaused(880001));
        settings.put("cps.risk.refund.minimumOrders", "-1");
        assertThrows(ServiceException.class, risk::scan);
        settings.put("cps.risk.refund.minimumOrders", "1.5");
        assertThrows(ServiceException.class, risk::scan);
    }

    private HealthObservation authorization(String key, String subject, boolean valid, LocalDateTime observed) {
        return new HealthObservation(key, HealthObservation.Kind.AUTHORIZATION, subject, observed, valid, null, null, null, null, null);
    }

    @Test
    public void authorizationAlertsAreMandatoryAndRepeatAfterExactlyTenMinutes() {
        health.record(authorization("auth-a", "tbk:synthetic", false, LocalDateTime.now(clock).minusMinutes(1)), 1);
        assertEquals(1, health.check());
        assertEquals(0, health.check());
        clock.advance(599);
        assertEquals(0, health.check());
        clock.advance(1);
        assertEquals(1, health.check());
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert WHERE kind='AUTHORIZATION_EXPIRED'", Long.class));
    }

    @Test
    public void invalidOptionalConfigurationCannotSuppressAuthorizationAlert() {
        settings.remove("cps.health.channel.minimumBalance");
        health.record(authorization("auth-missing-config", "jd:synthetic", false, LocalDateTime.now(clock).minusMinutes(1)), 1);
        health.check();
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert WHERE kind='AUTHORIZATION_EXPIRED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert WHERE kind='CONFIGURATION_INVALID'", Long.class));
    }

    @Test
    public void concurrentHealthChecksEmitOnlyOneAlertPerInterval() throws Exception {
        health.record(authorization("concurrent-auth", "pdd:synthetic", false, LocalDateTime.now(clock).minusMinutes(1)), 1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> { ready.countDown(); start.await(); return health.check(); }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int count = 0;
            for (Future<Integer> result : results) count += result.get(30, TimeUnit.SECONDS);
            assertEquals(1, count);
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert", Long.class));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void lateAuthorizationEvidenceCannotOverrideNewerRecovery() {
        LocalDateTime now = LocalDateTime.now(clock);
        health.record(authorization("first-invalid", "tbk:recovering", false, now.minusMinutes(3)), 1);
        assertEquals(1, health.check());
        health.record(authorization("recovered", "tbk:recovering", true, now.minusMinutes(1)), 1);
        health.record(authorization("late-invalid", "tbk:recovering", false, now.minusMinutes(2)), 1);
        assertEquals(0, health.check());
        assertFalse(jdbc.queryForObject("SELECT condition_active FROM cps_health_alert_state WHERE subject='tbk:recovering'", Boolean.class));
        assertThrows(ServiceException.class, () -> health.record(authorization("recovered", "tbk:recovering", false, now.minusMinutes(1)), 1));
    }

    @Test
    public void configuredBalanceAndRefundRateAlertsUsePreciseBoundaries() {
        settings.put("cps.health.channel.minimumBalance", "2.00");
        settings.put("cps.health.refund.rateMultiplier", "2");
        settings.put("cps.health.refund.minimumOrders", "100");
        LocalDateTime now = LocalDateTime.now(clock).minusMinutes(1);
        health.record(new HealthObservation("balance", HealthObservation.Kind.CHANNEL_BALANCE, "mock:synthetic", now,
            null, new BigDecimal("1.99"), null, null, null, null), 1);
        health.record(new HealthObservation("refund-rate", HealthObservation.Kind.REFUND_RATE, "tbk:category-synthetic", now,
            null, null, 100L, 4L, 100L, 2L), 1);
        assertEquals(2, health.check());
        assertEquals(0, health.check());
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert WHERE kind='CHANNEL_BALANCE_LOW'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert WHERE kind='REFUND_RATE_SPIKE'", Long.class));
        LocalDateTime recovered = LocalDateTime.now(clock).minusSeconds(30);
        health.record(new HealthObservation("balance-recovery", HealthObservation.Kind.CHANNEL_BALANCE, "mock:synthetic", recovered,
            null, new BigDecimal("2.00"), null, null, null, null), 1);
        health.record(new HealthObservation("refund-recovery", HealthObservation.Kind.REFUND_RATE, "tbk:category-synthetic", recovered,
            null, null, 100L, 2L, 100L, 2L), 1);
        settings.put("cps.health.refund.rateMultiplier", "1");
        assertEquals(0, health.check());
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert_state WHERE condition_active=1", Long.class));
    }

    @Test
    public void invalidExistingPlatformIsMonitoredWithoutAnyBusinessThreshold() {
        jdbc.update("INSERT INTO sys_union_platform(platform_type,platform_name,app_key,app_secret,is_quote,status) "
            + "VALUES ('tbk','synthetic','test-placeholder','test-placeholder','2','0')");
        assertEquals(1, health.check());
        assertEquals("AUTHORIZATION_EXPIRED", jdbc.queryForObject("SELECT kind FROM cps_health_alert", String.class));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;
        private MutableClock(Instant instant, ZoneId zone) { this.instant = instant; this.zone = zone; }
        private void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId newZone) { return Clock.fixed(instant, newZone); }
        @Override public Instant instant() { return instant; }
    }
}
