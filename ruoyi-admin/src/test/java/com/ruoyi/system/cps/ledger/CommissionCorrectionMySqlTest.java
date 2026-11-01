package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.withdraw.WithdrawalMySqlTest;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.domain.SysCommissionFlow;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.mapper.SysCommissionFlowMapper;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.system.service.ICommissionCalculateService;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/**
 * The four batch-02 line 11 correction cases, with both race orderings tested separately.
 * Only the fresh MySQL/Flyway harness is reused: no SQL ledger fixture or mocked mapper.
 * Historical input rows are inserted once; financial mutations use the production service.
 */
public class CommissionCorrectionMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(99_130_000L);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static SysAgentCommissionAccountMapper accounts;
    private static SysCommissionFlowMapper flows;
    private static SysOrderCommissionMapper orders;
    private static ICommissionCalculateService service;

    @BeforeClass public static void setup() throws Exception {
        WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
        jdbc = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "jdbc");
        var manager = (DataSourceTransactionManager) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "manager");
        tx = new TransactionTemplate(manager);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setMapperLocations(
            new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionFlowMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        accounts = sessions.getMapper(SysAgentCommissionAccountMapper.class);
        flows = sessions.getMapper(SysCommissionFlowMapper.class);
        orders = sessions.getMapper(SysOrderCommissionMapper.class);
        var target = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(target, "accountMapper", accounts);
        ReflectionTestUtils.setField(target, "flowMapper", flows);
        ReflectionTestUtils.setField(target, "orderCommissionMapper", orders);
        ReflectionTestUtils.setField(target, "ledgerJdbc", jdbc);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(ICommissionCalculateService.class);
        proxy.addAdvice(interceptor);
        service = (ICommissionCalculateService) proxy.getProxy();
    }

    @AfterClass public static void cleanup() throws Exception {
        WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
    }

    @Test public void buyerAlsoStoredAsReferrerReversesOnlyTheSelectedFlow() {
        long owner = IDS.incrementAndGet();
        // Historical shape only: this fixture does not authorize new self-invitations.
        long order = settle(owner, owner);
        long self = income(order, owner, "income_self");
        long promotion = income(order, owner, "income_promotion");
        Map<String, Object> originalSelf = row(self), originalPromotion = row(promotion);
        account(owner, "10.80", "9.00", "1.80");
        String key = "correction-same-beneficiary-" + order;
        SysCommissionFlow reversal = service.reverseCommissionFlow(self, "Synthetic selected self correction", key, 42L);
        assertEquals(Long.valueOf(self), reversal.getOriginalFlowId());
        money("-9.00", reversal.getAmount());
        assertEquals(reversal.getId(), service.reverseCommissionFlow(self, "Synthetic selected self correction", key, 42L).getId());
        assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE original_flow_id=?", self));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE original_flow_id=?", promotion));
        assertEquals(3, flowCount(owner));
        account(owner, "1.80", "0.00", "1.80");
        assertEquals(originalSelf, row(self)); assertEquals(originalPromotion, row(promotion));
        assertTrue(service.refundOrder(order)); assertTrue(service.refundOrder(order));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=? AND flow_type='refund_self'", order));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=? AND flow_type='refund_promotion'", order));
        assertEquals(4, flowCount(owner));
        account(owner, "0.00", "0.00", "0.00");
        assertEquals(originalSelf, row(self)); assertEquals(originalPromotion, row(promotion));
    }

    @Test public void cancelledOrderRejectsCorrectionWithoutChangingEitherBeneficiary() {
        long buyer = IDS.incrementAndGet(), referrer = IDS.incrementAndGet();
        long order = settle(buyer, referrer);
        long self = income(order, buyer, "income_self");
        long promotion = income(order, referrer, "income_promotion");
        Map<String, Object> originalSelf = row(self), originalPromotion = row(promotion);
        // Prepare the pre-existing terminal order status; never mutate its original money rows.
        jdbc.update("UPDATE sys_order_commission SET order_status='cancelled' WHERE id=?", order);
        ServiceException rejected = assertThrows(ServiceException.class, () ->
            service.reverseCommissionFlow(self, "Synthetic cancelled correction", "correction-cancelled-" + order, 42L));
        assertTrue(rejected.getMessage().contains("已退款或取消"));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE original_flow_id IN (?,?)", self, promotion));
        assertEquals(1, flowCount(buyer)); assertEquals(1, flowCount(referrer));
        account(buyer, "9.00", "9.00", "0.00"); account(referrer, "1.80", "0.00", "1.80");
        assertEquals("cancelled", orders.selectSysOrderCommissionById(order).getOrderStatus());
        assertEquals(originalSelf, row(self)); assertEquals(originalPromotion, row(promotion));
    }

    @Test public void legacyRelatedOrderIdIsResolvedAndRefundNeverDebitsTheReversedIncomeAgain() {
        long buyer = IDS.incrementAndGet();
        long order = pendingOrder(buyer, null);
        // Import an immutable historical input shape: this older income has only related_order_id.
        // The account opening fixture equals that one original row. No existing flow is edited.
        long original = tx.execute(status -> {
            jdbc.update("UPDATE sys_order_commission SET order_status='confirmed',settle_status='settled',"
                + "actual_commission=18,self_commission_amount=9 WHERE id=?", order);
            jdbc.update("INSERT INTO sys_agent_commission_account(user_id,balance,total_earned,self_earned,promotion_earned,status) "
                + "VALUES (?,9,9,9,0,'0')", buyer);
            var historical = new SysCommissionFlow();
            historical.setUserId(buyer); historical.setFlowType("income_self"); historical.setAmount(bd("9"));
            historical.setBalanceBefore(bd("0")); historical.setBalanceAfter(bd("9"));
            historical.setRelatedOrderId(Long.toString(order));
            historical.setDescription("Synthetic immutable historical related_order_id-only input");
            assertEquals(1, flows.insertSysCommissionFlow(historical));
            assertNotNull(historical.getId()); return historical.getId();
        });
        Map<String, Object> originalRow = row(original);
        assertNull(originalRow.get("order_commission_id"));
        assertEquals(Long.toString(order), originalRow.get("related_order_id"));
        account(buyer, "9.00", "9.00", "0.00");
        String key = "correction-legacy-related-" + order;
        SysCommissionFlow reversal = service.reverseCommissionFlow(original, "Synthetic legacy correction", key, 42L);
        money("-9.00", reversal.getAmount()); assertEquals(Long.valueOf(original), reversal.getOriginalFlowId());
        assertEquals(Long.toString(order), reversal.getRelatedOrderId());
        assertTrue(service.refundOrder(order)); assertTrue(service.refundOrder(order));
        assertEquals(reversal.getId(), service.reverseCommissionFlow(original, "Synthetic legacy correction", key, 42L).getId());
        assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE original_flow_id=?", original));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=? AND flow_type='refund_self'", buyer));
        assertEquals(2, flowCount(buyer)); account(buyer, "0.00", "0.00", "0.00");
        assertEquals("refunded", orders.selectSysOrderCommissionById(order).getOrderStatus());
        assertEquals(originalRow, row(original));
    }

    @Test public void concurrentCorrectionThenRefundRecoversEachEntitlementOnlyOnce() throws Exception {
        long buyer = IDS.incrementAndGet(), referrer = IDS.incrementAndGet();
        long order = settle(buyer, referrer);
        long self = income(order, buyer, "income_self"), promotion = income(order, referrer, "income_promotion");
        Map<String, Object> originalSelf = row(self), originalPromotion = row(promotion);
        String key = "correction-before-refund-" + order;
        concurrentWithFirstHoldingOrder(order,
            () -> service.reverseCommissionFlow(self, "Synthetic race correction", key, 42L),
            () -> service.refundOrder(order) ? "REFUNDED" : "NOT_REFUNDED", "REFUNDED");
        assertTrue(service.refundOrder(order));
        SysCommissionFlow replay = service.reverseCommissionFlow(self, "Synthetic race correction", key, 42L);
        assertNotNull(replay.getId());
        assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE original_flow_id=?", self));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=? AND flow_type='refund_self'", order));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=? AND flow_type='refund_promotion'", order));
        assertEquals(2, flowCount(buyer)); assertEquals(2, flowCount(referrer));
        account(buyer, "0.00", "0.00", "0.00"); account(referrer, "0.00", "0.00", "0.00");
        assertEquals("refunded", orders.selectSysOrderCommissionById(order).getOrderStatus());
        assertEquals(originalSelf, row(self)); assertEquals(originalPromotion, row(promotion));
    }

    @Test public void concurrentRefundThenCorrectionRejectsTheLosingCorrection() throws Exception {
        long buyer = IDS.incrementAndGet(), referrer = IDS.incrementAndGet();
        long order = settle(buyer, referrer);
        long self = income(order, buyer, "income_self"), promotion = income(order, referrer, "income_promotion");
        Map<String, Object> originalSelf = row(self), originalPromotion = row(promotion);
        String key = "refund-before-correction-" + order;
        concurrentWithFirstHoldingOrder(order, () -> assertTrue(service.refundOrder(order)), () -> {
            service.reverseCommissionFlow(self, "Synthetic losing race correction", key, 42L);
            return "INCORRECTLY_REVERSED";
        }, "CORRECTION_REJECTED");
        assertTrue(service.refundOrder(order));
        assertThrows(ServiceException.class, () ->
            service.reverseCommissionFlow(self, "Synthetic losing race correction", key, 42L));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE original_flow_id IN (?,?)", self, promotion));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=? AND flow_type='refund_self'", order));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=? AND flow_type='refund_promotion'", order));
        assertEquals(2, flowCount(buyer)); assertEquals(2, flowCount(referrer));
        account(buyer, "0.00", "0.00", "0.00"); account(referrer, "0.00", "0.00", "0.00");
        assertEquals("refunded", orders.selectSysOrderCommissionById(order).getOrderStatus());
        assertEquals(originalSelf, row(self)); assertEquals(originalPromotion, row(promotion));
    }

    private static long pendingOrder(long buyer, Long referrer) {
        long number = IDS.incrementAndGet();
        var order = new SysOrderCommission();
        order.setOrderId("synthetic-correction-" + number); order.setSubOrderNo(""); order.setPlatformType("tbk");
        order.setBuyerUserId(buyer); order.setAgentUserId(buyer); order.setReferrerUserId(referrer);
        order.setHasReferrer(referrer == null ? "0" : "1"); order.setOrderStatus("pending"); order.setSettleStatus("unsettled");
        order.setOrderAmount(bd("100")); order.setEstimatedCommission(bd("18"));
        order.setSelfCommissionRate(bd("50")); order.setSelfCommissionAmount(bd("9"));
        order.setPromotionCommissionRate(referrer == null ? bd("0") : bd("20"));
        order.setPromotionCommissionAmount(referrer == null ? bd("0") : bd("1.80"));
        assertEquals(1, orders.insertSysOrderCommission(order)); assertNotNull(order.getId());
        return order.getId();
    }

    private static long settle(long buyer, Long referrer) {
        long id = pendingOrder(buyer, referrer);
        assertTrue(service.settleOrder(id, bd("18"))); return id;
    }

    private static long income(long order, long user, String type) {
        return jdbc.queryForObject("SELECT id FROM sys_commission_flow WHERE order_commission_id=? AND user_id=? AND flow_type=?",
            Long.class, order, user, type);
    }

    private static void concurrentWithFirstHoldingOrder(long order, Runnable firstAction,
            Supplier<String> secondAction, String expectedSecondResult) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstPosted = new CountDownLatch(1), secondStarted = new CountDownLatch(1), commitFirst = new CountDownLatch(1);
        AtomicLong contenderConnection = new AtomicLong();
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                assertEquals(Long.valueOf(order), jdbc.queryForObject(
                    "SELECT id FROM sys_order_commission WHERE id=? FOR UPDATE", Long.class, order));
                firstAction.run();
                firstPosted.countDown();
                try {
                    assertTrue("first transaction commit gate timed out", commitFirst.await(20, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
            }));
            if (!firstPosted.await(15, TimeUnit.SECONDS)) {
                if (first.isDone()) first.get(1, TimeUnit.SECONDS);
                fail("First production action did not acquire and hold the MySQL order lock");
            }
            Future<String> second = pool.submit(() -> {
                try {
                    return tx.execute(status -> {
                        contenderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        secondStarted.countDown();
                        return secondAction.get();
                    });
                } catch (ServiceException rejected) {
                    // Catch outside the transaction so the rejected command is fully rolled back.
                    if (!"CORRECTION_REJECTED".equals(expectedSecondResult)) throw rejected;
                    assertTrue(rejected.getMessage().contains("已退款或取消"));
                    return "CORRECTION_REJECTED";
                }
            });
            assertTrue("second worker did not start", secondStarted.await(10, TimeUnit.SECONDS));
            // Prove the second production transaction is really waiting on InnoDB before
            // releasing the winner; thread start timing alone is not a concurrency proof.
            awaitInnoDbLockWait(contenderConnection.get(), second);
            commitFirst.countDown();
            first.get(30, TimeUnit.SECONDS);
            assertEquals(expectedSecondResult, second.get(30, TimeUnit.SECONDS));
        } finally {
            commitFirst.countDown();
            pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void awaitInnoDbLockWait(long connection, Future<?> contender) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx "
                    + "WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'", Long.class, connection) == 1L) return;
            assertFalse("contender completed while the winner still held the order lock", contender.isDone());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("MySQL never reported the contender waiting on the held order lock");
    }

    private static void account(long user, String balance, String self, String promotion) {
        SysAgentCommissionAccount a = accounts.selectByUserId(user); assertNotNull(a);
        money(balance, a.getBalance()); money(balance, a.getTotalEarned());
        money(self, a.getSelfEarned()); money(promotion, a.getPromotionEarned());
        money("0", a.getFrozenAmount()); money("0", a.getWithdrawingAmount()); money("0", a.getWithdrawn());
        BigDecimal running = bd("0");
        for (var row : jdbc.queryForList("SELECT amount,balance_before,balance_after FROM sys_commission_flow WHERE user_id=? ORDER BY id", user)) {
            money(running.toPlainString(), (BigDecimal) row.get("balance_before"));
            running = running.add((BigDecimal) row.get("amount"));
            money(running.toPlainString(), (BigDecimal) row.get("balance_after"));
        }
        money(balance, running);
        var audited = new LedgerAuditService(jdbc).checkAfter(user - 1, 1).getFirst();
        assertEquals(user, audited.userId()); assertTrue(audited.matches());
    }

    private static Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM sys_commission_flow WHERE id=?", id);
    }
    private static long flowCount(long user) { return count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", user); }
    private static long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static BigDecimal bd(String amount) { return new BigDecimal(amount); }
    private static void money(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, bd(expected).compareTo(actual)); }
}
