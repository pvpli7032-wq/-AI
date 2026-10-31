package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.ledger.AccountType;
import com.ruoyi.system.cps.ledger.LedgerPosting;
import com.ruoyi.system.cps.ledger.LedgerService;
import com.ruoyi.system.cps.ledger.PostingCommand;
import com.ruoyi.system.cps.mapper.WithdrawalMapper;
import com.ruoyi.system.cps.recon.ExposureDecision;
import com.ruoyi.system.cps.recon.ExposureService;
import com.ruoyi.system.cps.risk.HealthMonitorService;
import com.ruoyi.system.cps.risk.RiskService;
import com.ruoyi.system.service.ISysConfigService;

/**
 * Line 12 acceptance against MySQL 8 and the actual Flyway migrations/MyBatis mapper.
 *
 * The production LedgerService belongs to line 11 and is not implemented on this
 * branch. SqlLedgerContractFixture is an explicitly test-only implementation of
 * that boundary: it tests transaction joining, the withdrawal caller's locking,
 * and idempotency with real rows. These tests are not a substitute for line 11's
 * ledger acceptance or the combined order -> settlement -> ledger integration.
 *
 * LINE4_MYSQL_URL is mandatory. Only a fresh line12_withdraw_<UUID> database is
 * migrated and dropped. No shared/user database is populated or cleaned.
 */
public class WithdrawalMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(12_000_000L);
    private static String adminUrl, mysqlUser, mysqlPassword, database;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static WithdrawalMapper mapper;
    private static TransactionTemplate transactions;

    private SqlLedgerContractFixture ledger;
    private ScriptedChannel channel;
    private ExposureService exposure;
    private RiskService risk;
    private ISysConfigService configs;
    private HealthMonitorService health;
    private WithdrawalService service;
    private JSONObject policy;

    @BeforeClass
    public static void migrateFreshRealMySqlDatabase() throws Exception {
        adminUrl = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL is required for line 12 money/SQL acceptance",
            adminUrl != null && adminUrl.startsWith("jdbc:mysql://"));
        mysqlUser = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        mysqlPassword = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line12_withdraw_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
             Statement statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue("MySQL 8 or later is required", connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = adminUrl.indexOf('?');
        String path = query < 0 ? adminUrl : adminUrl.substring(0, query);
        String options = query < 0 ? "" : adminUrl.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        String base = slash < 0 ? path : path.substring(0, slash);
        DriverManagerDataSource ds = new DriverManagerDataSource(base + "/" + database + options, mysqlUser, mysqlPassword);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
            + "WHERE version='800' AND success=1", Long.class));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/cps/WithdrawalMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WithdrawalMapper.class);
        manager = new DataSourceTransactionManager(ds);
        transactions = new TransactionTemplate(manager);
    }

    @AfterClass
    public static void dropOnlyTheDatabaseCreatedByThisSuite() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line12_withdraw_[0-9a-f]{32}"));
            try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Before
    public void configureSyntheticPolicyAndDependencies() {
        policy = JSON.parseObject("""
            {"enabled":true,"channel":"mock","businessTimezone":"Asia/Shanghai",
             "autoPayEnabled":false,"autoPayBelowAmount":"0",
             "maximum":"0","dailyCount":0,"recipientDailyCount":0,"monthlyAmount":"0","monthDays":"0",
             "integerOnly":false,"requireReceivedOrder":false,"realNameSingle":"0","realNameMonthly":"0",
             "reviewAmount":"0","reviewFirstCount":0,"firstCountBasis":"SUCCEEDED","identityAction":"MANUAL_REVIEW",
             "channelSingle":"0","channelDaily":"0",
             "channelUserDaily":"0","groups":{}}
            """);
        savePolicy();
        jdbc.update("INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time) "
            + "SELECT 'Synthetic minimum for tests','commission.min.withdraw','0.01','N','line12-test',NOW() "
            + "WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='commission.min.withdraw')");
        jdbc.update("UPDATE sys_config SET config_value='0.01' WHERE config_key='commission.min.withdraw'");
        jdbc.update("UPDATE sys_config SET config_value='PROCESSING' WHERE config_key='cps.withdraw.mock.status'");
        configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey(any())).thenAnswer(invocation -> {
            List<String> values = jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_key=?",
                String.class, invocation.getArgument(0));
            return values.isEmpty() ? "" : values.getFirst();
        });
        risk = new RiskService(jdbc, configs);
        health = new HealthMonitorService(jdbc, configs, manager);
        exposure = mock(ExposureService.class);
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(false, false, "synthetic-test",
            money("0.00"), money("1000000.00")));
        ledger = new SqlLedgerContractFixture(jdbc);
        channel = new ScriptedChannel();
        service = newService(ledger, channel);
    }

    @Test
    public void concurrentApplicationsCannotReserveMoreThanTheUsersBalance() throws Exception {
        long user = user("50.00");
        List<WithdrawalView> admitted = concurrently(20, i -> {
            try {
                return service.apply(user, request("10.00", "parallel-" + i));
            } catch (ServiceException rejected) {
                assertTrue(rejected.getMessage(), rejected.getMessage().contains("余额"));
                return null;
            }
        });
        assertEquals(5, admitted.stream().filter(v -> v != null).count());
        assertEquals(5, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
        assertEquals(5, flowCount(user));
        assertAccount(user, "0.00", "50.00", "0.00");
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void concurrentSameRequestKeyIsOneReservationAndDifferentAmountIsRejected() throws Exception {
        long user = user("10.00");
        List<WithdrawalView> responses = concurrently(12, i -> service.apply(user, request("10.00", "same-key")));
        assertEquals(1, responses.stream().map(WithdrawalView::withdrawNo).distinct().count());
        assertEquals(1, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
        assertEquals(1, flowCount(user));
        assertAccount(user, "0.00", "10.00", "0.00");
        assertThrows(ServiceException.class, () -> service.apply(user, request("9.00", "same-key")));
    }

    @Test
    public void approvedIsDistinctFromPaidAndDuplicateSuccessNeverPaysTwice() {
        long user = user("20.00");
        WithdrawalView applied = applyAndApprove(user, request("20.00", "success"));
        assertEquals(WithdrawalStatus.APPROVED, applied.status());
        assertAccount(user, "0.00", "20.00", "0.00");
        assertEquals(0, channel.submitted.get());
        channel.next = PaymentStatus.SUCCESS;
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(applied.withdrawNo(), 1L).status());
        service.pay(applied.withdrawNo(), 1L);
        service.callback(channel.notification(applied.withdrawNo(), PaymentStatus.SUCCESS));
        service.callback(channel.notification(applied.withdrawNo(), PaymentStatus.SUCCESS));
        assertEquals(applied.withdrawNo(), service.apply(user, request("20.00", "success")).withdrawNo());
        assertAccount(user, "0.00", "0.00", "20.00");
        assertEquals(2, flowCount(user));
        assertEquals(1, channel.submitted.get());
    }

    @Test
    public void concurrentPayQueriesThePersistedSubmissionAndPaysOnlyOnce() throws Exception {
        long user = user("20.00");
        String no = applyAndApprove(user, request("20.00", "concurrent-pay")).withdrawNo();
        channel.next = PaymentStatus.SUCCESS;
        CallGate submission = new CallGate();
        channel.submitGate = submission;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<WithdrawalView> first = pool.submit(() -> service.pay(no, 1L));
            submission.awaitEntered();
            assertEquals("SUBMITTING", status(no));
            assertAccount(user, "0.00", "20.00", "0.00");
            // The original channel call remains in flight while every contender calls pay.
            List<WithdrawalView> contenders = concurrently(12, i -> service.pay(no, 2L));
            assertTrue(contenders.stream().allMatch(view -> view.status() == WithdrawalStatus.SUCCEEDED));
            submission.open();
            assertEquals(WithdrawalStatus.SUCCEEDED, first.get(20, TimeUnit.SECONDS).status());
            assertEquals(List.of(no), channel.submitNumbers);
            assertTrue("A submitted payment must be queried, not submitted again", channel.queried.get() > 0);
            assertTrue(channel.queryNumbers.stream().allMatch(no::equals));
            assertEquals(1, channel.submitted.get());
            assertEquals(1, auditCount(no, "SUBMIT"));
            assertEquals(1, auditCount(no, "CHANNEL_RESULT"));
            assertEquals("SUCCEEDED", status(no));
            assertAccount(user, "0.00", "0.00", "20.00");
            assertEquals(2, flowCount(user));
            assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        } finally {
            submission.open();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void paymentClaimWinningTheAuditRacePreventsARefundWhileTheChannelIsInFlight() throws Exception {
        long user = user("20.00");
        String no = applyAndApprove(user, request("20.00", "pay-before-reject")).withdrawNo();
        CallGate submission = new CallGate();
        channel.submitGate = submission;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<WithdrawalView> payment = pool.submit(() -> service.pay(no, 1L));
            submission.awaitEntered();
            assertEquals("SUBMITTING", status(no));
            ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.audit(no, false, 2L, "Synthetic competing rejection"));
            assertEquals("当前提现状态不允许审核或退回", rejected.getMessage());
            assertEquals("SUBMITTING", status(no));
            assertAccount(user, "0.00", "20.00", "0.00");
            assertWithdrawalFlows(no, "WITHDRAW_APPLY");
            assertEquals(0, auditCount(no, "REJECT"));
            submission.open();
            assertEquals(WithdrawalStatus.PROCESSING, payment.get(20, TimeUnit.SECONDS).status());
            assertEquals(WithdrawalStatus.SUCCEEDED,
                service.callback(channel.notification(no, PaymentStatus.SUCCESS)).status());
            assertAccount(user, "0.00", "0.00", "20.00");
            assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
            assertEquals(1, channel.submitted.get());
            assertEquals(1, auditCount(no, "SUBMIT"));
            assertEquals(0, auditCount(no, "REJECT"));
        } finally {
            submission.open();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void rejectionWinningThePaymentRaceRefundsOnceAndPreventsSubmission() throws Exception {
        long user = user("20.00");
        String no = applyAndApprove(user, request("20.00", "reject-before-pay")).withdrawNo();
        CallGate refund = new CallGate();
        ledger.returnGate = refund;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch payEntered = new CountDownLatch(1);
        try {
            Future<WithdrawalView> rejection = pool.submit(() -> service.audit(no, false, 1L, "Synthetic rejection won"));
            refund.awaitEntered(); // audit holds the real admission/account/withdrawal locks.
            Future<WithdrawalView> payment = pool.submit(() -> {
                payEntered.countDown();
                return service.pay(no, 2L);
            });
            assertTrue(payEntered.await(10, TimeUnit.SECONDS));
            assertEquals("APPROVED", status(no));
            assertAccount(user, "0.00", "20.00", "0.00");
            refund.open();
            assertEquals(WithdrawalStatus.REJECTED, rejection.get(20, TimeUnit.SECONDS).status());
            assertEquals(WithdrawalStatus.REJECTED, payment.get(20, TimeUnit.SECONDS).status());
            assertEquals(WithdrawalStatus.REJECTED, service.audit(no, false, 1L, "Synthetic duplicate rejection").status());
            assertEquals("REJECTED", status(no));
            assertAccount(user, "20.00", "0.00", "0.00");
            assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_RETURN");
            assertEquals(0, channel.submitted.get());
            assertEquals(0, channel.queried.get());
            assertEquals(0, auditCount(no, "SUBMIT"));
            assertEquals(1, auditCount(no, "REJECT"));
        } finally {
            refund.open();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void submissionTimeoutUsesOriginalNumberAndOnlyQueriesAfterward() {
        long user = user("25.00");
        WithdrawalView applied = applyAndApprove(user, request("25.00", "timeout"));
        channel.next = PaymentStatus.SUCCESS;
        channel.throwAfterSubmit = true;
        assertEquals(WithdrawalStatus.UNKNOWN, service.pay(applied.withdrawNo(), 1L).status());
        assertAccount(user, "0.00", "25.00", "0.00");
        assertEquals(1, channel.submitted.get());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(applied.withdrawNo(), 1L).status());
        assertEquals(1, channel.submitted.get());
        assertEquals(1, channel.queried.get());
        assertEquals(List.of(applied.withdrawNo()), channel.submitNumbers);
        assertEquals(List.of(applied.withdrawNo()), channel.queryNumbers);
        assertAccount(user, "0.00", "0.00", "25.00");
    }

    @Test
    public void processingAndFailedQueriesKeepReservationUntilAConclusiveResult() {
        long user = user("30.00");
        WithdrawalView applied = applyAndApprove(user, request("30.00", "processing"));
        channel.next = PaymentStatus.PROCESSING;
        assertEquals(WithdrawalStatus.PROCESSING, service.pay(applied.withdrawNo(), 1L).status());
        channel.throwOnQuery = true;
        service.query(applied.withdrawNo());
        assertAccount(user, "0.00", "30.00", "0.00");
        assertEquals(1, flowCount(user));
        channel.throwOnQuery = false;
        channel.results.put(applied.withdrawNo(), channel.result(applied.withdrawNo(), PaymentStatus.SUCCESS));
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(applied.withdrawNo(), 1L).status());
        assertEquals(1, channel.submitted.get());
        assertAccount(user, "0.00", "0.00", "30.00");
    }

    @Test
    public void definitiveFailureRefundsOnceAndConflictingLateSuccessCannotMutateMoney() {
        long user = user("45.00");
        WithdrawalView applied = applyAndApprove(user, request("25.00", "failed"));
        String no = applied.withdrawNo();
        channel.next = PaymentStatus.FAILED;
        assertEquals(WithdrawalStatus.FAILED, service.pay(no, 1L).status());
        assertEquals(WithdrawalStatus.FAILED, service.callback(channel.notification(no, PaymentStatus.FAILED)).status());
        LocalDateTime completed = mapper.findByNo(no).getCompletedAt();
        assertNotNull(completed);
        assertEquals(0, auditCount(no, "RESULT_CONFLICT"));
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T01:00:00Z"), ZoneOffset.UTC);
        health = new HealthMonitorService(jdbc, configs, manager, clock);
        service = newService(ledger, channel);
        // The scripted adapter deliberately forwards the contradictory terminal result.
        // A thrown exception must fail this test, rather than masquerade as conflict handling.
        assertEquals(WithdrawalStatus.FAILED, service.callback(channel.notification(no, PaymentStatus.SUCCESS)).status());
        assertConflictAudit(no, WithdrawalStatus.FAILED, 1);
        assertConflictAlert(no, WithdrawalStatus.FAILED, PaymentStatus.SUCCESS);
        // Deduplication survives service reconstruction and is not the auth-alert 10-minute timer.
        health = new HealthMonitorService(jdbc, configs, manager, Clock.offset(clock, Duration.ofHours(1)));
        service = newService(ledger, channel);
        assertEquals(WithdrawalStatus.FAILED, service.callback(channel.notification(no, PaymentStatus.SUCCESS)).status());
        assertConflictAudit(no, WithdrawalStatus.FAILED, 2);
        assertConflictAlert(no, WithdrawalStatus.FAILED, PaymentStatus.SUCCESS);
        assertEquals("FAILED", status(no));
        assertEquals(completed, mapper.findByNo(no).getCompletedAt());
        assertAccount(user, "45.00", "0.00", "0.00");
        assertEquals(2, flowCount(user));
        assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_RETURN");
        assertEquals(1, channel.submitted.get());
    }

    @Test
    public void lateFailureCannotReverseAnAlreadySuccessfulPayout() throws Exception {
        long user = user("10.00");
        WithdrawalView applied = applyAndApprove(user, request("10.00", "success-conflict"));
        String no = applied.withdrawNo();
        channel.next = PaymentStatus.SUCCESS;
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 1L).status());
        LocalDateTime completed = mapper.findByNo(no).getCompletedAt();
        assertNotNull(completed);
        List<WithdrawalView> callbacks = concurrently(6,
            i -> service.callback(channel.notification(no, PaymentStatus.FAILED)));
        assertTrue(callbacks.stream().allMatch(view -> view.status() == WithdrawalStatus.SUCCEEDED));
        assertConflictAudit(no, WithdrawalStatus.SUCCEEDED, 6);
        assertConflictAlert(no, WithdrawalStatus.SUCCEEDED, PaymentStatus.FAILED);
        assertEquals("SUCCEEDED", status(no));
        assertEquals(completed, mapper.findByNo(no).getCompletedAt());
        assertAccount(user, "0.00", "0.00", "10.00");
        assertEquals(2, flowCount(user));
        assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        assertEquals(1, channel.submitted.get());
    }

    @Test
    public void conflictAlertFailureRollsBackItsAuditAndCanBeRetriedWithoutChangingMoney() {
        long user = user("10.00");
        String no = applyAndApprove(user, request("10.00", "conflict-alert-rollback")).withdrawNo();
        channel.next = PaymentStatus.SUCCESS;
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 1L).status());
        long audits = count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=?", no);
        JdbcTemplate failingAlerts = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                int changed = super.update(sql, args);
                if (sql.startsWith("INSERT INTO cps_health_alert(")) {
                    throw new DataAccessResourceFailureException("Synthetic failure after inserting the conflict alert");
                }
                return changed;
            }
        };
        health = new HealthMonitorService(failingAlerts, configs, manager);
        service = newService(ledger, channel);
        assertThrows(DataAccessResourceFailureException.class,
            () -> service.callback(channel.notification(no, PaymentStatus.FAILED)));
        assertEquals(audits, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=?", no));
        assertEquals(0, auditCount(no, "RESULT_CONFLICT"));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_health_alert WHERE subject=?", no));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_health_alert_state WHERE subject=?", no));
        assertEquals("SUCCEEDED", status(no));
        assertAccount(user, "0.00", "0.00", "10.00");
        assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");

        health = new HealthMonitorService(jdbc, configs, manager);
        service = newService(ledger, channel);
        assertEquals(WithdrawalStatus.SUCCEEDED, service.callback(channel.notification(no, PaymentStatus.FAILED)).status());
        assertConflictAudit(no, WithdrawalStatus.SUCCEEDED, 1);
        assertConflictAlert(no, WithdrawalStatus.SUCCEEDED, PaymentStatus.FAILED);
        assertAccount(user, "0.00", "0.00", "10.00");
        assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
    }

    @Test
    public void awaitingConfirmationAndConfirmationTimeoutDoNotReleaseFunds() {
        long user = user("10.00");
        WithdrawalView applied = applyAndApprove(user, request("10.00", "confirmation"));
        channel.next = PaymentStatus.AWAITING_CONFIRMATION;
        assertEquals(WithdrawalStatus.AWAITING_CONFIRMATION, service.pay(applied.withdrawNo(), 1L).status());
        service.callback(channel.notification(applied.withdrawNo(), PaymentStatus.TIMED_OUT));
        assertEquals("TIMED_OUT", status(applied.withdrawNo()));
        assertAccount(user, "0.00", "10.00", "0.00");
        assertEquals(1, flowCount(user));
        channel.results.put(applied.withdrawNo(), channel.result(applied.withdrawNo(), PaymentStatus.FAILED));
        service.query(applied.withdrawNo());
        assertAccount(user, "10.00", "0.00", "0.00");
    }

    @Test
    public void submittedNonterminalStatusesCannotBeRejectedOrApprovedAgain() {
        for (PaymentStatus pending : List.of(PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN,
                PaymentStatus.AWAITING_CONFIRMATION, PaymentStatus.TIMED_OUT)) {
            long user = user("10.00");
            String no = applyAndApprove(user, request("10.00", "reject-submitted-" + pending)).withdrawNo();
            channel.next = pending;
            assertEquals(WithdrawalStatus.valueOf(pending.name()), service.pay(no, 1L).status());
            LocalDateTime submitted = mapper.findByNo(no).getSubmittedAt();
            assertNotNull(submitted);
            for (boolean approve : new boolean[] { false, true }) {
                ServiceException error = assertThrows(ServiceException.class,
                    () -> service.audit(no, approve, 2L, "Synthetic late review of " + pending));
                assertEquals("当前提现状态不允许审核或退回", error.getMessage());
            }
            assertEquals(pending.name(), status(no));
            assertEquals(submitted, mapper.findByNo(no).getSubmittedAt());
            assertNull(mapper.findByNo(no).getCompletedAt());
            assertAccount(user, "0.00", "10.00", "0.00");
            assertWithdrawalFlows(no, "WITHDRAW_APPLY");
            assertEquals(1, auditCount(no, "SUBMIT"));
            assertEquals(0, auditCount(no, "REJECT"));
            assertEquals("A submitted withdrawal cannot receive any approval beyond its explicit prerequisite", 1,
                auditCount(no, "APPROVE"));
        }
        assertEquals(4, channel.submitted.get());
    }

    @Test
    public void queryPendingScansBeyondUnresolvedFirstPageAndIsolatesAnInvalidResult() {
        long user = user("105.00");
        List<String> ownNumbers = new ArrayList<>();
        channel.next = PaymentStatus.UNKNOWN;
        for (int i = 0; i < 105; i++) {
            String no = applyAndApprove(user, request("1.00", "pending-page-" + i)).withdrawNo();
            assertEquals(WithdrawalStatus.UNKNOWN, service.pay(no, 1L).status());
            ownNumbers.add(no);
        }
        long untouchedUser = user("10.00");
        String approved = applyAndApprove(untouchedUser, request("1.00", "pending-excludes-approved")).withdrawNo();
        policy.put("reviewAmount", "1.00");
        savePolicy();
        String manual = service.apply(untouchedUser, request("1.00", "pending-excludes-manual")).withdrawNo();
        assertEquals("APPROVED", status(approved));
        assertEquals("MANUAL_REVIEW", status(manual));

        String invalid = ownNumbers.get(50);
        String succeeded = ownNumbers.get(100);
        String failed = ownNumbers.get(104);
        channel.results.put(invalid, channel.result("synthetic-mismatched-withdrawal", PaymentStatus.SUCCESS));
        channel.results.put(succeeded, channel.result(succeeded, PaymentStatus.SUCCESS));
        channel.results.put(failed, channel.result(failed, PaymentStatus.FAILED));
        // Other tests intentionally leave ambiguous withdrawals reserved. Include them in
        // the expected scan instead of mutating their states or depending on JUnit order.
        List<String> pending = pendingNumbers();
        assertTrue("The conclusive result must be beyond the first 100 rows", pending.indexOf(succeeded) >= 100);
        assertEquals(pending.size(), service.queryPending());
        assertEquals("Every pending number must be queried once in ID order", pending, channel.queryNumbers);
        assertEquals(1, auditCount(invalid, "QUERY_RETRY"));
        assertEquals("UNKNOWN", status(invalid));
        assertWithdrawalFlows(invalid, "WITHDRAW_APPLY");
        assertEquals("SUCCEEDED", status(succeeded));
        assertWithdrawalFlows(succeeded, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        assertEquals("FAILED", status(failed));
        assertWithdrawalFlows(failed, "WITHDRAW_APPLY", "WITHDRAW_RETURN");
        assertEquals(103, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=? AND status='UNKNOWN'", user));
        assertAccount(user, "1.00", "103.00", "1.00");
        assertEquals(107, flowCount(user));
        assertFalse(channel.queryNumbers.contains(approved));
        assertFalse(channel.queryNumbers.contains(manual));

        // A later scan retries the failed item under its original number, skips terminal
        // rows, and still reaches the tail while the first page remains unresolved.
        channel.queryNumbers.clear();
        channel.results.put(invalid, channel.result(invalid, PaymentStatus.SUCCESS));
        List<String> retry = pendingNumbers();
        assertEquals(pending.size() - 2, retry.size());
        assertEquals(retry.size(), service.queryPending());
        assertEquals(retry, channel.queryNumbers);
        assertFalse(channel.queryNumbers.contains(succeeded));
        assertFalse(channel.queryNumbers.contains(failed));
        assertEquals("SUCCEEDED", status(invalid));
        assertEquals(1, auditCount(invalid, "QUERY_RETRY"));
        assertWithdrawalFlows(invalid, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        assertAccount(user, "1.00", "102.00", "2.00");
        assertEquals(108, flowCount(user));
        assertAccount(untouchedUser, "8.00", "2.00", "0.00");
        assertEquals(105, channel.submitted.get());
        assertEquals(ownNumbers, channel.submitNumbers);
    }

    @Test
    public void anyReviewGateRequiresManualReviewAndRejectionRefundsOnlyOnce() {
        long user = user("50.00");
        policy.put("reviewFirstCount", 1);
        savePolicy();
        WithdrawalView applied = service.apply(user, request("20.00", "manual"));
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, applied.status());
        assertThrows(ServiceException.class, () -> service.pay(applied.withdrawNo(), 1L));
        assertEquals(WithdrawalStatus.REJECTED, service.audit(applied.withdrawNo(), false, 1L, "synthetic rejection").status());
        try {
            service.audit(applied.withdrawNo(), false, 1L, "repeat rejection");
        } catch (ServiceException expectedTerminal) {
            assertNotNull(expectedTerminal.getMessage());
        }
        assertAccount(user, "50.00", "0.00", "0.00");
        assertEquals(2, flowCount(user));
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void manualApprovalOnlyChangesApprovalStateAndStillRequiresSeparatePayment() {
        long user = user("20.00");
        policy.put("reviewAmount", "10.00");
        savePolicy();
        WithdrawalView applied = service.apply(user, request("20.00", "manual-approve"));
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, applied.status());
        assertEquals(WithdrawalStatus.APPROVED, service.audit(applied.withdrawNo(), true, 1L, "synthetic review completed").status());
        assertAccount(user, "0.00", "20.00", "0.00");
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void negativeBalanceBannedUserAndExposureBlockAllRejectWithoutReservations() {
        long negative = user("-2.00");
        assertRejectedWithoutMoneyChange(negative);
        long banned = user("10.00");
        jdbc.update("UPDATE sys_user SET risk_level='BANNED' WHERE user_id=?", banned);
        assertRejectedWithoutMoneyChange(banned);
        long frozen = user("10.00");
        jdbc.update("UPDATE sys_agent_commission_account SET status='1' WHERE user_id=?", frozen);
        assertRejectedWithoutMoneyChange(frozen);
        long exposed = user("10.00");
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(true, true,
            "synthetic exceeded exposure", money("101.00"), money("100.00")));
        assertRejectedWithoutMoneyChange(exposed);
    }

    @Test
    public void exposureAtLimitRequiresReviewAndIsRecheckedBeforeDispatch() {
        long user = user("20.00");
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(false, true,
            "synthetic exposure at limit", money("100.00"), money("100.00")));
        WithdrawalView applied = service.apply(user, request("20.00", "at-limit"));
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, applied.status());
        service.audit(applied.withdrawNo(), true, 1L, "synthetic review");
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(true, true,
            "synthetic exposure exceeded", money("101.00"), money("100.00")));
        try {
            service.pay(applied.withdrawNo(), 1L);
        } catch (ServiceException blocked) {
            assertNotNull(blocked.getMessage());
        }
        assertEquals(0, channel.submitted.get());
        assertAccount(user, "0.00", "20.00", "0.00");
    }

    @Test
    public void paidOrderInvalidationContractCreatesDebtAndBlocksFurtherWithdrawal() {
        long user = user("25.00");
        WithdrawalView applied = applyAndApprove(user, request("25.00", "paid-then-invalid"));
        channel.next = PaymentStatus.SUCCESS;
        service.pay(applied.withdrawNo(), 1L);
        // This is the line-11 recovery boundary, not a claim to have run its order-event implementation.
        transactions.executeWithoutResult(tx -> ledger.post(new PostingCommand("synthetic-recovery-" + user,
            "RECOVERY", "synthetic-invalid-order-" + user, AccountType.USER_AVAILABLE, user, null,
            "refund_self", AccountType.USER_AVAILABLE, AccountType.REBATE_COST,
            money("25.00"), "test-only paid order became invalid", 1L)));
        assertAccount(user, "-25.00", "0.00", "25.00");
        assertThrows(ServiceException.class, () -> service.apply(user, request("0.01", "negative-after-payment")));
        assertEquals(1, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
        assertEquals(3, flowCount(user));
    }

    @Test
    public void ledgerFailureRollsBackWithdrawalReservationAndBothLedgerEffects() {
        long user = user("10.00");
        ledger.failAfterSql = true;
        assertThrows(ServiceException.class, () -> service.apply(user, request("10.00", "rollback")));
        assertAccount(user, "10.00", "0.00", "0.00");
        assertEquals(0, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
        assertEquals(0, flowCount(user));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE user_id=?", user));
    }

    @Test
    public void successfulExternalPaymentWithLocalLedgerFailureRecoversByQueryWithoutResubmission() {
        long user = user("15.00");
        WithdrawalView applied = applyAndApprove(user, request("15.00", "finish-rollback"));
        channel.next = PaymentStatus.SUCCESS;
        ledger.failAfterSql = true;
        try {
            service.pay(applied.withdrawNo(), 1L);
        } catch (ServiceException expectedLocalFailure) {
            assertNotNull(expectedLocalFailure.getMessage());
        }
        assertAccount(user, "0.00", "15.00", "0.00");
        assertEquals(1, flowCount(user));
        assertTrue(WithdrawalStatus.valueOf(status(applied.withdrawNo())).submitted());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(applied.withdrawNo(), 1L).status());
        assertAccount(user, "0.00", "0.00", "15.00");
        assertEquals(1, channel.submitted.get());
        assertEquals(1, channel.queried.get());
        assertEquals(2, flowCount(user));
    }

    @Test
    public void absentLedgerOrDisabledConfigurationNeverReportsARealApplication() {
        long user = user("10.00");
        WithdrawalService noLedger = newService(null, channel);
        assertThrows(ServiceException.class, () -> noLedger.apply(user, request("10.00", "no-ledger")));
        policy.put("enabled", false);
        savePolicy();
        assertThrows(ServiceException.class, () -> service.apply(user, request("10.00", "disabled")));
        assertAccount(user, "10.00", "0.00", "0.00");
        assertEquals(0, flowCount(user));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
    }

    @Test
    public void sharedRecipientDailyLimitIsAtomicAcrossDifferentUsers() throws Exception {
        long first = user("10.00");
        long second = user("10.00");
        Map<String, Object> recipient = jdbc.queryForMap("SELECT recipient_hash,recipient_account FROM cps_withdraw_recipient WHERE user_id=?", first);
        jdbc.update("UPDATE cps_withdraw_recipient SET recipient_hash=?,recipient_account=? WHERE user_id=?",
            recipient.get("recipient_hash"), recipient.get("recipient_account"), second);
        policy.put("recipientDailyCount", 1);
        savePolicy();
        List<WithdrawalView> responses = concurrently(2, i -> {
            try {
                return service.apply(i == 0 ? first : second, request("10.00", "shared-payee"));
            } catch (ServiceException expectedLimit) {
                return null;
            }
        });
        assertEquals(1, responses.stream().filter(v -> v != null).count());
        assertEquals(1, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id IN (?,?)", first, second));
        assertMoney("10.00", jdbc.queryForObject("SELECT SUM(balance) FROM sys_agent_commission_account WHERE user_id IN (?,?)",
            BigDecimal.class, first, second));
        assertMoney("10.00", jdbc.queryForObject("SELECT SUM(withdrawing_amount) FROM sys_agent_commission_account WHERE user_id IN (?,?)",
            BigDecimal.class, first, second));
    }

    @Test
    public void monthlyAndPerUserDailyLimitsCountOutstandingReservations() {
        long user = user("50.00");
        policy.put("dailyCount", 1);
        savePolicy();
        service.apply(user, request("10.00", "daily-one"));
        assertThrows(ServiceException.class, () -> service.apply(user, request("10.00", "daily-two")));
        policy.put("dailyCount", 0);
        policy.put("monthlyAmount", "15.00");
        savePolicy();
        assertThrows(ServiceException.class, () -> service.apply(user, request("10.00", "monthly-over")));
        assertAccount(user, "40.00", "10.00", "0.00");
        assertEquals(1, flowCount(user));
    }

    @Test
    public void newlyObservedRiskMovesAManuallyApprovedWithdrawalBackToReview() {
        long user = user("20.00");
        WithdrawalView applied = applyAndApprove(user, request("20.00", "new-risk-before-pay"));
        assertEquals(WithdrawalStatus.APPROVED, applied.status());
        risk.review(user, false, true, true, "Synthetic newly observed abuse signal", 1L);
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, service.pay(applied.withdrawNo(), 1L).status());
        assertNull(mapper.findByNo(applied.withdrawNo()).getAuditBy());
        assertNull(mapper.findByNo(applied.withdrawNo()).getAuditAt());
        assertEquals(0, channel.submitted.get());
        assertAccount(user, "0.00", "20.00", "0.00");
        assertEquals(1, flowCount(user));
    }

    @Test
    public void riskHoldMustBeResolvedBeforeApprovalAndThenPaymentCompletes() {
        long user = user("20.00");
        risk.review(user, false, true, true, "Synthetic risk evidence needs review", 1L);
        String no = service.apply(user, request("20.00", "resolve-risk-review")).withdrawNo();
        assertEquals("MANUAL_REVIEW", status(no));
        ServiceException held = assertThrows(ServiceException.class,
            () -> service.audit(no, true, 2L, "Synthetic withdrawal review cannot waive risk"));
        assertEquals("风控人工复核尚未解除，请先在风控台处理", held.getMessage());
        assertEquals("MANUAL_REVIEW", status(no));
        assertNull(mapper.findByNo(no).getAuditBy());
        assertNull(mapper.findByNo(no).getAuditAt());
        assertEquals(0, auditCount(no, "APPROVE"));
        assertTrue(jdbc.queryForObject("SELECT manual_review FROM cps_risk_user_state WHERE user_id=?", Boolean.class, user));
        assertAccount(user, "0.00", "20.00", "0.00");
        assertWithdrawalFlows(no, "WITHDRAW_APPLY");
        assertEquals(0, channel.submitted.get());

        risk.review(user, false, false, false, "Synthetic risk evidence independently cleared", 3L);
        assertEquals(WithdrawalStatus.APPROVED, service.audit(no, true, 2L, "Synthetic review after risk clearance").status());
        assertEquals(Long.valueOf(2L), mapper.findByNo(no).getAuditBy());
        assertNotNull(mapper.findByNo(no).getAuditAt());
        assertFalse(jdbc.queryForObject("SELECT manual_review FROM cps_risk_user_state WHERE user_id=?", Boolean.class, user));
        channel.next = PaymentStatus.SUCCESS;
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 4L).status());
        assertAccount(user, "0.00", "0.00", "20.00");
        assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        assertEquals(1, channel.submitted.get());
        assertEquals(1, auditCount(no, "APPROVE"));
        assertEquals(1, auditCount(no, "SUBMIT"));
        assertEquals(0, auditCount(no, "REVIEW_REQUIRED"));
    }

    @Test
    public void newlyObservedRiskInvalidatesAnEarlierHumanApprovalAndRequiresANewDecision() {
        long user = user("20.00");
        policy.put("reviewAmount", "10.00");
        savePolicy();
        String no = service.apply(user, request("20.00", "new-risk-after-human-approval")).withdrawNo();
        assertEquals(WithdrawalStatus.APPROVED, service.audit(no, true, 1L, "Synthetic first review").status());
        assertEquals(Long.valueOf(1L), mapper.findByNo(no).getAuditBy());
        assertNotNull(mapper.findByNo(no).getAuditAt());

        risk.review(user, false, true, true, "Synthetic new evidence after first approval", 3L);
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, service.pay(no, 4L).status());
        assertNull("The old reviewer cannot approve newly observed risk", mapper.findByNo(no).getAuditBy());
        assertNull("The previous approval time must not survive a return to review", mapper.findByNo(no).getAuditAt());
        ServiceException held = assertThrows(ServiceException.class,
            () -> service.audit(no, true, 2L, "Synthetic attempt while risk remains active"));
        assertEquals("风控人工复核尚未解除，请先在风控台处理", held.getMessage());
        assertEquals(1, auditCount(no, "APPROVE"));
        assertEquals(1, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? AND action='REVIEW_REQUIRED' "
            + "AND from_status='APPROVED' AND to_status='MANUAL_REVIEW'", no));
        assertEquals(0, channel.submitted.get());
        assertAccount(user, "0.00", "20.00", "0.00");

        risk.review(user, false, false, false, "Synthetic new risk cleared by risk desk", 3L);
        ServiceException notReapproved = assertThrows(ServiceException.class, () -> service.pay(no, 4L));
        assertEquals("提现尚未审核通过", notReapproved.getMessage());
        assertEquals("MANUAL_REVIEW", status(no));
        assertEquals(0, channel.submitted.get());
        assertEquals(WithdrawalStatus.APPROVED, service.audit(no, true, 2L, "Synthetic second review after clearance").status());
        assertEquals(Long.valueOf(2L), mapper.findByNo(no).getAuditBy());
        assertNotNull(mapper.findByNo(no).getAuditAt());
        channel.next = PaymentStatus.SUCCESS;
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 4L).status());
        assertAccount(user, "0.00", "0.00", "20.00");
        assertWithdrawalFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        assertEquals(1, channel.submitted.get());
        assertEquals(2, auditCount(no, "APPROVE"));
        assertEquals(1, auditCount(no, "SUBMIT"));
        assertEquals(1, auditCount(no, "REVIEW_REQUIRED"));
    }

    @Test
    public void exposureReachingTheLimitBeforeManualApprovalRequiresAHumanDecision() {
        long user = user("20.00");
        WithdrawalView applied = service.apply(user, request("20.00", "new-exposure-before-pay"));
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, applied.status());
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(false, true,
            "Synthetic exposure now at limit", money("100.00"), money("100.00")));
        ServiceException unreviewed = assertThrows(ServiceException.class, () -> service.pay(applied.withdrawNo(), 1L));
        assertEquals("提现尚未审核通过", unreviewed.getMessage());
        assertEquals("MANUAL_REVIEW", status(applied.withdrawNo()));
        assertEquals(0, channel.submitted.get());
        assertEquals(WithdrawalStatus.APPROVED,
            service.audit(applied.withdrawNo(), true, 1L, "Synthetic exposure manually approved").status());
        assertEquals(WithdrawalStatus.PROCESSING, service.pay(applied.withdrawNo(), 1L).status());
        assertEquals(1, channel.submitted.get());
        assertAccount(user, "0.00", "20.00", "0.00");
    }

    @Test
    public void loweringTheCurrentChannelLimitPreventsDispatchOfAnOlderApprovedRequest() {
        long user = user("20.00");
        WithdrawalView applied = applyAndApprove(user, request("20.00", "lower-channel-limit"));
        policy.put("channelSingle", "10.00");
        savePolicy();
        assertThrows(ServiceException.class, () -> service.pay(applied.withdrawNo(), 1L));
        assertEquals(0, channel.submitted.get());
        assertAccount(user, "0.00", "20.00", "0.00");
        assertEquals(1, flowCount(user));
    }

    @Test
    public void revokingRecipientVerificationAfterApplicationPreventsDispatch() {
        long user = user("20.00");
        WithdrawalView applied = applyAndApprove(user, request("20.00", "revoke-payee-verification"));
        jdbc.update("UPDATE cps_withdraw_recipient SET verified=0 WHERE user_id=?", user);
        assertThrows(ServiceException.class, () -> service.pay(applied.withdrawNo(), 1L));
        assertEquals(0, channel.submitted.get());
        assertAccount(user, "0.00", "20.00", "0.00");
        assertEquals(1, flowCount(user));
    }

    @Test
    public void durableMockSubmissionKeepsItsOriginalResultAndKeyAfterReconstruction() {
        String no = "WD" + UUID.randomUUID().toString().replace("-", "");
        PaymentRequest request = new PaymentRequest(no, money("12.34"), "Synthetic payee", "synthetic-durable-payee");
        MockWithdrawChannel first = new MockWithdrawChannel(mapper);
        PaymentResult submitted = first.submit(request);
        assertEquals(PaymentStatus.PROCESSING, submitted.status());
        MockWithdrawChannel afterRestart = new MockWithdrawChannel(mapper);
        assertEquals(submitted, afterRestart.query(no));
        jdbc.update("UPDATE sys_config SET config_value='SUCCESS' WHERE config_key='cps.withdraw.mock.status'");
        assertEquals(submitted, afterRestart.submit(request));
        assertThrows(ServiceException.class, () -> afterRestart.submit(new PaymentRequest(no, money("12.35"),
            "Synthetic payee", "synthetic-durable-payee")));
        assertThrows(ServiceException.class, () -> afterRestart.submit(new PaymentRequest(no, money("12.34"),
            "Synthetic payee", "synthetic-different-payee")));
        assertEquals(1, count("SELECT COUNT(*) FROM cps_withdraw_mock_payment WHERE withdraw_no=?", no));
        assertEquals(submitted, afterRestart.query(no));
    }

    @Test
    public void durableMockAuthenticatesCallbacksAndDuplicateSuccessHasOneLedgerEffect() {
        long user = user("18.00");
        service = newService(ledger, new MockWithdrawChannel(mapper));
        WithdrawalView applied = applyAndApprove(user, request("18.00", "mock-authenticated"));
        assertEquals(WithdrawalStatus.PROCESSING, service.pay(applied.withdrawNo(), 1L).status());
        MockWithdrawalPayment payment = mapper.mockPayment(applied.withdrawNo());
        JSONObject body = new JSONObject();
        body.put("withdrawNo", payment.getWithdrawNo());
        body.put("channelNo", payment.getChannelNo());
        body.put("amount", payment.getAmount());
        body.put("status", "SUCCESS");
        assertThrows(ServiceException.class, () -> service.callback(new ChannelNotification("mock", body.toJSONString(), "incorrect")));
        body.put("amount", money("18.01"));
        assertThrows(ServiceException.class, () -> service.callback(new ChannelNotification("mock", body.toJSONString(), payment.getCallbackToken())));
        assertAccount(user, "0.00", "18.00", "0.00");
        assertEquals(PaymentStatus.PROCESSING, mapper.mockPayment(applied.withdrawNo()).getStatus());
        body.put("amount", money("18.00"));
        ChannelNotification valid = new ChannelNotification("mock", body.toJSONString(), payment.getCallbackToken());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.callback(valid).status());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.callback(valid).status());
        assertAccount(user, "0.00", "0.00", "18.00");
        assertEquals(2, flowCount(user));
        body.put("status", "FAILED");
        assertThrows(ServiceException.class, () -> service.callback(new ChannelNotification("mock", body.toJSONString(), payment.getCallbackToken())));
        assertEquals("SUCCEEDED", status(applied.withdrawNo()));
        assertAccount(user, "0.00", "0.00", "18.00");
    }

    @Test
    public void durableMockUnknownQueryCreatesNoPaymentAndTimeoutIsNotFailure() {
        MockWithdrawChannel durable = new MockWithdrawChannel(mapper);
        String absent = "WD" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(PaymentStatus.UNKNOWN, durable.query(absent).status());
        assertEquals(0, count("SELECT COUNT(*) FROM cps_withdraw_mock_payment WHERE withdraw_no=?", absent));
        String no = "WD" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("UPDATE sys_config SET config_value='AWAITING_CONFIRMATION' WHERE config_key='cps.withdraw.mock.status'");
        durable.submit(new PaymentRequest(no, money("1.00"), "Synthetic payee", "synthetic-timeout-payee"));
        assertEquals(PaymentStatus.TIMED_OUT, durable.simulate(no, PaymentStatus.TIMED_OUT).status());
        assertEquals(PaymentStatus.TIMED_OUT, new MockWithdrawChannel(mapper).query(no).status());
        assertEquals(PaymentStatus.FAILED, durable.simulate(no, PaymentStatus.FAILED).status());
        assertEquals(PaymentStatus.FAILED, new MockWithdrawChannel(mapper).query(no).status());
    }

    /** Payment lifecycle tests explicitly establish human approval while automatic payment stays disabled. */
    private WithdrawalView applyAndApprove(long user, WithdrawalApplyRequest request) {
        WithdrawalView applied = service.apply(user, request);
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, applied.status());
        assertEquals(0, auditCount(applied.withdrawNo(), "APPROVE"));
        assertEquals(0, auditCount(applied.withdrawNo(), "SUBMIT"));
        WithdrawalView approved = service.audit(applied.withdrawNo(), true, 1L,
            "Synthetic explicit approval for payment lifecycle acceptance");
        assertEquals(WithdrawalStatus.APPROVED, approved.status());
        assertEquals(applied.withdrawNo(), approved.withdrawNo());
        assertEquals(1, auditCount(applied.withdrawNo(), "APPROVE"));
        assertEquals(0, auditCount(applied.withdrawNo(), "SUBMIT"));
        return approved;
    }

    private WithdrawalService newService(LedgerService posting, WithdrawChannel gateway) {
        return new WithdrawalService(mapper, provider(LedgerService.class, posting), provider(WithdrawChannel.class, gateway),
            risk, exposure, health, manager);
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (value != null) beans.addBean("synthetic", value);
        return beans.getBeanProvider(type);
    }

    private void assertRejectedWithoutMoneyChange(long user) {
        BigDecimal before = jdbc.queryForObject("SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user);
        assertThrows(ServiceException.class, () -> service.apply(user, request("1.00", "blocked")));
        assertMoney(before.toPlainString(), jdbc.queryForObject("SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user));
        assertEquals(0, flowCount(user));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
    }

    private void savePolicy() {
        jdbc.update("UPDATE cps_withdraw_policy SET config=?,updated_by=1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=1", policy.toJSONString());
    }

    private static WithdrawalApplyRequest request(String amount, String key) {
        return new WithdrawalApplyRequest(money(amount), "line12-" + key);
    }

    private static long user(String balance) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag,create_time) VALUES (?,?,?,'0','0',NOW())",
            id, "line12-test-" + id, "synthetic payee " + id);
        jdbc.update("INSERT INTO sys_agent_commission_account(user_id,balance,status,create_time) VALUES (?,?,'0',NOW())", id, money(balance));
        String account = "synthetic-account-" + id;
        jdbc.update("INSERT INTO cps_withdraw_recipient(user_id,channel,recipient_name,recipient_account,recipient_hash,verified,verification_ref,updated_by,updated_at) "
            + "VALUES (?,'mock',?,?,?,1,'synthetic-verification',1,CURRENT_TIMESTAMP(3))", id, "synthetic payee " + id, account, hash(account));
        return id;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String status(String no) {
        return jdbc.queryForObject("SELECT status FROM cps_withdrawal WHERE withdraw_no=?", String.class, no);
    }

    private static long flowCount(long user) {
        return count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", user);
    }

    private static void assertWithdrawalFlows(String no, String... businessTypes) {
        List<String> expected = List.of(businessTypes).stream().sorted().toList();
        assertEquals(expected, jdbc.queryForList("SELECT business_type FROM sys_commission_flow "
            + "WHERE business_id=? ORDER BY business_type", String.class, no));
        assertEquals(expected.size(), count("SELECT COUNT(DISTINCT idempotency_key) FROM sys_commission_flow WHERE business_id=?", no));
        for (String type : expected) {
            assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE business_id=? "
                + "AND business_type=? AND idempotency_key=?", no, type, type + ":" + no));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow f JOIN cps_withdrawal w ON w.withdraw_no=f.business_id "
            + "WHERE w.withdraw_no=? AND f.amount<>w.amount", no));
    }

    private static long auditCount(String no, String action) {
        return count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? AND action=?", no, action);
    }

    private static void assertConflictAudit(String no, WithdrawalStatus original, long expected) {
        assertEquals(expected, auditCount(no, "RESULT_CONFLICT"));
        assertEquals(expected, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? "
            + "AND action='RESULT_CONFLICT' AND from_status=? AND to_status=? AND operator_id IS NULL "
            + "AND reason LIKE '%人工对账%'", no, original.name(), original.name()));
    }

    private void assertConflictAlert(String no, WithdrawalStatus original, PaymentStatus incoming) {
        assertEquals(1, count("SELECT COUNT(*) FROM cps_health_alert WHERE kind='WITHDRAWAL_RESULT_CONFLICT' AND subject=?", no));
        assertEquals(1, count("SELECT COUNT(*) FROM cps_health_alert_state WHERE kind='WITHDRAWAL_RESULT_CONFLICT' "
            + "AND subject=? AND condition_active=1 AND last_emitted_at IS NOT NULL", no));
        List<Map<String, Object>> visible = health.alerts().stream()
            .filter(alert -> no.equals(alert.get("subject")) && "WITHDRAWAL_RESULT_CONFLICT".equals(alert.get("kind"))).toList();
        assertEquals("The durable alert must be visible through the operations alert list", 1, visible.size());
        String message = (String) visible.getFirst().get("message");
        assertTrue(message, message.contains("本地=" + original));
        assertTrue(message, message.contains("通道=" + incoming));
        assertTrue(message, message.contains("人工对账"));
        Object active = visible.getFirst().get("condition_active");
        assertTrue(Boolean.TRUE.equals(active) || active instanceof Number number && number.intValue() == 1);
    }

    private static List<String> pendingNumbers() {
        return jdbc.queryForList("SELECT withdraw_no FROM cps_withdrawal "
            + "WHERE status IN('SUBMITTING','PROCESSING','UNKNOWN','AWAITING_CONFIRMATION','TIMED_OUT') ORDER BY id", String.class);
    }

    private static long count(String sql, Object... params) {
        return jdbc.queryForObject(sql, Long.class, params);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals("Expected " + expected + " but was " + actual, 0, money(expected).compareTo(actual));
    }

    private static void assertAccount(long user, String available, String withdrawing, String paid) {
        Map<String, Object> row = jdbc.queryForMap("SELECT balance,withdrawing_amount,withdrawn FROM sys_agent_commission_account WHERE user_id=?", user);
        assertMoney(available, (BigDecimal) row.get("balance"));
        assertMoney(withdrawing, (BigDecimal) row.get("withdrawing_amount"));
        assertMoney(paid, (BigDecimal) row.get("withdrawn"));
    }

    @FunctionalInterface
    private interface ParallelAction<T> { T run(int index) throws Exception; }

    private static <T> List<T> concurrently(int n, ParallelAction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    return action.run(index);
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();
            List<T> result = new ArrayList<>();
            for (Future<T> future : futures) result.add(future.get(40, TimeUnit.SECONDS));
            return result;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Holds an actual in-flight operation at a known boundary, without sleeps. */
    private static final class CallGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void pause() {
            entered.countDown();
            try {
                assertTrue("Timed out waiting to release the synthetic operation", released.await(40, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic operation was interrupted", interrupted);
            }
        }

        void awaitEntered() throws InterruptedException {
            assertTrue("The synthetic operation did not reach its boundary", entered.await(20, TimeUnit.SECONDS));
        }

        void open() { released.countDown(); }
    }

    /** Test-only contract participant; never scanned or used as a production ledger bean. */
    private static final class SqlLedgerContractFixture implements LedgerService {
        private final JdbcTemplate sql;
        private boolean failAfterSql;
        private CallGate returnGate;
        SqlLedgerContractFixture(JdbcTemplate sql) { this.sql = sql; }

        @Override
        public LedgerPosting post(PostingCommand command) {
            assertTrue("Withdrawal and ledger must join one transaction", TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(command.debitAccount(), command.account());
            assertTrue(command.amount().signum() > 0);
            Map<String, Object> account = sql.queryForMap("SELECT balance,withdrawing_amount,withdrawn,frozen_amount "
                + "FROM sys_agent_commission_account WHERE user_id=? FOR UPDATE", command.userId());
            List<Map<String, Object>> prior = sql.queryForList("SELECT id,balance_after FROM sys_commission_flow WHERE idempotency_key=?", command.businessKey());
            if (!prior.isEmpty()) {
                return new LedgerPosting(((Number) prior.getFirst().get("id")).longValue(), command.businessKey(),
                    (BigDecimal) prior.getFirst().get("balance_after"), true);
            }
            if (returnGate != null && "WITHDRAW_RETURN".equals(command.businessType())) returnGate.pause();
            BigDecimal available = (BigDecimal) account.get("balance");
            BigDecimal inFlight = (BigDecimal) account.get("withdrawing_amount");
            BigDecimal paid = (BigDecimal) account.get("withdrawn");
            BigDecimal frozen = (BigDecimal) account.get("frozen_amount");
            BigDecimal before = available;
            BigDecimal amount = command.amount();
            if (command.debitAccount() == AccountType.USER_AVAILABLE) available = available.subtract(amount);
            if (command.creditAccount() == AccountType.USER_AVAILABLE) available = available.add(amount);
            if (command.debitAccount() == AccountType.USER_WITHDRAWING) inFlight = inFlight.subtract(amount);
            if (command.creditAccount() == AccountType.USER_WITHDRAWING) inFlight = inFlight.add(amount);
            if (command.debitAccount() == AccountType.USER_FROZEN) frozen = frozen.subtract(amount);
            if (command.creditAccount() == AccountType.USER_FROZEN) frozen = frozen.add(amount);
            if (command.creditAccount() == AccountType.PAYOUT) paid = paid.add(amount);
            assertTrue("The caller cannot consume more reserved money than exists", inFlight.signum() >= 0);
            if (command.creditAccount() == AccountType.USER_WITHDRAWING) {
                assertTrue("The real account lock must prevent over-reservation", available.signum() >= 0);
            }
            sql.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,balance_before,balance_after,related_order_id,"
                + "business_type,business_id,account,debit_account,credit_account,idempotency_key,description,create_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))", command.userId(), command.type(),
                command.amount(), before, available, command.orderLineId() == null ? null : command.orderLineId().toString(),
                command.businessType(), command.businessId(), command.account().name(), command.debitAccount().name(),
                command.creditAccount().name(), command.businessKey(), command.reason());
            sql.update("UPDATE sys_agent_commission_account SET balance=?,withdrawing_amount=?,withdrawn=?,frozen_amount=?,version=version+1 WHERE user_id=?",
                available, inFlight, paid, frozen, command.userId());
            if (failAfterSql) {
                failAfterSql = false;
                throw new ServiceException("Synthetic ledger failure after both SQL writes");
            }
            Long id = sql.queryForObject("SELECT id FROM sys_commission_flow WHERE idempotency_key=?", Long.class, command.businessKey());
            return new LedgerPosting(id, command.businessKey(), available, false);
        }
    }

    /** No network call or real payee: this channel makes adverse outcomes deterministic. */
    private static final class ScriptedChannel implements WithdrawChannel {
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger queried = new AtomicInteger();
        final List<String> submitNumbers = new ArrayList<>();
        final List<String> queryNumbers = new ArrayList<>();
        final Map<String, PaymentResult> results = new ConcurrentHashMap<>();
        PaymentStatus next = PaymentStatus.PROCESSING;
        boolean throwAfterSubmit;
        boolean throwOnQuery;
        CallGate submitGate;

        @Override
        public PaymentResult submit(PaymentRequest request) {
            PaymentResult result;
            synchronized (this) {
                submitted.incrementAndGet();
                submitNumbers.add(request.withdrawNo());
                result = results.computeIfAbsent(request.withdrawNo(), no -> result(no, next));
            }
            if (submitGate != null) submitGate.pause();
            if (throwAfterSubmit) throw new ServiceException("Synthetic timeout after channel accepted the original idempotency key");
            return result;
        }

        @Override
        public synchronized PaymentResult query(String no) {
            queried.incrementAndGet();
            queryNumbers.add(no);
            if (throwOnQuery) throw new ServiceException("Synthetic temporary query failure");
            return results.getOrDefault(no, result(no, PaymentStatus.UNKNOWN));
        }

        @Override
        public PaymentResult handleCallback(ChannelNotification notification) {
            if (!"synthetic-signature".equals(notification.signature())) throw new ServiceException("Invalid test notification");
            String[] parts = notification.payload().split("\\|", 2);
            return result(parts[0], PaymentStatus.valueOf(parts[1]));
        }

        ChannelNotification notification(String no, PaymentStatus status) {
            return new ChannelNotification("mock", no + "|" + status.name(), "synthetic-signature");
        }

        PaymentResult result(String no, PaymentStatus status) {
            return new PaymentResult(no, "synthetic-channel-" + no, status, "Synthetic test outcome");
        }
    }
}
