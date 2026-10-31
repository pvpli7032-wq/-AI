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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * Automatic-payout acceptance using real MySQL, migrations, transaction locks and MyBatis SQL.
 * The test-only ledger implements the line 11 boundary, just as WithdrawalMySqlTest does; it
 * does not replace that line's production-ledger or combined settlement acceptance.
 * Every database is generated for this suite and is the only database this suite removes.
 */
public class AutomaticWithdrawalMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(12_800_000L);
    private static String adminUrl, mysqlUser, mysqlPassword, database;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static WithdrawalMapper mapper;

    private SqlLedgerContractFixture ledger;
    private ScriptedChannel channel;
    private ExposureService exposure;
    private RiskService risk;
    private HealthMonitorService health;
    private WithdrawalService service;
    private JSONObject policy;

    @BeforeClass
    public static void migrateFreshRealMySqlDatabase() throws Exception {
        adminUrl = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL is required for automatic-withdrawal money/SQL acceptance",
            adminUrl != null && adminUrl.startsWith("jdbc:mysql://"));
        mysqlUser = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        mysqlPassword = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line12_auto_" + UUID.randomUUID().toString().replace("-", "");
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
        assertEquals(1, count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='800' AND success=1"));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/cps/WithdrawalMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WithdrawalMapper.class);
        manager = new DataSourceTransactionManager(ds);
    }

    @AfterClass
    public static void dropOnlyTheDatabaseCreatedByThisSuite() throws Exception {
        if (database == null) return;
        assertTrue(database.matches("line12_auto_[0-9a-f]{32}"));
        try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Before
    public void configureSyntheticPolicyAndDependencies() {
        // Deliberately omit both new fields: old saved policies must default to manual review.
        policy = JSON.parseObject("""
            {"enabled":true,"channel":"mock","businessTimezone":"Asia/Shanghai",
             "maximum":"0","dailyCount":0,"recipientDailyCount":0,"monthlyAmount":"0","monthDays":"0",
             "integerOnly":false,"requireReceivedOrder":false,"realNameSingle":"0","realNameMonthly":"0",
             "reviewAmount":"0","reviewFirstCount":0,"firstCountBasis":"SUCCEEDED","identityAction":"MANUAL_REVIEW",
             "channelSingle":"0","channelDaily":"0","channelUserDaily":"0","groups":{}}
            """);
        jdbc.update("UPDATE cps_withdraw_policy SET config=?,updated_by=1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=1",
            policy.toJSONString());
        jdbc.update("INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time) "
            + "SELECT 'Synthetic automatic-withdrawal minimum','commission.min.withdraw','0.01','N','line12-test',NOW() "
            + "WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='commission.min.withdraw')");
        jdbc.update("UPDATE sys_config SET config_value='0.01' WHERE config_key='commission.min.withdraw'");
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey(any())).thenAnswer(invocation -> {
            List<String> values = jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_key=?",
                String.class, invocation.getArgument(0));
            return values.isEmpty() ? "" : values.getFirst();
        });
        risk = new RiskService(jdbc, configs);
        health = new HealthMonitorService(jdbc, configs, manager);
        exposure = mock(ExposureService.class);
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(false, false, "Synthetic exposure clear",
            money("0.00"), money("1000000.00")));
        ledger = new SqlLedgerContractFixture();
        channel = new ScriptedChannel();
        service = newService();
    }

    @Test
    public void oldConfigurationWithoutAutomaticFieldsDefaultsToManualReview() {
        long user = user("20.00");
        WithdrawalView row = service.apply(user, request("0.01", "missing-auto-fields"));
        assertManualReservation(row, user, "19.99", "0.01");
        assertEquals(0, channel.submitted.get());
        assertEquals(0, channel.queried.get());
    }

    @Test
    public void explicitDisabledSwitchOverridesAPositiveAutomaticAmount() {
        configureAuto(false, "1000.00");
        long user = user("20.00");
        WithdrawalView row = service.apply(user, request("0.01", "auto-switch-off"));
        assertManualReservation(row, user, "19.99", "0.01");
        assertThrows(ServiceException.class, () -> service.pay(row.withdrawNo(), 1L));
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void zeroAutomaticAmountDisablesAutomaticApprovalAndPayment() {
        configureAuto(true, "0.00");
        long user = user("20.00");
        WithdrawalView row = service.apply(user, request("0.01", "zero-auto-amount"));
        assertManualReservation(row, user, "19.99", "0.01");
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void missingAutomaticAmountDefaultsToZeroEvenWithSwitchOn() {
        policy.put("autoPayEnabled", true);
        savePolicy();
        long user = user("20.00");
        WithdrawalView row = service.apply(user, request("0.01", "missing-auto-amount"));
        assertManualReservation(row, user, "19.99", "0.01");
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void onlyTheAmountStrictlyBelowTheConfiguredThresholdPaysAutomatically() {
        configureAuto(true, "10.00");
        long below = user("20.00"), equal = user("20.00"), above = user("20.00");
        WithdrawalView paid = service.apply(below, request("9.99", "below-auto-amount"));
        assertEquals(WithdrawalStatus.SUCCEEDED, paid.status());
        assertSuccessfulPayment(paid.withdrawNo(), below, "10.01", "9.99");
        assertManualReservation(service.apply(equal, request("10.00", "equal-auto-amount")), equal, "10.00", "10.00");
        assertManualReservation(service.apply(above, request("10.01", "above-auto-amount")), above, "9.99", "10.01");
        assertEquals(List.of(paid.withdrawNo()), channel.submitNumbers);
        assertEquals(0, channel.queried.get());
    }

    @Test
    public void roundedPayableAmountControlsTheStrictBoundary() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        WithdrawalView row = service.apply(user, request("9.995", "rounds-to-boundary"));
        assertMoney("10.00", row.amount());
        assertManualReservation(row, user, "10.00", "10.00");
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void humanApprovalAndManualPaymentRemainAvailableAtTheAutomaticBoundary() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        String no = service.apply(user, request("10.00", "human-at-boundary")).withdrawNo();
        assertEquals(WithdrawalStatus.APPROVED, service.audit(no, true, 7L, "Synthetic manual approval").status());
        assertEquals(Long.valueOf(7L), mapper.findByNo(no).getAuditBy());
        assertAccount(user, "10.00", "10.00", "0.00");
        assertFlows(no, "WITHDRAW_APPLY");
        assertEquals(0, channel.submitted.get());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 8L).status());
        assertSuccessfulPayment(no, user, "10.00", "10.00");
        assertEquals(List.of(no), channel.submitNumbers);
    }

    @Test
    public void humanApprovalAndPaymentRemainAvailableWithAutomaticSwitchOff() {
        configureAuto(false, "10.00");
        long user = user("20.00");
        String no = service.apply(user, request("0.01", "human-with-auto-off")).withdrawNo();
        assertEquals(WithdrawalStatus.APPROVED, service.audit(no, true, 7L, "Synthetic manual approval while auto is off").status());
        assertEquals(0, channel.submitted.get());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 8L).status());
        assertSuccessfulPayment(no, user, "19.99", "0.01");
        assertEquals(List.of(no), channel.submitNumbers);
    }

    @Test
    public void aRiskReviewOverridesAutomaticPaymentEvenForOneCent() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        risk.review(user, false, true, true, "Synthetic risk evidence awaiting review", 3L);
        WithdrawalView row = service.apply(user, request("0.01", "risk-auto-override"));
        assertManualReservation(row, user, "19.99", "0.01");
        assertTrue(JSON.parseArray(mapper.findByNo(row.withdrawNo()).getReviewReasons(), String.class).contains("RISK_REVIEW"));
        assertThrows(ServiceException.class, () -> service.audit(row.withdrawNo(), true, 7L, "Cannot waive independent risk"));
        assertEquals(0, channel.submitted.get());
        assertTrue(jdbc.queryForObject("SELECT manual_review FROM cps_risk_user_state WHERE user_id=?", Boolean.class, user));
    }

    @Test
    public void aRiskBlockRejectsTheApplicationWithoutReservingMoney() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        risk.review(user, true, true, true, "Synthetic withdrawal block", 3L);
        assertThrows(ServiceException.class, () -> service.apply(user, request("0.01", "risk-block-auto")));
        assertNoApplication(user, "20.00");
    }

    @Test
    public void exposureManualActionOverridesAutoButPermitsAnExplicitHumanDecision() {
        configureAuto(true, "10.00");
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(false, true, "Synthetic exposure at limit",
            money("100.00"), money("100.00")));
        long user = user("20.00");
        WithdrawalView row = service.apply(user, request("0.01", "exposure-review-auto"));
        assertManualReservation(row, user, "19.99", "0.01");
        assertTrue(JSON.parseArray(mapper.findByNo(row.withdrawNo()).getReviewReasons(), String.class).contains("EXPOSURE_LIMIT_REACHED"));
        assertEquals(0, channel.submitted.get());
        assertEquals(WithdrawalStatus.APPROVED, service.audit(row.withdrawNo(), true, 7L, "Synthetic exposure explicitly accepted").status());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(row.withdrawNo(), 8L).status());
        assertSuccessfulPayment(row.withdrawNo(), user, "19.99", "0.01");
        assertEquals(List.of(row.withdrawNo()), channel.submitNumbers);
    }

    @Test
    public void exposureRejectActionRejectsEvenTheSmallestAutomaticAmount() {
        configureAuto(true, "10.00");
        when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(true, true, "Synthetic exposure withdrawal prohibited",
            money("100.00"), money("100.00")));
        long user = user("20.00");
        assertThrows(ServiceException.class, () -> service.apply(user, request("0.01", "exposure-block-auto")));
        assertNoApplication(user, "20.00");
    }

    @Test
    public void existingReviewAmountFirstCountGroupAndIdentityGatesStillOverrideAuto() {
        configureAuto(true, "10.00");
        JSONObject base = JSON.parseObject(policy.toJSONString());
        for (String gate : List.of("AMOUNT", "FIRST", "GROUP", "IDENTITY")) {
            policy = JSON.parseObject(base.toJSONString());
            switch (gate) {
                case "AMOUNT" -> policy.put("reviewAmount", "0.01");
                case "FIRST" -> policy.put("reviewFirstCount", 1);
                case "GROUP" -> policy.put("groups", JSON.parseObject("""
                    {"default":[{"metric":"AMOUNT","comparison":"GTE","threshold":"0.01"}]}
                    """));
                case "IDENTITY" -> policy.put("realNameSingle", "0.01");
                default -> throw new AssertionError(gate);
            }
            savePolicy();
            long user = user("20.00");
            assertManualReservation(service.apply(user, request("0.01", "legacy-gate-" + gate)), user, "19.99", "0.01");
        }
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void repeatedConcurrentApplicationUsesOneNumberOneSubmissionAndOneSettlement() throws Exception {
        configureAuto(true, "10.00");
        long user = user("20.00");
        List<WithdrawalView> responses = concurrently(12, i -> service.apply(user, request("9.99", "same-auto-request")));
        assertEquals(1, responses.stream().map(WithdrawalView::withdrawNo).distinct().count());
        String no = responses.getFirst().withdrawNo();
        assertEquals(1, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
        assertEquals(List.of(no), channel.submitNumbers);
        assertSuccessfulPayment(no, user, "10.01", "9.99");
        assertEquals(WithdrawalStatus.SUCCEEDED, service.apply(user, request("9.99", "same-auto-request")).status());
        assertThrows(ServiceException.class, () -> service.apply(user, request("9.98", "same-auto-request")));
        assertEquals(1, channel.submitted.get());
        assertFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
    }

    @Test
    public void concurrentDistinctAutomaticApplicationsCannotSpendTheSameBalanceTwice() throws Exception {
        configureAuto(true, "11.00");
        long user = user("50.00");
        List<WithdrawalView> results = concurrently(16, i -> {
            try { return service.apply(user, request("10.00", "different-auto-" + i)); }
            catch (ServiceException rejected) {
                assertTrue(rejected.getMessage(), rejected.getMessage().contains("余额"));
                return null;
            }
        });
        List<WithdrawalView> paid = results.stream().filter(row -> row != null).toList();
        assertEquals(5, paid.size());
        assertEquals(5, paid.stream().map(WithdrawalView::withdrawNo).distinct().count());
        assertEquals(5, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=? AND status='SUCCEEDED'", user));
        assertEquals(5, channel.submitted.get());
        assertEquals(5, channel.submitNumbers.stream().distinct().count());
        assertEquals(10, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", user));
        assertAccount(user, "0.00", "0.00", "50.00");
        for (WithdrawalView row : paid) {
            assertEquals(WithdrawalStatus.SUCCEEDED, row.status());
            assertMoney("0.00", row.fee());
            assertFlows(row.withdrawNo(), "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        }
    }

    @Test
    public void concurrentManualPayOnlyQueriesAnAlreadyClaimedAutomaticSubmission() throws Exception {
        configureAuto(true, "10.00");
        long user = user("20.00");
        CallGate submitting = new CallGate();
        CallGate querying = new CallGate(12);
        channel.submitGate = submitting;
        channel.queryGate = querying;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WithdrawalView> automatic = pool.submit(() -> service.apply(user, request("9.99", "auto-vs-manual-pay")));
            submitting.awaitEntered();
            String no = withdrawalNumber(user);
            assertEquals("SUBMITTING", status(no));
            assertAccount(user, "10.01", "9.99", "0.00");
            assertFlows(no, "WITHDRAW_APPLY");
            assertEquals(List.of(no), channel.submitNumbers);
            Future<List<WithdrawalView>> followerBatch = pool.submit(() -> concurrently(12, i -> service.pay(no, 8L)));
            querying.awaitEntered();
            assertEquals(12, channel.queried.get());
            assertEquals(List.of(no), channel.queryNumbers.stream().distinct().toList());
            assertEquals("SUBMITTING", status(no));
            assertAccount(user, "10.01", "9.99", "0.00");
            querying.open();
            List<WithdrawalView> followers = followerBatch.get(30, TimeUnit.SECONDS);
            for (WithdrawalView follower : followers) assertEquals(WithdrawalStatus.SUCCEEDED, follower.status());
            assertEquals(1, channel.submitted.get());
            assertEquals(12, channel.queried.get());
            assertEquals(List.of(no), channel.queryNumbers.stream().distinct().toList());
            submitting.open();
            assertEquals(WithdrawalStatus.SUCCEEDED, automatic.get(30, TimeUnit.SECONDS).status());
            assertSuccessfulPayment(no, user, "10.01", "9.99");
            assertEquals(1, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? AND action='SUBMIT'", no));
        } finally {
            submitting.open();
            querying.open();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void disablingAutoAfterReservationCommitPreventsAutomaticSubmission() throws Exception {
        configureAuto(true, "10.00");
        HeldApplication held = changeAfterReservationCommit(user -> configureAuto(false, "10.00"));
        assertManualReservation(held.row(), held.user(), "10.01", "9.99");
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void loweringTheAutoThresholdToTheAmountAfterCommitPreventsAutomaticSubmission() throws Exception {
        configureAuto(true, "10.00");
        HeldApplication held = changeAfterReservationCommit(user -> configureAuto(true, "9.99"));
        assertManualReservation(held.row(), held.user(), "10.01", "9.99");
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void newRiskAfterReservationCommitInvalidatesAutomaticApproval() throws Exception {
        configureAuto(true, "10.00");
        HeldApplication held = changeAfterReservationCommit(user ->
            risk.review(user, false, true, true, "Synthetic risk discovered before dispatch", 3L));
        assertManualReservation(held.row(), held.user(), "10.01", "9.99");
        assertTrue(JSON.parseArray(mapper.findByNo(held.row().withdrawNo()).getReviewReasons(), String.class).contains("RISK_REVIEW"));
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void exposureReachingItsLimitAfterCommitInvalidatesAutomaticApproval() throws Exception {
        configureAuto(true, "10.00");
        HeldApplication held = changeAfterReservationCommit(user ->
            when(exposure.evaluateWithdrawal(any())).thenReturn(new ExposureDecision(false, true, "Synthetic exposure just reached limit",
                money("100.00"), money("100.00"))));
        assertManualReservation(held.row(), held.user(), "10.01", "9.99");
        assertTrue(JSON.parseArray(mapper.findByNo(held.row().withdrawNo()).getReviewReasons(), String.class).contains("EXPOSURE_LIMIT_REACHED"));
        assertEquals(0, channel.submitted.get());
    }

    @Test
    public void aRolledBackReservationCannotDispatchAnAutomaticPayment() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        ledger.failAfterSql = true;
        assertThrows(ServiceException.class, () -> service.apply(user, request("9.99", "auto-ledger-rollback")));
        assertNoApplication(user, "20.00");
    }

    @Test
    public void unknownAutomaticSubmissionRetainsItsNumberAndOnlyQueriesAfterTheSwitchIsDisabled() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        channel.throwAfterSubmit = true;
        WithdrawalView row = service.apply(user, request("9.99", "auto-unknown-original-key"));
        assertEquals(WithdrawalStatus.UNKNOWN, row.status());
        assertAccount(user, "10.01", "9.99", "0.00");
        assertFlows(row.withdrawNo(), "WITHDRAW_APPLY");
        configureAuto(false, "0.00");
        assertEquals(0, service.processAutomatic());
        assertEquals(List.of(row.withdrawNo()), channel.submitNumbers);
        assertEquals(0, channel.queried.get());
        channel.throwAfterSubmit = false;
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(row.withdrawNo(), 8L).status());
        assertEquals(List.of(row.withdrawNo()), channel.submitNumbers);
        assertEquals(List.of(row.withdrawNo()), channel.queryNumbers);
        assertEquals(WithdrawalStatus.SUCCEEDED, service.query(row.withdrawNo()).status());
        assertSuccessfulPayment(row.withdrawNo(), user, "10.01", "9.99");
        assertEquals(1, channel.submitted.get());
        assertEquals(1, channel.queried.get());
    }

    @Test
    public void definitiveAutomaticFailureReturnsTheExactAmountOnceWithoutAnyFee() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        channel.next = PaymentStatus.FAILED;
        WithdrawalView row = service.apply(user, request("9.99", "automatic-failed"));
        assertEquals(WithdrawalStatus.FAILED, row.status());
        assertMoney("0.00", row.fee());
        assertAccount(user, "20.00", "0.00", "0.00");
        assertFlows(row.withdrawNo(), "WITHDRAW_APPLY", "WITHDRAW_RETURN");
        assertEquals(WithdrawalStatus.FAILED, service.apply(user, request("9.99", "automatic-failed")).status());
        assertEquals(WithdrawalStatus.FAILED, service.pay(row.withdrawNo(), 8L).status());
        assertEquals(List.of(row.withdrawNo()), channel.submitNumbers);
        assertEquals(0, channel.queried.get());
        assertAccount(user, "20.00", "0.00", "0.00");
        assertFlows(row.withdrawNo(), "WITHDRAW_APPLY", "WITHDRAW_RETURN");
    }

    @Test
    public void aNewServiceRecoversCommittedAutomaticApprovalAfterTheOriginalProcessStops() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        String no = stopAfterReservationCommit(user, "recovery-after-commit");
        assertEquals("APPROVED", status(no));
        assertTrue(mapper.findByNo(no).getAutoPayEligible());
        assertNull(mapper.findByNo(no).getSubmittedAt());
        assertAccount(user, "10.01", "9.99", "0.00");
        assertFlows(no, "WITHDRAW_APPLY");
        assertEquals(0, channel.submitted.get());

        service = newService();
        assertEquals(1, service.processAutomatic());
        assertSuccessfulPayment(no, user, "10.01", "9.99");
        assertFalse(mapper.findByNo(no).getAutoPayEligible());
        assertEquals(List.of(no), channel.submitNumbers);
        assertEquals(0, service.processAutomatic());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.apply(user, request("9.99", "recovery-after-commit")).status());
        assertEquals(1, channel.submitted.get());
        assertEquals(0, channel.queried.get());
    }

    @Test
    public void concurrentAutomaticWorkersRecoverEachCommittedRequestExactlyOnce() throws Exception {
        configureAuto(true, "10.00");
        List<Long> users = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long user = user("20.00");
            users.add(user);
            numbers.add(stopAfterReservationCommit(user, "parallel-recovery-" + i));
        }
        assertEquals(0, channel.submitted.get());
        concurrently(8, i -> service.processAutomatic());
        assertEquals(4, channel.submitted.get());
        assertEquals(numbers.stream().sorted().toList(), channel.submitNumbers.stream().sorted().toList());
        assertEquals(0, service.processAutomatic());
        for (int i = 0; i < users.size(); i++) {
            assertSuccessfulPayment(numbers.get(i), users.get(i), "10.01", "9.99");
            assertFalse(mapper.findByNo(numbers.get(i)).getAutoPayEligible());
            assertEquals(1, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? AND action='SUBMIT'", numbers.get(i)));
        }
    }

    @Test
    public void oneResultPostingFailureDoesNotStopTheAutomaticQueueAndRecoversByQueryOnly() {
        configureAuto(true, "10.00");
        long firstUser = user("20.00"), secondUser = user("20.00");
        String first = stopAfterReservationCommit(firstUser, "worker-result-ledger-failure");
        String second = stopAfterReservationCommit(secondUser, "worker-next-after-failure");
        ledger.failAfterSql = true;
        assertEquals(2, service.processAutomatic());
        assertEquals("SUBMITTING", status(first));
        assertFalse(mapper.findByNo(first).getAutoPayEligible());
        assertAccount(firstUser, "10.01", "9.99", "0.00");
        assertFlows(first, "WITHDRAW_APPLY");
        assertEquals(1, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? AND action='AUTO_PAY_RETRY'", first));
        assertSuccessfulPayment(second, secondUser, "10.01", "9.99");
        assertEquals(List.of(first, second), channel.submitNumbers);
        assertEquals(0, service.processAutomatic());
        assertEquals(1, service.queryPending());
        assertSuccessfulPayment(first, firstUser, "10.01", "9.99");
        assertEquals(List.of(first), channel.queryNumbers);
        assertEquals(List.of(first, second), channel.submitNumbers);
        assertEquals(2, channel.submitted.get());
    }

    @Test
    public void automaticWorkerDoesNotConsumeHumanApprovedRequestsAfterTheSwitchIsEnabled() {
        configureAuto(false, "10.00");
        long user = user("20.00");
        String no = service.apply(user, request("0.01", "human-approved-before-enabling")).withdrawNo();
        assertEquals(WithdrawalStatus.APPROVED, service.audit(no, true, 7L, "Synthetic human approval").status());
        assertFalse(mapper.findByNo(no).getAutoPayEligible());
        configureAuto(true, "10.00");
        assertEquals(0, service.processAutomatic());
        assertEquals(WithdrawalStatus.APPROVED, service.payAutomatically(no).status());
        assertEquals("APPROVED", status(no));
        assertEquals(Long.valueOf(7L), mapper.findByNo(no).getAuditBy());
        assertEquals(0, channel.submitted.get());
        assertEquals(0, channel.queried.get());
        assertAccount(user, "19.99", "0.01", "0.00");
        assertFlows(no, "WITHDRAW_APPLY");
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 8L).status());
        assertSuccessfulPayment(no, user, "19.99", "0.01");
    }

    @Test
    public void explicitHumanApprovalTakesOwnershipOfAQueuedAutomaticRequest() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        String no = stopAfterReservationCommit(user, "human-takes-automatic-request");
        assertTrue(mapper.findByNo(no).getAutoPayEligible());
        assertEquals(WithdrawalStatus.APPROVED, service.audit(no, true, 7L, "Synthetic operator assumes this request").status());
        assertFalse(mapper.findByNo(no).getAutoPayEligible());
        assertEquals(Long.valueOf(7L), mapper.findByNo(no).getAuditBy());
        assertEquals(0, service.processAutomatic());
        assertEquals(WithdrawalStatus.APPROVED, service.payAutomatically(no).status());
        assertEquals(0, channel.submitted.get());
        assertAccount(user, "10.01", "9.99", "0.00");
        assertFlows(no, "WITHDRAW_APPLY");
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(no, 8L).status());
        assertSuccessfulPayment(no, user, "10.01", "9.99");
    }

    @Test
    public void aRejectedQueuedRequestCannotBeRecoveredIntoAnAutomaticPayment() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        String no = stopAfterReservationCommit(user, "reject-queued-auto-request");
        assertEquals(WithdrawalStatus.REJECTED, service.audit(no, false, 7L, "Synthetic queued request rejected").status());
        assertFalse(mapper.findByNo(no).getAutoPayEligible());
        assertEquals(0, service.processAutomatic());
        assertEquals(WithdrawalStatus.REJECTED, service.payAutomatically(no).status());
        assertEquals(0, channel.submitted.get());
        assertEquals(0, channel.queried.get());
        assertAccount(user, "20.00", "0.00", "0.00");
        assertFlows(no, "WITHDRAW_APPLY", "WITHDRAW_RETURN");
    }

    @Test
    public void clearingRiskDoesNotAutomaticallyApproveAPreviouslyHeldApplication() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        risk.review(user, false, true, true, "Synthetic risk evidence awaiting review", 3L);
        WithdrawalView row = service.apply(user, request("0.01", "risk-clear-not-auto-approval"));
        assertManualReservation(row, user, "19.99", "0.01");
        risk.review(user, false, false, false, "Synthetic independent risk clearance", 3L);
        assertEquals(0, service.processAutomatic());
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, service.payAutomatically(row.withdrawNo()).status());
        assertManualReservation(service.query(row.withdrawNo()), user, "19.99", "0.01");
        assertEquals(0, channel.submitted.get());
        assertEquals(0, channel.queried.get());
        assertEquals(WithdrawalStatus.APPROVED, service.audit(row.withdrawNo(), true, 7L, "Synthetic new human approval").status());
        assertEquals(WithdrawalStatus.SUCCEEDED, service.pay(row.withdrawNo(), 8L).status());
        assertSuccessfulPayment(row.withdrawNo(), user, "19.99", "0.01");
    }

    @Test
    public void recoveryRechecksTheCurrentSwitchAndDoesNotUseTheOldEnabledSnapshot() {
        configureAuto(true, "10.00");
        long user = user("20.00");
        String no = stopAfterReservationCommit(user, "disabled-before-recovery");
        assertTrue(JSON.parseObject(mapper.findByNo(no).getPolicySnapshot()).getBooleanValue("autoPayEnabled"));
        configureAuto(false, "10.00");
        assertEquals(1, service.processAutomatic());
        assertManualReservation(service.query(no), user, "10.01", "9.99");
        assertEquals(0, channel.submitted.get());
        assertEquals(0, service.processAutomatic());
    }

    private WithdrawalService newService() {
        return new WithdrawalService(mapper, provider(LedgerService.class, ledger), provider(WithdrawChannel.class, channel),
            risk, exposure, health, manager);
    }

    /** An Error ends the caller after COMMIT without entering automatic dispatch or its recovery handler. */
    private String stopAfterReservationCommit(long user, String requestKey) {
        ledger.afterApplicationCommit.set(() -> { throw new CommittedApplicationCrash(); });
        assertThrows(CommittedApplicationCrash.class, () -> service.apply(user, request("9.99", requestKey)));
        String no = withdrawalNumber(user);
        assertEquals("APPROVED", status(no));
        assertTrue(mapper.findByNo(no).getAutoPayEligible());
        assertNull(mapper.findByNo(no).getSubmittedAt());
        assertFlows(no, "WITHDRAW_APPLY");
        return no;
    }

    private static final class CommittedApplicationCrash extends Error {
        private static final long serialVersionUID = 1L;
    }

    private void configureAuto(boolean enabled, String belowAmount) {
        policy.put("autoPayEnabled", enabled);
        policy.put("autoPayBelowAmount", belowAmount);
        savePolicy();
    }

    private void savePolicy() { service.configure(policy.toJSONString(), 1L); }

    private record HeldApplication(long user, WithdrawalView row) {}

    /** Pauses after the real reservation COMMIT, before apply can claim its automatic submission. */
    private HeldApplication changeAfterReservationCommit(LongConsumer change) throws Exception {
        long user = user("20.00");
        CallGate committed = new CallGate();
        ledger.afterApplicationCommit.set(committed::pause);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<WithdrawalView> application = pool.submit(() -> service.apply(user, request("9.99", "after-commit")));
            committed.awaitEntered();
            String no = withdrawalNumber(user);
            assertEquals("APPROVED", status(no));
            assertAccount(user, "10.01", "9.99", "0.00");
            assertFlows(no, "WITHDRAW_APPLY");
            assertEquals(0, channel.submitted.get());
            change.accept(user);
            committed.open();
            return new HeldApplication(user, application.get(30, TimeUnit.SECONDS));
        } finally {
            committed.open();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (value != null) beans.addBean("synthetic", value);
        return beans.getBeanProvider(type);
    }

    private static WithdrawalApplyRequest request(String amount, String key) {
        return new WithdrawalApplyRequest(money(amount), "line12-auto-" + key);
    }

    private static long user(String balance) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag,create_time) VALUES (?,?,?,'0','0',NOW())",
            id, "line12-auto-test-" + id, "synthetic auto payee " + id);
        jdbc.update("INSERT INTO sys_agent_commission_account(user_id,balance,status,create_time) VALUES (?,?,'0',NOW())", id, money(balance));
        String account = "synthetic-auto-account-" + id;
        jdbc.update("INSERT INTO cps_withdraw_recipient(user_id,channel,recipient_name,recipient_account,recipient_hash,verified,verification_ref,updated_by,updated_at) "
            + "VALUES (?,'mock',?,?,?,1,'synthetic-auto-verification',1,CURRENT_TIMESTAMP(3))", id, "synthetic auto payee " + id, account, hash(account));
        return id;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static String withdrawalNumber(long user) {
        return jdbc.queryForObject("SELECT withdraw_no FROM cps_withdrawal WHERE user_id=?", String.class, user);
    }

    private static String status(String no) {
        return jdbc.queryForObject("SELECT status FROM cps_withdrawal WHERE withdraw_no=?", String.class, no);
    }

    private static void assertManualReservation(WithdrawalView row, long user, String available, String reserved) {
        assertEquals(WithdrawalStatus.MANUAL_REVIEW, row.status());
        assertEquals("MANUAL_REVIEW", status(row.withdrawNo()));
        assertMoney("0.00", row.fee());
        WithdrawalRecord stored = mapper.findByNo(row.withdrawNo());
        assertFalse(stored.getAutoPayEligible());
        assertNull(stored.getAuditBy());
        assertNull(stored.getAuditAt());
        assertNull(stored.getSubmittedAt());
        assertNull(stored.getCompletedAt());
        assertAccount(user, available, reserved, "0.00");
        assertFlows(row.withdrawNo(), "WITHDRAW_APPLY");
    }

    private static void assertSuccessfulPayment(String no, long user, String available, String paid) {
        assertEquals("SUCCEEDED", status(no));
        WithdrawalRecord row = mapper.findByNo(no);
        assertMoney("0.00", row.getFee());
        assertNotNull(row.getSubmittedAt());
        assertNotNull(row.getCompletedAt());
        assertAccount(user, available, "0.00", paid);
        assertFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
    }

    private void assertNoApplication(long user, String available) {
        assertEquals(0, count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?", user));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", user));
        assertAccount(user, available, "0.00", "0.00");
        assertEquals(0, channel.submitted.get());
        assertEquals(0, channel.queried.get());
    }

    private static void assertFlows(String no, String... types) {
        List<String> expected = List.of(types).stream().sorted().toList();
        assertEquals(expected, jdbc.queryForList("SELECT business_type FROM sys_commission_flow WHERE business_id=? ORDER BY business_type", String.class, no));
        assertEquals(expected.size(), count("SELECT COUNT(DISTINCT idempotency_key) FROM sys_commission_flow WHERE business_id=?", no));
        for (String type : expected) {
            assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE business_id=? AND business_type=? AND idempotency_key=?",
                no, type, type + ":" + no));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow f JOIN cps_withdrawal w ON w.withdraw_no=f.business_id "
            + "WHERE w.withdraw_no=? AND f.amount<>w.amount", no));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_withdrawal WHERE withdraw_no=? AND fee<>0", no));
    }

    private static long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
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

    private static <T> List<T> concurrently(int count, ParallelAction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    return action.run(index);
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(40, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static final class CallGate {
        private final CountDownLatch entered, released = new CountDownLatch(1);
        CallGate() { this(1); }
        CallGate(int entrants) { entered = new CountDownLatch(entrants); }
        void pause() {
            entered.countDown();
            try { assertTrue("Synthetic operation was not released", released.await(40, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic operation interrupted", interrupted);
            }
        }
        void awaitEntered() throws InterruptedException {
            assertTrue("Synthetic operation did not reach the controlled boundary", entered.await(20, TimeUnit.SECONDS));
        }
        void open() { released.countDown(); }
    }

    /** Test-only ledger contract; all money mutations in this suite pass through this boundary. */
    private static final class SqlLedgerContractFixture implements LedgerService {
        final AtomicReference<Runnable> afterApplicationCommit = new AtomicReference<>();
        boolean failAfterSql;

        @Override
        public LedgerPosting post(PostingCommand command) {
            assertTrue("Reservation/result and ledger must commit together", TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(command.debitAccount(), command.account());
            assertTrue(command.amount().signum() > 0);
            Map<String, Object> account = jdbc.queryForMap("SELECT balance,withdrawing_amount,withdrawn "
                + "FROM sys_agent_commission_account WHERE user_id=? FOR UPDATE", command.userId());
            List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,balance_after FROM sys_commission_flow WHERE idempotency_key=?", command.businessKey());
            if (!existing.isEmpty()) return new LedgerPosting(((Number) existing.getFirst().get("id")).longValue(),
                command.businessKey(), (BigDecimal) existing.getFirst().get("balance_after"), true);
            BigDecimal available = (BigDecimal) account.get("balance"), before = available;
            BigDecimal withdrawing = (BigDecimal) account.get("withdrawing_amount"), paid = (BigDecimal) account.get("withdrawn");
            if (command.debitAccount() == AccountType.USER_AVAILABLE) available = available.subtract(command.amount());
            if (command.creditAccount() == AccountType.USER_AVAILABLE) available = available.add(command.amount());
            if (command.debitAccount() == AccountType.USER_WITHDRAWING) withdrawing = withdrawing.subtract(command.amount());
            if (command.creditAccount() == AccountType.USER_WITHDRAWING) withdrawing = withdrawing.add(command.amount());
            if (command.creditAccount() == AccountType.PAYOUT) paid = paid.add(command.amount());
            assertTrue("Cannot consume another request's reservation", withdrawing.signum() >= 0);
            assertTrue("The account lock must prevent over-reservation", available.signum() >= 0);
            jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,balance_before,balance_after,related_order_id,"
                + "business_type,business_id,account,debit_account,credit_account,idempotency_key,description,create_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))", command.userId(), command.type(), command.amount(),
                before, available, command.orderLineId() == null ? null : command.orderLineId().toString(), command.businessType(),
                command.businessId(), command.account().name(), command.debitAccount().name(), command.creditAccount().name(),
                command.businessKey(), command.reason());
            jdbc.update("UPDATE sys_agent_commission_account SET balance=?,withdrawing_amount=?,withdrawn=?,version=version+1 WHERE user_id=?",
                available, withdrawing, paid, command.userId());
            if (failAfterSql) {
                failAfterSql = false;
                throw new ServiceException("Synthetic ledger failure after both SQL writes");
            }
            if ("WITHDRAW_APPLY".equals(command.businessType())) {
                Runnable afterCommit = afterApplicationCommit.getAndSet(null);
                if (afterCommit != null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { afterCommit.run(); }
                });
            }
            Long id = jdbc.queryForObject("SELECT id FROM sys_commission_flow WHERE idempotency_key=?", Long.class, command.businessKey());
            return new LedgerPosting(id, command.businessKey(), available, false);
        }
    }

    /** Each submit checks that the durable claim and reservation are visible before channel I/O. */
    private static final class ScriptedChannel implements WithdrawChannel {
        final AtomicInteger submitted = new AtomicInteger(), queried = new AtomicInteger();
        final List<String> submitNumbers = new CopyOnWriteArrayList<>(), queryNumbers = new CopyOnWriteArrayList<>();
        final Map<String, PaymentResult> results = new ConcurrentHashMap<>();
        PaymentStatus next = PaymentStatus.SUCCESS;
        boolean throwAfterSubmit;
        CallGate submitGate;
        CallGate queryGate;

        @Override
        public PaymentResult submit(PaymentRequest request) {
            assertFalse("External payout must not run inside the reservation/claim transaction", TransactionSynchronizationManager.isActualTransactionActive());
            // Another caller may query the persisted claim before this callback begins; the
            // immutable SUBMIT audit proves the exact initial state without forbidding that race.
            assertEquals(1, count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? "
                + "AND action='SUBMIT' AND to_status='SUBMITTING'", request.withdrawNo()));
            assertFlows(request.withdrawNo(), "WITHDRAW_APPLY");
            WithdrawalRecord row = mapper.findByNo(request.withdrawNo());
            assertMoney(request.amount().toPlainString(), row.getAmount());
            assertNotNull(row.getSubmittedAt());
            submitted.incrementAndGet();
            submitNumbers.add(request.withdrawNo());
            PaymentResult result = results.computeIfAbsent(request.withdrawNo(), no -> result(no, next));
            if (submitGate != null) submitGate.pause();
            if (throwAfterSubmit) throw new ServiceException("Synthetic timeout after accepting the original idempotency key");
            return result;
        }

        @Override
        public PaymentResult query(String no) {
            queried.incrementAndGet();
            queryNumbers.add(no);
            if (queryGate != null) queryGate.pause();
            return results.getOrDefault(no, result(no, PaymentStatus.UNKNOWN));
        }

        @Override
        public PaymentResult handleCallback(ChannelNotification notification) {
            throw new UnsupportedOperationException("This suite exercises automatic dispatch and queries, not signed callbacks");
        }

        private static PaymentResult result(String no, PaymentStatus status) {
            return new PaymentResult(no, "synthetic-auto-channel-" + no, status, "Synthetic automatic-payout outcome");
        }
    }
}
