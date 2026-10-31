package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.cps.mapper.RewardProgressMapper;
import com.ruoyi.system.cps.rule.*;
import com.ruoyi.system.cps.settlement.*;
import com.ruoyi.system.cps.withdraw.WithdrawalMySqlTest;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** Real production mapper/JDBC basis/outbox/ledger SQL. Requires the existing fresh-MySQL harness.
 * Deliberately NOT executed by the component worker (no DB/Maven permission).
 */
public class RewardRepricingMySqlTest {
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private RewardService rewards;
    private LedgerService ledger;
    @BeforeClass public static void migrate() throws Exception { WithdrawalMySqlTest.migrateFreshRealMySqlDatabase(); }
    @AfterClass public static void cleanup() throws Exception { WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite(); }
    @Before public void wire() throws Exception {
        jdbc = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "jdbc");
        tx = new TransactionTemplate((DataSourceTransactionManager) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "manager"));
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(jdbc.getDataSource());
        var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(RewardProgressMapper.class); factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"),
            new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        rewards = new RewardService(sessions.getMapper(RewardProgressMapper.class), new RewardRepricingJdbcStore(jdbc));
        var accounts = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(accounts, "accountMapper", sessions.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(accounts, "ledgerJdbc", jdbc);
        ledger = new DefaultLedgerService(accounts);
    }
    @Test public void realSqlDownUpReplayRefundAndRollbackPreserveImmutableGrant() {
        long order = 99129101L, buyer = 99129001L;
        tx.executeWithoutResult(s -> {
            rewards.capture(snapshot(order, buyer, Type.FIRST_N_SUBSIDY, Mode.RATIO, "1"));
            apply(rewards.qualify(order, q("5")));
        });
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> {
            apply(rewards.reprice(order, q("1"))); assertBalance(buyer, "1");
            throw new IllegalStateException("rollback award revision and real ledger together");
        }));
        assertBalance(buyer, "5");
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_reward_basis_revision r JOIN cps_reward_award a ON a.id=r.award_id WHERE a.order_id=?", Long.class, order));
        tx.executeWithoutResult(s -> { apply(rewards.reprice(order, q("1"))); apply(rewards.reprice(order, q("1"))); });
        assertBalance(buyer, "1");
        tx.executeWithoutResult(s -> apply(rewards.reprice(order, q("7"))));
        assertBalance(buyer, "7");
        assertEquals(0, new BigDecimal("5").compareTo(jdbc.queryForObject(
            "SELECT amount FROM cps_reward_award WHERE order_id=?", BigDecimal.class, order)));
        tx.executeWithoutResult(s -> {
            Refund half = Refund.fromCommissionRecovery(new BigDecimal("1"), new BigDecimal("3"), new BigDecimal("50"), new BigDecimal("2"));
            apply(rewards.refund(order, half)); apply(rewards.refund(order, half));
        });
        assertBalance(buyer, "4.67");
        tx.executeWithoutResult(s -> apply(rewards.refund(order,
            Refund.fromCommissionRecovery(new BigDecimal("3"), new BigDecimal("3"), BigDecimal.ZERO, BigDecimal.ZERO))));
        assertBalance(buyer, "0");
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_reward_basis_revision r JOIN cps_reward_award a ON a.id=r.award_id WHERE a.order_id=?", Long.class, order));
    }
    @Test public void realSqlZeroOriginalGrantCanAcquireAndLoseForcedTopup() {
        long order = 99129201L, buyer = 99129202L;
        tx.executeWithoutResult(s -> {
            rewards.capture(snapshot(order, buyer, Type.FORCED_FIRST, Mode.FIXED, "10"));
            apply(rewards.qualify(order, q("10"))); apply(rewards.reprice(order, q("1")));
        });
        assertBalance(buyer, "9");
        tx.executeWithoutResult(s -> { apply(rewards.reprice(order, q("11"))); apply(rewards.reprice(order, q("11"))); });
        assertBalance(buyer, "0");
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject(
            "SELECT amount FROM cps_reward_award WHERE order_id=?", BigDecimal.class, order)));
    }
    private static Snapshot snapshot(long order, long buyer, Type type, Mode mode, String value) {
        Config c = new Config(type, 1, mode, 1, new BigDecimal(value), Set.of("A"), T, T.plusSeconds(100));
        return new Snapshot(order, new OrderKey("A", "repricing-" + order, "s"), buyer, null, T, false,
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100"), List.of(c));
    }
    private static Qualification q(String rebate) {
        return new Qualification(T, T, true, false, new BigDecimal("100"), new BigDecimal("3"), new BigDecimal(rebate));
    }
    private void assertBalance(long buyer, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(
            "SELECT balance FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, buyer)));
    }
    private void apply(Result result) {
        for (Instruction i : result.instructions()) {
            if (i.recovery()) post(i, "", "reward_refund", AccountType.USER_AVAILABLE, AccountType.REWARD_COST);
            else {
                post(i, ":F", "reward_grant", AccountType.REWARD_COST, AccountType.USER_FROZEN);
                post(i, ":L", "reward_release", AccountType.USER_FROZEN, AccountType.USER_AVAILABLE);
            }
        }
    }
    private void post(Instruction i, String suffix, String type, AccountType debit, AccountType credit) {
        String key = i.businessKey() + suffix;
        ledger.post(new PostingCommand(key, "REWARD", key, debit, i.userId(), i.awardOrderId(), type,
            debit, credit, i.amount(), "Synthetic append-only repricing", null));
    }
}
