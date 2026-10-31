package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.withdraw.*;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** Real ledger/MySQL fault acceptance; only the channel is synthetic.
 * Reuses the existing UUID database and production-ledger wiring without changing
 * either harness. SQL SIGNAL is injected AFTER real flow/account SQL, inside the
 * production ledger transaction, not by replacing LedgerService with a fixture.
 * Requires LINE4_MYSQL_URL and MySQL 8; never targets a shared database.
 */
public class WithdrawalLedgerFailureMySqlTest {
    private WithdrawalMySqlTest acceptance;
    private JdbcTemplate jdbc;
    private LedgerService ledger;
    private WithdrawalService service;
    private CommissionCalculateServiceImpl accounts;
    private Object channel;

    @BeforeClass public static void migrate() throws Exception {
        WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
    }

    @AfterClass public static void cleanup() throws Exception {
        WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
    }

    @Before public void productionLedger() throws Exception {
        var wiring = new WithdrawalLedgerMySqlTest();
        wiring.wireProductionLedger();
        acceptance = (WithdrawalMySqlTest) ReflectionTestUtils.getField(wiring, "acceptance");
        jdbc = (JdbcTemplate) ReflectionTestUtils.getField(wiring, "jdbc");
        ledger = (LedgerService) ReflectionTestUtils.getField(wiring, "ledger");
        accounts = (CommissionCalculateServiceImpl) ReflectionTestUtils.getField(wiring, "accounts");
        service = (WithdrawalService) ReflectionTestUtils.getField(acceptance, "service");
        channel = ReflectionTestUtils.getField(acceptance, "channel");
    }

    @Test public void externalSuccessThenSqlFailureRollsBackAndQueryPostsExactlyOnce() {
        recoverTerminalSqlFailure(false);
    }

    @Test public void automaticExternalSuccessThenSqlFailureAlsoRecoversWithoutResubmission() {
        recoverTerminalSqlFailure(true);
    }

    private void recoverTerminalSqlFailure(boolean automatic) {
        long user = user("25.00");
        // Another reservation must survive both the failed terminal transaction and recovery.
        String other = approved(user, request("10.00", "other-reservation")).withdrawNo();
        WithdrawalApplyRequest request = request("15.00", automatic ? "automatic-sql-fault" : "manual-sql-fault");
        if (automatic) {
            JSONObject policy = (JSONObject) ReflectionTestUtils.getField(acceptance, "policy");
            policy.put("autoPayEnabled", true);
            policy.put("autoPayBelowAmount", "16.00");
            ReflectionTestUtils.invokeMethod(acceptance, "savePolicy");
        }
        ReflectionTestUtils.setField(channel, "next", PaymentStatus.SUCCESS);
        String no;
        if (automatic) {
            AtomicInteger faults = failTerminalSqlOnce(user);
            DataAccessException failure = assertThrows(DataAccessException.class, () -> service.apply(user, request));
            assertTrue(failure.getMostSpecificCause().getMessage().contains("Synthetic terminal ledger SQL fault"));
            assertEquals(1, faults.get());
            no = jdbc.queryForObject("SELECT withdraw_no FROM cps_withdrawal WHERE user_id=? AND request_key=?",
                String.class, user, request.requestId());
            assertEquals(1, auditCount(no, "AUTO_APPROVE"));
        } else {
            no = approved(user, request).withdrawNo();
            AtomicInteger faults = failTerminalSqlOnce(user);
            DataAccessException failure = assertThrows(DataAccessException.class, () -> service.pay(no, 1L));
            assertTrue(failure.getMostSpecificCause().getMessage().contains("Synthetic terminal ledger SQL fault"));
            assertEquals(1, faults.get());
        }
        assertNotNull(no);
        assertEquals("SUBMITTING", status(no));
        assertNull(jdbc.queryForObject("SELECT completed_at FROM cps_withdrawal WHERE withdraw_no=?", Object.class, no));
        assertAccount(user, "0.00", "25.00", "0.00");
        assertFlows(no, "WITHDRAW_APPLY");
        assertFlows(other, "WITHDRAW_APPLY");
        assertEquals(1, auditCount(no, "SUBMIT"));
        assertEquals(0, auditCount(no, "CHANNEL_RESULT"));
        assertEquals(List.of(no), ReflectionTestUtils.getField(channel, "submitNumbers"));
        assertEquals(0, counter("queried"));
        // The synthetic external channel remembers success under the original number.
        PaymentResult external = ReflectionTestUtils.invokeMethod(channel, "query", no);
        assertNotNull(external);
        assertEquals(PaymentStatus.SUCCESS, external.status());

        assertEquals(WithdrawalStatus.SUCCEEDED, service.query(no).status());
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM cps_withdrawal WHERE withdraw_no=?", Object.class, no));
        assertAccount(user, "0.00", "10.00", "15.00");
        assertFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        assertFlows(other, "WITHDRAW_APPLY");
        assertEquals("APPROVED", status(other));
        assertEquals(1, auditCount(no, "CHANNEL_RESULT"));
        assertEquals(2, counter("queried")); // explicit external inspection plus recovery

        // Exercise both caller replay and the production ledger's own unique-key replay.
        service.pay(no, 1L);
        service.query(no);
        ChannelNotification notification = ReflectionTestUtils.invokeMethod(channel, "notification", no, PaymentStatus.SUCCESS);
        service.callback(notification);
        assertEquals(no, service.apply(user, request).withdrawNo());
        assertTrue(ledger.post(terminal(user, no, "15.00", true)).replayed());
        assertAccount(user, "0.00", "10.00", "15.00");
        assertFlows(no, "WITHDRAW_APPLY", "WITHDRAW_SUCCESS");
        assertEquals(3L, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", user));
        assertEquals(1, counter("submitted"));
        assertEquals(2, counter("queried"));
        assertEquals(List.of(no), ReflectionTestUtils.getField(channel, "submitNumbers"));
        assertEquals(1, auditCount(no, "SUBMIT"));
        assertEquals(1, auditCount(no, "CHANNEL_RESULT"));
    }

    /** The first account UPDATE and flow INSERT have already executed. Fail the
     * final pocket UPDATE via a real server SQL error after it executes as well.
     * All those SQL effects must disappear together when the transaction unwinds.
     */
    private AtomicInteger failTerminalSqlOnce(long user) {
        AtomicInteger faults = new AtomicInteger();
        JdbcTemplate faulting = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql, Object... args) {
                int changed = super.update(sql, args);
                if (sql.startsWith("UPDATE sys_agent_commission_account SET withdrawing_amount=")
                        && ((Number) args[1]).longValue() == user
                        && count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=? AND business_type='WITHDRAW_SUCCESS'", user) == 1
                        && faults.compareAndSet(0, 1)) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals(1, changed);
                    assertAccount(user, "0.00", "10.00", "15.00");
                    // JDBC translates the actual MySQL failure; no fake ledger exception.
                    jdbc.execute("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic terminal ledger SQL fault'");
                    fail("MySQL SIGNAL must fail the real ledger transaction");
                }
                return changed;
            }
        };
        ReflectionTestUtils.setField(accounts, "ledgerJdbc", faulting);
        return faults;
    }

    @Test public void rejectionWinsPaymentRaceWithoutConsumingAnotherReservation() throws Exception {
        long user = user("30.00");
        String no = approved(user, request("10.00", "reject-race")).withdrawNo();
        String other = approved(user, request("20.00", "reject-race-other")).withdrawNo();
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch payEntered = new CountDownLatch(1);
        // Pause AFTER the real transactional ledger delegate wrote the return, while
        // WithdrawalService still owns admission/account/withdrawal locks.
        ProxyFactory gate = new ProxyFactory(ledger);
        gate.setInterfaces(LedgerService.class);
        gate.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getArguments().length == 1
                    && invocation.getArguments()[0] instanceof PostingCommand command
                    && command.businessId().equals(no) && command.businessType().equals("WITHDRAW_RETURN")) {
                returned.countDown();
                assertTrue("Timed out releasing real ledger return", release.await(20, TimeUnit.SECONDS));
            }
            return result;
        });
        LedgerService gated = (LedgerService) gate.getProxy();
        service = ReflectionTestUtils.invokeMethod(acceptance, "newService", gated, (WithdrawChannel) channel);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var rejection = pool.submit(() -> service.audit(no, false, 1L, "Synthetic rejection race"));
            assertTrue("Return never reached the real ledger", returned.await(20, TimeUnit.SECONDS));
            var payment = pool.submit(() -> {
                payEntered.countDown();
                return service.pay(no, 2L);
            });
            assertTrue(payEntered.await(10, TimeUnit.SECONDS));
            assertFalse("Payment cannot pass uncommitted rejection locks", payment.isDone());
            assertEquals("APPROVED", status(no));
            assertAccount(user, "0.00", "30.00", "0.00");
            assertFlows(no, "WITHDRAW_APPLY");
            release.countDown();
            assertEquals(WithdrawalStatus.REJECTED, rejection.get(20, TimeUnit.SECONDS).status());
            assertEquals(WithdrawalStatus.REJECTED, payment.get(20, TimeUnit.SECONDS).status());
            assertEquals(WithdrawalStatus.REJECTED, service.audit(no, false, 1L, "Synthetic replay").status());
            assertTrue(ledger.post(terminal(user, no, "10.00", false)).replayed());
            // A conflicting terminal posting must not spend the second withdrawal's money.
            assertThrows(ServiceException.class, () -> ledger.post(terminal(user, no, "10.00", true)));
            assertAccount(user, "10.00", "20.00", "0.00");
            assertFlows(no, "WITHDRAW_APPLY", "WITHDRAW_RETURN");
            assertFlows(other, "WITHDRAW_APPLY");
            assertEquals("APPROVED", status(other));
            assertEquals(3L, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", user));
            assertEquals(0, counter("submitted"));
            assertEquals(0, counter("queried"));
            assertEquals(0, auditCount(no, "SUBMIT"));
            assertEquals(1, auditCount(no, "REJECT"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private long user(String balance) {
        return ReflectionTestUtils.invokeMethod(WithdrawalMySqlTest.class, "user", balance);
    }

    private WithdrawalView approved(long user, WithdrawalApplyRequest request) {
        return ReflectionTestUtils.invokeMethod(acceptance, "applyAndApprove", user, request);
    }

    private static WithdrawalApplyRequest request(String amount, String key) {
        return new WithdrawalApplyRequest(new BigDecimal(amount), "real-ledger-" + key);
    }

    private void assertAccount(long user, String available, String withdrawing, String paid) {
        ReflectionTestUtils.invokeMethod(WithdrawalMySqlTest.class, "assertAccount", user, available, withdrawing, paid);
    }

    private void assertFlows(String no, String... types) {
        ReflectionTestUtils.invokeMethod(WithdrawalMySqlTest.class, "assertWithdrawalFlows", no, types);
    }

    private String status(String no) {
        return jdbc.queryForObject("SELECT status FROM cps_withdrawal WHERE withdraw_no=?", String.class, no);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long auditCount(String no, String action) {
        return count("SELECT COUNT(*) FROM cps_withdraw_audit WHERE withdraw_no=? AND action=?", no, action);
    }

    private int counter(String name) {
        return ((AtomicInteger) ReflectionTestUtils.getField(channel, name)).get();
    }

    private static PostingCommand terminal(long user, String no, String amount, boolean success) {
        String business = success ? "WITHDRAW_SUCCESS" : "WITHDRAW_RETURN";
        AccountType credit = success ? AccountType.PAYOUT : AccountType.USER_AVAILABLE;
        return new PostingCommand(business + ":" + no, business, no, AccountType.USER_WITHDRAWING,
            user, null, success ? "withdraw_success" : "withdraw_return", AccountType.USER_WITHDRAWING,
            credit, new BigDecimal(amount), "Synthetic terminal replay", null);
    }
}
