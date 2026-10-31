package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.cps.mapper.RewardProgressMapper;
import com.ruoyi.system.cps.rule.RewardService;
import com.ruoyi.system.cps.settlement.*;
import com.ruoyi.system.cps.withdraw.WithdrawalMySqlTest;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** Synthetic integration cases, not the end-to-end 19-row business acceptance matrix.
 * Requires LINE4_MYSQL_URL (MySQL 8+), LINE4_MYSQL_USER and LINE4_MYSQL_PASSWORD.
 * The immutable withdrawal harness only creates/migrates/drops a fresh UUID database;
 * its SQL ledger fixture and mocked policy dependencies are never instantiated here.
 */
public class RewardLedgerMySqlTest {
    private static final Instant PAID = Instant.parse("2026-01-01T12:00:00Z");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private RewardService rewards;
    private LedgerService ledger;
    private SettlementNotificationService notifications;
    private DataSourceTransactionManager manager;

    @BeforeClass public static void migrate() throws Exception {
        WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
    }
    @AfterClass public static void cleanup() throws Exception {
        WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
    }
    @Before public void wireRealServices() throws Exception {
        jdbc = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "jdbc");
        manager = (DataSourceTransactionManager) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "manager");
        tx = new TransactionTemplate(manager);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RewardProgressMapper.class); // real production annotation SQL
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        var accounts = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(accounts, "accountMapper", sessions.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(accounts, "ledgerJdbc", jdbc);
        ledger = proxy(new DefaultLedgerService(accounts), LedgerService.class);
        rewards = proxy(new RewardService(sessions.getMapper(RewardProgressMapper.class)), RewardService.class);
        var channels = new StaticListableBeanFactory().getBeanProvider(SettlementNotificationChannel.class);
        notifications = proxy(new SettlementNotificationService(new SettlementNotificationRepository(jdbc), channels, manager),
            SettlementNotificationService.class);
    }
    private <T> T proxy(T target, Class<T> type) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        if (type.isInterface()) factory.setInterfaces(type); else factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }

    @Test public void allFiveTypesQualifyOnlyAfterReleaseAndReplayDoesNotPayAgain() {
        long buyer = 99121001L, inviter = 99121002L, order = 99121101L;
        Snapshot snapshot = snapshot(order, buyer, inviter, List.of(
            config(Type.INVITATION_RATIO, 1, ".10"), config(Type.INVITEE_FIRST_FIXED, 1, "3"),
            config(Type.INVITEE_FIRST_N_FIXED, 2, "4"), config(Type.OWN_QUANTITY, 1, "5"),
            config(Type.FIRST_N_SUBSIDY, 2, "6")));
        capture(snapshot);
        tx.executeWithoutResult(s -> {
            assertTrue(rewards.qualify(order, evidence(false, false)).instructions().isEmpty());
            assertTrue(rewards.qualify(order, evidence(true, true)).instructions().isEmpty());
            assertTrue(rewards.qualify(order, new Qualification(PAID, PAID.plusSeconds(1), true, false,
                money("100"), money("20"), money("10"))).instructions().isEmpty());
            assertEquals(0, count("cps_reward_instruction", "cause_order_id", order));
            Result result = rewards.qualify(order, evidence(true, false));
            assertFalse(result.manualReview());
            assertEquals(5, result.instructions().size());
            assertEquals(Set.of(Type.values()), result.instructions().stream().map(Instruction::type)
                .collect(java.util.stream.Collectors.toSet()));
            apply(result.instructions());
        });
        assertMoney("8", balance(inviter));
        assertMoney("11", balance(buyer));
        tx.executeWithoutResult(s -> {
            assertEquals(snapshot, rewards.snapshot(order));
            Result replay = rewards.qualify(order, evidence(true, false));
            assertEquals(5, replay.instructions().size());
            apply(replay.instructions());
        });
        assertEquals(10, flows(buyer) + flows(inviter));
        assertMoney("8", balance(inviter));
        assertMoney("11", balance(buyer));
        assertAudit(buyer); assertAudit(inviter);
    }

    @Test public void cumulativeRefundPostsOnlyPositiveDeltaAndReplaysExactlyOnce() {
        long buyer = 99122001L, order = 99122101L;
        capture(snapshot(order, buyer, null, List.of(config(Type.FIRST_N_SUBSIDY, 3, "10"))));
        qualifyAndApply(order);
        tx.executeWithoutResult(s -> {
            Result first = rewards.refund(order, refund(".25"));
            assertEquals(2, first.instructions().size()); // grant plus first recovery
            assertTrue(first.instructions().get(1).recovery());
            assertMoney("2.50", first.instructions().get(1).amount());
            apply(first.instructions());
            apply(rewards.refund(order, refund(".25")).instructions());
            Result second = rewards.refund(order, refund(".50"));
            assertEquals(3, second.instructions().size()); // grant plus cumulative recovery deltas
            assertMoney("2.50", second.instructions().get(2).amount());
            assertEquals(2, second.instructions().stream().filter(Instruction::recovery).count());
            apply(second.instructions());
            apply(rewards.replay(order)); // includes original grant; every ledger leg deduplicates
        });
        assertMoney("5", balance(buyer));
        assertEquals(4, flows(buyer));
        assertMoney("5", jdbc.queryForObject("SELECT recovered FROM cps_reward_award WHERE order_id=?", BigDecimal.class, order));
        assertThrows(IllegalArgumentException.class, () -> tx.execute(s -> rewards.refund(order, refund(".40"))));
        assertMoney("5", balance(buyer));
        assertAudit(buyer);
    }

    @Test public void quantityAwardOnSecondOrderIsClawedBackByRefundOfFirstContribution() {
        long buyer = 99123001L, first = 99123101L, second = 99123102L;
        List<Config> configs = List.of(config(Type.OWN_QUANTITY, 2, "9"));
        capture(snapshot(first, buyer, null, configs)); capture(snapshot(second, buyer, null, configs));
        qualifyAndApply(first);
        assertEquals(0, flows(buyer));
        qualifyAndApply(second);
        assertMoney("9", balance(buyer));
        tx.executeWithoutResult(s -> {
            Result result = rewards.refund(first, new Refund(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));
            assertEquals(1, result.instructions().size());
            Instruction recovery = result.instructions().get(0);
            assertTrue(recovery.recovery());
            assertEquals(first, recovery.causeOrderId());
            assertEquals(second, recovery.awardOrderId());
            assertMoney("9", recovery.amount());
            apply(result.instructions());
            apply(rewards.refund(first, new Refund(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO)).instructions());
        });
        assertMoney("0", balance(buyer));
        assertEquals(3, flows(buyer));
        assertEquals(1, count("cps_reward_instruction", "cause_order_id", first));
        assertAudit(buyer);
    }

    @Test public void captureQualificationAndRealLedgerRollbackTogether() {
        long buyer = 99124001L, order = 99124101L;
        Snapshot snapshot = snapshot(order, buyer, null, List.of(config(Type.FIRST_N_SUBSIDY, 2, "7")));
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> {
            rewards.capture(snapshot);
            apply(rewards.qualify(order, evidence(true, false)).instructions());
            assertMoney("7", balance(buyer));
            throw new IllegalStateException("synthetic failure after real ledger");
        }));
        assertEquals(0, count("cps_reward_order_snapshot", "order_id", order));
        assertEquals(0, count("cps_reward_order_state", "order_id", order));
        assertEquals(0, count("cps_reward_award", "order_id", order));
        assertEquals(0, count("cps_reward_instruction", "cause_order_id", order));
        assertEquals(0, count("sys_agent_commission_account", "user_id", buyer));
        assertEquals(0, flows(buyer));
        capture(snapshot); qualifyAndApply(order);
        assertMoney("7", balance(buyer));
        assertEquals(2, flows(buyer));
    }

    @Test public void notificationAndRecoveryShareLedgerRollbackThenCommitAndReplay() {
        long buyer = 99125001L, order = 99125101L;
        capture(snapshot(order, buyer, null, List.of(config(Type.FIRST_N_SUBSIDY, 2, "10"))));
        qualifyAndApply(order);
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> {
            recoverAndNotify(order, buyer);
            assertMoney("5", balance(buyer));
            assertEquals(1, count("cps_settlement_notification", "user_id", buyer));
            throw new IllegalStateException("synthetic failure after notification enqueue");
        }));
        assertMoney("10", balance(buyer));
        assertEquals(2, flows(buyer));
        assertEquals(1, count("cps_reward_instruction", "cause_order_id", order));
        assertMoney("0", jdbc.queryForObject("SELECT recovered FROM cps_reward_award WHERE order_id=?", BigDecimal.class, order));
        assertEquals(0, count("cps_settlement_notification", "user_id", buyer));
        tx.executeWithoutResult(s -> recoverAndNotify(order, buyer));
        tx.executeWithoutResult(s -> recoverAndNotify(order, buyer));
        assertMoney("5", balance(buyer));
        assertEquals(3, flows(buyer));
        assertEquals(1, count("cps_settlement_notification", "user_id", buyer));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM cps_settlement_notification WHERE user_id=?", String.class, buyer));
        assertAudit(buyer);
    }

    @Test public void paymentSnapshotIsImmutableAndMissingAttributionRequiresReview() {
        long buyer = 99126001L, order = 99126101L;
        Snapshot original = snapshot(order, buyer, null, List.of(config(Type.FIRST_N_SUBSIDY, 2, "3")));
        capture(original); capture(original);
        assertThrows(IllegalStateException.class, () -> capture(snapshot(order, buyer, 99126002L,
            List.of(config(Type.FIRST_N_SUBSIDY, 2, "99")))));
        qualifyAndApply(order);
        assertMoney("3", balance(buyer));
        long historical = order + 1;
        Snapshot unknown = new Snapshot(historical, new OrderKey("tbk", "synthetic-" + historical, ""), buyer,
            null, PAID, true, money("1"), money("1"), money("100"), original.configs());
        capture(unknown);
        tx.executeWithoutResult(s -> {
            Result result = rewards.qualify(historical, evidence(true, false));
            assertTrue(result.manualReview()); assertTrue(result.instructions().isEmpty());
        });
        assertEquals(0, count("cps_reward_instruction", "cause_order_id", historical));
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> rewards.qualify(historical + 1, evidence(true, false))));
    }

    private void recoverAndNotify(long order, long buyer) {
        Result result = rewards.refund(order, refund(".50"));
        apply(result.instructions());
        Instruction recovery = result.instructions().stream().filter(Instruction::recovery).findFirst().orElseThrow();
        notifications.enqueue(recovery.businessKey(), buyer, order, SettlementNotificationService.REFUND,
            recovery.amount(), balance(buyer));
    }
    private void capture(Snapshot snapshot) { tx.executeWithoutResult(s -> rewards.capture(snapshot)); }
    private void qualifyAndApply(long order) {
        tx.executeWithoutResult(s -> apply(rewards.qualify(order, evidence(true, false)).instructions()));
    }
    /** Test-side settlement adapter: grants must use two legal routes, not COST -> AVAILABLE.
     * Stable suffixes preserve instruction identity across both positive-amount legs. Enum names
     * are NOT flow_type (some exceed its 20-character contract). No production adapter is claimed.
     */
    private void apply(List<Instruction> instructions) {
        for (Instruction i : instructions) {
            assertTrue(i.amount().signum() > 0);
            if (i.recovery()) {
                post(i, "", "reward_refund", AccountType.USER_AVAILABLE, AccountType.REWARD_COST);
            } else {
                post(i, ":F", "reward_grant", AccountType.REWARD_COST, AccountType.USER_FROZEN);
                post(i, ":L", "reward_release", AccountType.USER_FROZEN, AccountType.USER_AVAILABLE);
            }
        }
    }
    private void post(Instruction i, String suffix, String type, AccountType debit, AccountType credit) {
        String key = i.businessKey() + suffix;
        ledger.post(new PostingCommand(key, "REWARD", key, debit, i.userId(), i.awardOrderId(), type,
            debit, credit, i.amount(), "Synthetic reward settlement", null));
    }
    private static Snapshot snapshot(long order, long buyer, Long inviter, List<Config> configs) {
        return new Snapshot(order, new OrderKey("tbk", "synthetic-" + order, ""), buyer, inviter, PAID, false,
            money("1"), money("1"), money("100"), configs);
    }
    private static Config config(Type type, int n, String value) {
        return new Config(type, 1, type == Type.INVITATION_RATIO ? Mode.RATIO : Mode.FIXED, n,
            new BigDecimal(value), Set.of("tbk"), PAID.minusSeconds(1), PAID.plusSeconds(3600));
    }
    private static Qualification evidence(boolean released, boolean held) {
        return new Qualification(PAID.plusSeconds(120), PAID.plusSeconds(60), released, held,
            money("100"), money("20"), money("10"));
    }
    private static Refund refund(String ratio) {
        BigDecimal remaining = BigDecimal.ONE.subtract(new BigDecimal(ratio));
        return new Refund(new BigDecimal(ratio), money("100").multiply(remaining), money("20").multiply(remaining));
    }
    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
    private static void assertMoney(String expected, BigDecimal actual) { assertEquals(0, money(expected).compareTo(actual)); }
    private BigDecimal balance(long user) {
        return jdbc.queryForObject("SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user);
    }
    private long count(String table, String column, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Long.class, id);
    }
    private long flows(long user) { return count("sys_commission_flow", "user_id", user); }
    private void assertAudit(long user) {
        var checks = new LedgerAuditService(jdbc).checkAfter(user - 1, 1);
        assertEquals(1, checks.size()); assertEquals(user, checks.getFirst().userId());
        assertTrue(checks.getFirst().matches());
    }
}
