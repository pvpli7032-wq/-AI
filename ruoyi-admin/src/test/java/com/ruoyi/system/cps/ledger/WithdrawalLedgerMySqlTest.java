package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import com.ruoyi.system.cps.settlement.PaymentInvitationSnapshotPort;
import com.ruoyi.system.cps.settlement.PaymentInvitationSnapshotService;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.withdraw.*;
import com.ruoyi.system.cps.mapper.WithdrawalMapper;
import com.ruoyi.system.cps.recon.ExposureService;
import com.ruoyi.system.cps.risk.*;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** Re-runs the unchanged line 12 acceptance assertions with the production ledger,
 * NOT its SQL contract fixture. Migrations and data live only in a fresh UUID database.
 */
public class WithdrawalLedgerMySqlTest {
    private WithdrawalMySqlTest acceptance;
    private JdbcTemplate jdbc;
    private LedgerService ledger;
    private TransactionTemplate tx;
    private CommissionCalculateServiceImpl accounts;

    @BeforeClass public static void migrate() throws Exception {
        WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
    }
    @AfterClass public static void cleanup() throws Exception {
        WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
    }
    @Before public void wireProductionLedger() throws Exception {
        acceptance = new WithdrawalMySqlTest();
        acceptance.configureSyntheticPolicyAndDependencies();
        jdbc = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "jdbc");
        var manager = (DataSourceTransactionManager) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "manager");
        tx = new TransactionTemplate(manager);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionFlowMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionConfigMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        accounts = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(accounts, "orderCommissionMapper", sessions.getMapper(com.ruoyi.system.mapper.SysOrderCommissionMapper.class));
        ReflectionTestUtils.setField(accounts, "flowMapper", sessions.getMapper(com.ruoyi.system.mapper.SysCommissionFlowMapper.class));
        ReflectionTestUtils.setField(accounts, "commissionConfigMapper", sessions.getMapper(com.ruoyi.system.mapper.SysCommissionConfigMapper.class));
        ReflectionTestUtils.setField(accounts, "accountMapper", sessions.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(accounts, "ledgerJdbc", jdbc);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(new DefaultLedgerService(accounts));
        proxy.setInterfaces(LedgerService.class);
        proxy.addAdvice(interceptor);
        ledger = (LedgerService) proxy.getProxy();
        var realService = new WithdrawalService(
            (WithdrawalMapper) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "mapper"),
            provider(LedgerService.class, ledger),
            provider(WithdrawChannel.class, (WithdrawChannel) ReflectionTestUtils.getField(acceptance, "channel")),
            (RiskService) ReflectionTestUtils.getField(acceptance, "risk"),
            (ExposureService) ReflectionTestUtils.getField(acceptance, "exposure"),
            (HealthMonitorService) ReflectionTestUtils.getField(acceptance, "health"), manager);
        ReflectionTestUtils.setField(acceptance, "service", realService);
    }
    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        var beans = new StaticListableBeanFactory(); beans.addBean("production", value);
        return beans.getBeanProvider(type);
    }
    @Test public void concurrentApplications() throws Exception {
        acceptance.concurrentApplicationsCannotReserveMoreThanTheUsersBalance();
    }
    @Test public void concurrentRequestReplay() throws Exception {
        acceptance.concurrentSameRequestKeyIsOneReservationAndDifferentAmountIsRejected();
    }
    @Test public void duplicateSuccess() { acceptance.approvedIsDistinctFromPaidAndDuplicateSuccessNeverPaysTwice(); }
    @Test public void concurrentPayment() throws Exception { acceptance.concurrentPayQueriesThePersistedSubmissionAndPaysOnlyOnce(); }
    @Test public void paymentWinsRejectionRace() throws Exception {
        acceptance.paymentClaimWinningTheAuditRacePreventsARefundWhileTheChannelIsInFlight();
    }
    @Test public void timeoutOnlyQueries() { acceptance.submissionTimeoutUsesOriginalNumberAndOnlyQueriesAfterward(); }
    @Test public void failedPaymentReturnsOnce() { acceptance.definitiveFailureRefundsOnceAndConflictingLateSuccessCannotMutateMoney(); }
    @Test public void lateFailurePreservesPaidMoney() throws Exception { acceptance.lateFailureCannotReverseAnAlreadySuccessfulPayout(); }
    @Test public void processingRetainsMoney() { acceptance.processingAndFailedQueriesKeepReservationUntilAConclusiveResult(); }
    @Test public void reviewRejectionReturnsOnce() { acceptance.anyReviewGateRequiresManualReviewAndRejectionRefundsOnlyOnce(); }
    @Test public void bannedDebtExposureDenied() { acceptance.negativeBalanceBannedUserAndExposureBlockAllRejectWithoutReservations(); }
    @Test public void confirmationTimeoutRetainsMoney() { acceptance.awaitingConfirmationAndConfirmationTimeoutDoNotReleaseFunds(); }

    @Test public void mismatchReplayAndCompetingTerminalCannotConsumeOtherReservations() {
        long user = 99110001L;
        jdbc.update("INSERT INTO sys_agent_commission_account(user_id,balance,status) VALUES (?,20,'0')", user);
        var first = command(user, "test-withdraw-a", "WITHDRAW_APPLY", AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "10");
        ledger.post(first);
        assertTrue(ledger.post(first).replayed());
        assertThrows(ServiceException.class, () -> ledger.post(command(user, "test-withdraw-a", "WITHDRAW_APPLY",
            AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "9")));
        ledger.post(command(user, "test-withdraw-b", "WITHDRAW_APPLY", AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "10"));
        ledger.post(command(user, "test-withdraw-a", "WITHDRAW_SUCCESS", AccountType.USER_WITHDRAWING, AccountType.PAYOUT, "10"));
        assertThrows(ServiceException.class, () -> ledger.post(command(user, "test-withdraw-a", "WITHDRAW_RETURN",
            AccountType.USER_WITHDRAWING, AccountType.USER_AVAILABLE, "10")));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject(
            "SELECT withdrawing_amount FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user)));
    }
    @Test public void outerRollbackUndoesFlowAndAccountTogether() {
        long user = 99110002L;
        jdbc.update("INSERT INTO sys_agent_commission_account(user_id,balance,status) VALUES (?,10,'0')", user);
        assertThrows(ServiceException.class, () -> tx.execute(s -> {
            ledger.post(command(user, "rollback-real-ledger", "WITHDRAW_APPLY", AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "10"));
            throw new ServiceException("synthetic failure after production ledger");
        }));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", Long.class, user));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject("SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user)));
    }
    @Test public void ordinaryUserNeedsNoAgentApplicationAndNoInviterIsValid() {
        long buyer = 99110003L;
        ordinaryUser(buyer, null);
        rates();
        var order = tx.execute(s -> accounts.calculateCommission("ordinary-no-agent", "tbk", "synthetic-item", "Synthetic", new BigDecimal("100"),
            new BigDecimal("20"), BigDecimal.ZERO, buyer, "2026-01-01 12:00:00"));
        assertEquals("0", order.getHasReferrer());
        assertNull(order.getReferrerUserId());
        assertEquals(0, new BigDecimal("10").compareTo(order.getSelfCommissionAmount()));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", Long.class, buyer));
    }
    @Test public void changedInviterDoesNotChangeOrderSnapshotOrRefundRecipient() {
        long buyer = 99110004L, original = 99110005L, replacement = 99110006L;
        ordinaryUser(original, null); ordinaryUser(replacement, null); ordinaryUser(buyer, original); rates();
        // The legacy API has no paidAt. Supply an upstream persisted order whose inviter
        // was verified against explicit payment evidence; never infer it from today's relation.
        persistVerifiedPaymentOrder(buyer, original);

        var order = tx.execute(s -> accounts.calculateCommission("snapshot-no-agent", "tbk", "synthetic-item", "Synthetic", new BigDecimal("100"),
            new BigDecimal("20"), BigDecimal.ZERO, buyer, "2026-01-01 12:00:00"));
        assertEquals(Long.valueOf(original), order.getReferrerUserId());
        assertEquals(0, new BigDecimal("2").compareTo(order.getPromotionCommissionAmount()));
        jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=? WHERE user_id=?", replacement, buyer);
        tx.execute(s -> accounts.settleOrder(order.getId(), new BigDecimal("18")));
        assertEquals(0, new BigDecimal("1.80").compareTo(jdbc.queryForObject(
            "SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, original)));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", Long.class, replacement));
        tx.execute(s -> accounts.refundOrder(order.getId()));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
            "SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, original)));
    }
    @Test public void paidOrderRecoveryCreatesDebtAndLaterRebateOffsetsIt() {
        long user = 99110007L;
        transfer(user, "debt-freeze", "freeze_self", AccountType.REBATE_COST, AccountType.USER_FROZEN, "10");
        transfer(user, "debt-release", "income_self", AccountType.USER_FROZEN, AccountType.USER_AVAILABLE, "10");
        ledger.post(command(user, "debt-payout", "WITHDRAW_APPLY", AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "10"));
        ledger.post(command(user, "debt-payout", "WITHDRAW_SUCCESS", AccountType.USER_WITHDRAWING, AccountType.PAYOUT, "10"));
        transfer(user, "debt-recover", "refund_self", AccountType.USER_AVAILABLE, AccountType.REBATE_COST, "10");
        assertEquals(0, new BigDecimal("-10").compareTo(balance(user)));
        assertThrows(ServiceException.class, () -> ledger.post(command(user, "debt-denied", "WITHDRAW_APPLY",
            AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "1")));
        transfer(user, "offset-freeze", "freeze_self", AccountType.REBATE_COST, AccountType.USER_FROZEN, "12");
        transfer(user, "offset-release", "income_self", AccountType.USER_FROZEN, AccountType.USER_AVAILABLE, "12");
        assertEquals(0, new BigDecimal("2").compareTo(balance(user)));
        assertEquals(0, new BigDecimal("12").compareTo(jdbc.queryForObject(
            "SELECT total_earned FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user)));
        var audit = new LedgerAuditService(jdbc).checkAfter(user - 1, 1).getFirst();
        assertEquals(user, audit.userId()); assertTrue(audit.matches());
        jdbc.update("UPDATE sys_agent_commission_account SET balance=999 WHERE user_id=?", user);
        assertFalse(new LedgerAuditService(jdbc).checkAfter(user - 1, 1).getFirst().matches());
        assertEquals(0, new BigDecimal("999").compareTo(balance(user))); // report, never silently repair
    }
    @Test public void concurrentPostingsAcrossAccountsMatchFlowReconstruction() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 32; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(20, java.util.concurrent.TimeUnit.SECONDS));
                    long user = 99111000L + index % 8;
                    transfer(user, "parallel-freeze-"+index, "freeze_self", AccountType.REBATE_COST, AccountType.USER_FROZEN, "1");
                    transfer(user, "parallel-release-"+index, "income_self", AccountType.USER_FROZEN, AccountType.USER_AVAILABLE, "1");
                    // A stale repeatable-read snapshot must not break replay after the account has moved.
                    tx.execute(s -> {
                        jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow", Long.class);
                        transfer(user, "parallel-release-"+index, "income_self", AccountType.USER_FROZEN, AccountType.USER_AVAILABLE, "1");
                        return null;
                    });
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) f.get(45, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            start.countDown(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        var checks = new LedgerAuditService(jdbc).checkAfter(99110999L, 8);
        assertEquals(8, checks.size());
        for (var check : checks) { assertTrue(check.matches()); assertEquals(0, new BigDecimal("4").compareTo(check.available())); }
    }
    @Test public void legacyReversalCannotTreatAContractReservationAsSignedLegacyWithdrawal() {
        long user = 99110009L;
        transfer(user, "reverse-guard-freeze", "freeze_self", AccountType.REBATE_COST, AccountType.USER_FROZEN, "10");
        transfer(user, "reverse-guard-release", "income_self", AccountType.USER_FROZEN, AccountType.USER_AVAILABLE, "10");
        var reservation = ledger.post(command(user, "reverse-guard", "WITHDRAW_APPLY", AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "10"));
        assertThrows(ServiceException.class, () -> tx.execute(s -> accounts.reverseCommissionFlow(
            reservation.flowId(), "Synthetic legacy request", "synthetic-old-reversal", 1L)));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(user)));
        assertTrue(new LedgerAuditService(jdbc).checkAfter(user - 1, 1).getFirst().matches());
    }
    @Test public void malformedMoneyAndUnsupportedAccountPathsAreRejectedBeforeWriting() {
        long user = 99110008L;
        assertThrows(ServiceException.class, () -> ledger.post(command(user, "invalid-scale", "WITHDRAW_APPLY",
            AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "1.001")));
        assertThrows(ServiceException.class, () -> ledger.post(command(user, "invalid-negative", "WITHDRAW_APPLY",
            AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "-1")));
        assertThrows(ServiceException.class, () -> ledger.post(command(user, "invalid-route", "WITHDRAW_APPLY",
            AccountType.USER_AVAILABLE, AccountType.PAYOUT, "1")));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", Long.class, user));
    }

    private void persistVerifiedPaymentOrder(long buyer, long inviter) {
        tx.executeWithoutResult(status -> {
            var orders = (SysOrderCommissionMapper) ReflectionTestUtils.getField(accounts, "orderCommissionMapper");
            Instant paid = Instant.parse("2026-01-01T12:00:10Z");
            var order = new SysOrderCommission();
            order.setOrderId("snapshot-no-agent"); order.setSubOrderNo(""); order.setPlatformType("tbk");
            order.setItemId("synthetic-item"); order.setItemTitle("Synthetic");
            order.setOrderAmount(new BigDecimal("100")); order.setEstimatedCommission(new BigDecimal("20"));
            order.setCommissionRate(BigDecimal.ZERO); order.setBuyerUserId(buyer); order.setAgentUserId(buyer);
            order.setOrderTime(java.sql.Timestamp.valueOf("2026-01-01 12:00:00"));
            order.setPaidTime(Date.from(paid)); order.setOrderStatus("pending"); order.setSettleStatus("unsettled");
            order.setSelfCommissionRate(accounts.getSelfCommissionRate());
            order.setSelfCommissionAmount(order.getEstimatedCommission().multiply(order.getSelfCommissionRate())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            order.setHasReferrer("0"); order.setPromotionCommissionRate(BigDecimal.ZERO);
            order.setPromotionCommissionAmount(BigDecimal.ZERO);
            assertEquals(1, orders.insertSysOrderCommission(order));
            assertNotNull(order.getId());
            var line = new UnionOrderLine(Platform.TBK, order.getOrderId(), "", order.getItemId(), null, null,
                order.getOrderAmount(), order.getEstimatedCommission(), null, "SYNTHETIC_PAID_EVIDENCE", paid, null, null);
            PaymentInvitationSnapshotPort evidence = (platform, orderNo, suborder, owner, paidAt) -> {
                assertEquals(Platform.TBK, platform); assertEquals(order.getOrderId(), orderNo);
                assertEquals("", suborder); assertEquals(Long.valueOf(buyer), owner); assertEquals(paid, paidAt);
                return Optional.of(new PaymentInvitationSnapshotPort.Snapshot(buyer, inviter, paid, "withdrawal-fixture-paid"));
            };
            var decision = new PaymentInvitationSnapshotService(jdbc, provider(PaymentInvitationSnapshotPort.class, evidence))
                .resolve(order.getId(), buyer, line);
            assertFalse(decision.manualReview()); assertEquals("withdrawal-fixture-paid", decision.evidenceId());
            order.setReferrerUserId(decision.inviterUserId()); order.setHasReferrer("1");
            order.setPromotionCommissionRate(accounts.getPromotionCommissionRate());
            order.setPromotionCommissionAmount(order.getSelfCommissionAmount().multiply(order.getPromotionCommissionRate())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            assertEquals(1, orders.updateSysOrderCommission(order));
            assertEquals("VERIFIED", jdbc.queryForObject(
                "SELECT review_status FROM cps_payment_invitation_snapshot WHERE order_line_id=?", String.class, order.getId()));
        });
    }

    private BigDecimal balance(long user) {
        return jdbc.queryForObject("SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user);
    }
    private void transfer(long user, String id, String type, AccountType debit, AccountType credit, String amount) {
        ledger.post(new PostingCommand(id, "SYNTHETIC_TEST", id, debit, user, null, type, debit, credit,
            new BigDecimal(amount), "Synthetic order lifecycle", null));
    }
    private void ordinaryUser(long user, Long inviter) {
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,?,?,'0','0')", user, "synthetic-"+user, "Synthetic");
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid,inviter_user_id) VALUES (?,?,?)", user, "synthetic-openid-"+user, inviter);
    }
    private void rates() {
        jdbc.update("UPDATE sys_commission_config SET config_value=50,status='0' WHERE config_key='self_rate'");
        jdbc.update("UPDATE sys_commission_config SET config_value=20,status='0' WHERE config_key='promotion_rate'");
    }
    private static PostingCommand command(long user, String id, String type, AccountType debit, AccountType credit, String amount) {
        return new PostingCommand(type + ":" + id, type, id, debit, user, null, "withdraw", debit, credit, new BigDecimal(amount), "Synthetic ledger test", null);
    }
}
