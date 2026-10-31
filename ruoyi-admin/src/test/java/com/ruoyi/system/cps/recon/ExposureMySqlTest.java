package com.ruoyi.system.cps.recon;

import static org.junit.Assert.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.ReconBatchMapper;

/** Real MySQL verifies hot configuration, row-lock ownership and rollback of partial snapshots. */
public class ExposureMySqlTest {
    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static ReconBatchMapper mapper;
    private static DataSourceTransactionManager transactions;
    private MutableClock clock;
    private ExposureService exposure;

    @BeforeClass public static void setup() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with real MySQL 8 is required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line12_exposure_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            (slash < 0 ? path : path.substring(0, slash)) + "/" + database + options, user, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        assertEquals("", configured(ExposureService.LIMIT_KEY));
        assertEquals("MANUAL_REVIEW", configured(ExposureService.ACTION_KEY));
        assertEquals("60", configured(ExposureService.INTERVAL_KEY));
        assertEquals("0 * * * * ?", jdbc.queryForObject(
            "SELECT cron_expression FROM sys_job WHERE invoke_target='cpsExposureTask.snapshot()'", String.class));
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMapperLocations(new ClassPathResource("mapper/cps/ReconBatchMapper.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(bean.getObject()).getMapper(ReconBatchMapper.class);
        transactions = new DataSourceTransactionManager(dataSource);
    }

    @AfterClass public static void cleanup() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line12_exposure_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Before public void reset() {
        for (String table : List.of("cps_exposure_policy_audit", "cps_exposure_snapshot", "cps_recon_coverage",
            "cps_recon_source", "sys_commission_flow", "sys_order_commission", "sys_agent_commission_account")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE cps_exposure_schedule SET last_capture_at=NULL WHERE id=1");
        jdbc.update("DELETE FROM sys_config WHERE config_key IN (?,?,?)",
            ExposureService.LIMIT_KEY, ExposureService.ACTION_KEY, ExposureService.INTERVAL_KEY);
        setting(ExposureService.LIMIT_KEY, "");
        setting(ExposureService.ACTION_KEY, "MANUAL_REVIEW");
        setting(ExposureService.INTERVAL_KEY, "60");
        clock = new MutableClock(START);
        exposure = service(mapper);
    }

    @Test public void missingBlankNegativeMalformedAndDuplicateLimitsFailClosed() {
        credit("initial", "9.00");
        jdbc.update("DELETE FROM sys_config WHERE config_key=?", ExposureService.LIMIT_KEY);
        assertDenied(exposure.evaluateWithdrawal(decimal("1")));
        for (String invalid : List.of("", "-1", "not-money", "0.001", "1E1000000", "10000000000000000")) {
            setting(ExposureService.LIMIT_KEY, invalid);
            assertDenied(exposure.evaluateWithdrawal(decimal("1")));
        }
        setting(ExposureService.LIMIT_KEY, "100");
        jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES(?,'100')", ExposureService.LIMIT_KEY);
        assertDenied(exposure.evaluateWithdrawal(decimal("1")));
        assertEquals(1, count("sys_commission_flow"));
        assertEquals(0, count("cps_exposure_snapshot"));
    }

    @Test public void zeroDisablesTheCeilingForBothActionsButStillCalculatesCurrentExposure() {
        credit("initial", "9.00");
        for (ExposureLimitAction action : ExposureLimitAction.values()) {
            exposure.configure(policy("0", action, 60), 42L);
            ExposureDecision decision = exposure.evaluateWithdrawal(decimal("1"));
            assertFalse(decision.blocked());
            assertFalse(decision.manualReview());
            amount("0", decision.limit());
            amount("9", decision.current());
        }
        credit("new-income", "4.00");
        amount("13", exposure.evaluateWithdrawal(decimal("1")).current());
        assertEquals(0, count("cps_exposure_snapshot"));
        assertEquals(2, count("sys_commission_flow"));
    }

    @Test public void exactAndExceededCeilingsUseTheConfiguredActionAndBelowRemainsClear() {
        credit("initial", "9.00");
        for (ExposureLimitAction action : ExposureLimitAction.values()) {
            for (String ceiling : List.of("9.01", "9.00", "8.99")) {
                exposure.configure(policy(ceiling, action, 60), 42L);
                ExposureDecision decision = exposure.evaluateWithdrawal(decimal("1"));
                boolean atOrAbove = decimal("9").compareTo(decimal(ceiling)) >= 0;
                assertEquals(action + " / " + ceiling, atOrAbove, decision.manualReview());
                assertEquals(action + " / " + ceiling, atOrAbove && action == ExposureLimitAction.REJECT, decision.blocked());
                amount("9", decision.current());
                amount(ceiling, decision.limit());
            }
        }
        assertEquals(1, count("sys_commission_flow"));
    }

    @Test public void absentActionDefaultsToManualButAnInvalidOrDuplicateActionBlocks() {
        credit("initial", "9.00");
        setting(ExposureService.LIMIT_KEY, "8");
        jdbc.update("DELETE FROM sys_config WHERE config_key=?", ExposureService.ACTION_KEY);
        ExposureDecision defaults = exposure.evaluateWithdrawal(decimal("1"));
        assertFalse(defaults.blocked());
        assertTrue(defaults.manualReview());
        for (String invalid : List.of("", "ALLOW", "manual_review")) {
            setting(ExposureService.ACTION_KEY, invalid);
            assertDenied(exposure.evaluateWithdrawal(decimal("1")));
        }
        setting(ExposureService.ACTION_KEY, "MANUAL_REVIEW");
        jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES(?,'REJECT')", ExposureService.ACTION_KEY);
        assertDenied(exposure.evaluateWithdrawal(decimal("1")));
    }

    @Test public void configuringPolicyValidatesPersistsAndAuditsTheAuthenticatedOperator() {
        ExposurePolicy unconfigured = exposure.configuration();
        assertNull(unconfigured.limit());
        assertEquals(ExposureLimitAction.MANUAL_REVIEW, unconfigured.action());
        assertEquals(60, unconfigured.intervalMinutes());
        ExposurePolicy saved = exposure.configure(policy("0", ExposureLimitAction.REJECT, 7), 42L);
        assertEquals(policy("0.00", ExposureLimitAction.REJECT, 7), saved);
        assertEquals(saved, exposure.configuration());
        assertEquals("0.00", configured(ExposureService.LIMIT_KEY));
        assertEquals("REJECT", configured(ExposureService.ACTION_KEY));
        assertEquals("7", configured(ExposureService.INTERVAL_KEY));
        assertEquals(1, count("cps_exposure_policy_audit"));
        assertEquals(Long.valueOf(42), jdbc.queryForObject("SELECT operator_id FROM cps_exposure_policy_audit", Long.class));
        JSONObject audit = JSON.parseObject(jdbc.queryForObject("SELECT after_config FROM cps_exposure_policy_audit", String.class));
        amount("0", audit.getBigDecimal("limit"));
        assertEquals("REJECT", audit.getString("action"));
        assertEquals(7, audit.getIntValue("intervalMinutes"));
        assertTrue(jdbc.queryForObject("SELECT before_config FROM cps_exposure_policy_audit", String.class)
            .contains(ExposureService.LIMIT_KEY));
    }

    @Test public void invalidPolicyWritesLeaveEverySettingAndAuditUntouched() {
        for (ExposurePolicy invalid : List.of(
            new ExposurePolicy(null, ExposureLimitAction.MANUAL_REVIEW, 60),
            policy("-0.01", ExposureLimitAction.MANUAL_REVIEW, 60),
            policy("0.001", ExposureLimitAction.MANUAL_REVIEW, 60),
            policy("10000000000000000", ExposureLimitAction.MANUAL_REVIEW, 60),
            policy("0", null, 60), policy("0", ExposureLimitAction.MANUAL_REVIEW, 0),
            policy("0", ExposureLimitAction.MANUAL_REVIEW, -1))) {
            assertThrows(ServiceException.class, () -> exposure.configure(invalid, 42L));
        }
        assertThrows(ServiceException.class, () -> exposure.configure(null, 42L));
        assertThrows(ServiceException.class, () -> exposure.configure(policy("0", ExposureLimitAction.MANUAL_REVIEW, 60), 0L));
        assertEquals("", configured(ExposureService.LIMIT_KEY));
        assertEquals("MANUAL_REVIEW", configured(ExposureService.ACTION_KEY));
        assertEquals("60", configured(ExposureService.INTERVAL_KEY));
        assertEquals(0, count("cps_exposure_policy_audit"));
        assertNull(checkpoint());
    }

    @Test public void duplicateConfigAndFailedAuditRollBackAllEarlierConfigurationWrites() {
        jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES(?,'REJECT')", ExposureService.ACTION_KEY);
        assertThrows(ServiceException.class, () -> exposure.configure(policy("100", ExposureLimitAction.REJECT, 1), 42L));
        assertEquals("", configured(ExposureService.LIMIT_KEY));
        assertEquals("60", configured(ExposureService.INTERVAL_KEY));
        assertEquals(0, count("cps_exposure_policy_audit"));
        jdbc.update("DELETE FROM sys_config WHERE config_key=?", ExposureService.ACTION_KEY);
        setting(ExposureService.ACTION_KEY, "MANUAL_REVIEW");
        ReconBatchMapper failing = mock(ReconBatchMapper.class, delegatesTo(mapper));
        doThrow(new ServiceException("synthetic audit write failure")).when(failing)
            .appendExposurePolicyAudit(anyLong(), anyString(), anyString());
        assertThrows(ServiceException.class, () -> service(failing).configure(policy("100", ExposureLimitAction.REJECT, 1), 42L));
        assertEquals("", configured(ExposureService.LIMIT_KEY));
        assertEquals("MANUAL_REVIEW", configured(ExposureService.ACTION_KEY));
        assertEquals("60", configured(ExposureService.INTERVAL_KEY));
        assertEquals(0, count("cps_exposure_policy_audit"));
    }

    @Test public void defaultIntervalIsOneHourAndEarlyTicksDoNotScanPositions() {
        ReconBatchMapper measured = mock(ReconBatchMapper.class, delegatesTo(mapper));
        ExposureService measuredService = service(measured);
        assertTrue(measuredService.captureIfDue());
        assertEquals(6, count("cps_exposure_snapshot"));
        assertEquals(utc(START), checkpoint());
        clock.set(START.plusSeconds(3600).minusMillis(1));
        assertFalse(measuredService.captureIfDue());
        verify(measured, times(1)).selectExposurePositions(null);
        assertEquals(6, count("cps_exposure_snapshot"));
        clock.set(START.plusSeconds(3600));
        assertTrue(measuredService.captureIfDue());
        verify(measured, times(2)).selectExposurePositions(null);
        assertEquals(12, count("cps_exposure_snapshot"));
        assertEquals(utc(START.plusSeconds(3600)), checkpoint());
    }

    @Test public void changingTheIntervalTakesEffectWithoutRebuildingTheServiceOrScheduler() {
        exposure.configure(policy("0", ExposureLimitAction.MANUAL_REVIEW, 60), 42L);
        assertTrue(exposure.captureIfDue());
        clock.set(START.plusSeconds(10 * 60));
        assertFalse(exposure.captureIfDue());
        exposure.configure(policy("0", ExposureLimitAction.MANUAL_REVIEW, 5), 42L);
        assertTrue(exposure.captureIfDue());
        clock.set(START.plusSeconds(15 * 60).minusMillis(1));
        assertFalse(exposure.captureIfDue());
        clock.set(START.plusSeconds(15 * 60));
        assertTrue(exposure.captureIfDue());
        exposure.configure(policy("0", ExposureLimitAction.MANUAL_REVIEW, 120), 42L);
        clock.set(START.plusSeconds(75 * 60));
        assertFalse(exposure.captureIfDue());
        clock.set(START.plusSeconds(135 * 60));
        assertTrue(exposure.captureIfDue());
        // Existing system-configuration administration is also read live, without a stale cache.
        setting(ExposureService.INTERVAL_KEY, "1");
        clock.set(START.plusSeconds(136 * 60));
        assertTrue(exposure.captureIfDue());
        assertEquals(30, count("cps_exposure_snapshot"));
    }

    @Test(timeout = 30000) public void concurrentInstancesCaptureOnlyOnceAtTheSameDueInstant() throws Exception {
        int workers = 8;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Boolean>> ticks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                ExposureService instance = service(mapper);
                ticks.add(() -> { start.await(10, TimeUnit.SECONDS); return instance.captureIfDue(); });
            }
            int captured = 0;
            for (Future<Boolean> tick : executor.invokeAll(ticks)) if (tick.get()) captured++;
            assertEquals(1, captured);
            assertEquals(6, count("cps_exposure_snapshot"));
            assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM cps_exposure_snapshot WHERE platform='ALL'", Integer.class));
            assertEquals(utc(START), checkpoint());
        } finally { executor.shutdownNow(); }
    }

    @Test public void aMidCaptureFailureRollsBackPartialRowsAndCheckpointAndTheNextTickRetries() {
        assertTrue(exposure.captureIfDue());
        clock.set(START.plusSeconds(3600));
        ReconBatchMapper failing = mock(ReconBatchMapper.class, delegatesTo(mapper));
        doAnswer(invocation -> {
            String platform = invocation.getArgument(0);
            int inserted = mapper.appendExposure(platform, invocation.getArgument(1));
            if ("tbk".equals(platform)) throw new ServiceException("synthetic late snapshot failure");
            return inserted;
        }).when(failing).appendExposure(anyString(), any(BigDecimal.class));
        assertThrows(ServiceException.class, () -> service(failing).captureIfDue());
        assertEquals(6, count("cps_exposure_snapshot"));
        assertEquals(utc(START), checkpoint());
        assertTrue(exposure.captureIfDue());
        assertEquals(12, count("cps_exposure_snapshot"));
        assertEquals(utc(START.plusSeconds(3600)), checkpoint());
    }

    @Test public void withdrawalAlwaysSeesNewLedgerValuesEvenWhenSnapshotsAreNotDueOrIntervalIsInvalid() {
        credit("initial", "9.00");
        exposure.configure(policy("9", ExposureLimitAction.MANUAL_REVIEW, Integer.MAX_VALUE), 42L);
        assertTrue(exposure.captureIfDue());
        clock.set(START.plusSeconds(60));
        assertFalse(exposure.captureIfDue());
        amount("9", exposure.evaluateWithdrawal(decimal("1")).current());
        credit("new-income", "2.00");
        setting(ExposureService.INTERVAL_KEY, "0");
        assertThrows(ServiceException.class, () -> exposure.captureIfDue());
        ExposureDecision realtime = exposure.evaluateWithdrawal(decimal("1"));
        assertFalse(realtime.blocked());
        assertTrue(realtime.manualReview());
        amount("11", realtime.current());
        setting(ExposureService.LIMIT_KEY, "0");
        ExposureDecision disabled = exposure.evaluateWithdrawal(decimal("1"));
        assertFalse(disabled.blocked());
        assertFalse(disabled.manualReview());
        amount("11", disabled.current());
        amount("9", jdbc.queryForObject("SELECT amount FROM cps_exposure_snapshot WHERE platform='ALL'", BigDecimal.class));
        assertEquals(2, count("sys_commission_flow"));
        assertEquals(6, count("cps_exposure_snapshot"));
        assertEquals(utc(START), checkpoint());
    }

    @Test public void absentIntervalUsesOneHourWhileInvalidValuesNeitherScanNorAdvanceTheCheckpoint() {
        jdbc.update("DELETE FROM sys_config WHERE config_key=?", ExposureService.INTERVAL_KEY);
        ReconBatchMapper measured = mock(ReconBatchMapper.class, delegatesTo(mapper));
        ExposureService measuredService = service(measured);
        assertTrue(measuredService.captureIfDue());
        clock.set(START.plusSeconds(3599));
        assertFalse(measuredService.captureIfDue());
        for (String invalid : List.of("", "0", "-1", "NaN", "1.5", "2147483648")) {
            setting(ExposureService.INTERVAL_KEY, invalid);
            assertThrows(ServiceException.class, measuredService::captureIfDue);
        }
        verify(measured, times(1)).selectExposurePositions(null);
        assertEquals(6, count("cps_exposure_snapshot"));
        assertEquals(utc(START), checkpoint());
    }

    @Test public void operationalSnapshotDoesNotPostponeTheScheduledCapture() {
        assertTrue(exposure.captureIfDue());
        clock.set(START.plusSeconds(1800));
        exposure.capture();
        assertEquals(utc(START), checkpoint());
        assertFalse(exposure.captureIfDue());
        clock.set(START.plusSeconds(3600));
        assertTrue(exposure.captureIfDue());
        assertEquals(18, count("cps_exposure_snapshot"));
        assertEquals(utc(START.plusSeconds(3600)), checkpoint());
    }

    private ExposureService service(ReconBatchMapper delegate) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(new ExposureService(delegate, clock));
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (ExposureService) factory.getProxy();
    }

    private static ExposurePolicy policy(String limit, ExposureLimitAction action, int minutes) {
        return new ExposurePolicy(decimal(limit), action, minutes);
    }

    private static void setting(String key, String value) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key=?", Integer.class, key) == 0) {
            jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES(?,?)", key, value);
        } else jdbc.update("UPDATE sys_config SET config_value=? WHERE config_key=?", value, key);
    }

    private static String configured(String key) {
        return jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?", String.class, key);
    }

    private static LocalDateTime checkpoint() {
        return jdbc.queryForObject("SELECT last_capture_at FROM cps_exposure_schedule WHERE id=1", LocalDateTime.class);
    }

    private static void credit(String key, String value) {
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,idempotency_key,business_type,business_id,"
            + "account,debit_account,credit_account,create_time) VALUES(1,'income_self',?,?,'UNFREEZE',?,'USER_FROZEN',"
            + "'USER_FROZEN','USER_AVAILABLE','2026-06-01 00:00:00')", decimal(value), "exposure:" + key, key);
    }

    private static int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static void amount(String expected, BigDecimal actual) { assertEquals(0, decimal(expected).compareTo(actual)); }
    private static void assertDenied(ExposureDecision decision) {
        assertTrue(decision.blocked());
        assertTrue(decision.manualReview());
        amount("9", decision.current());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
