package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.domain.SysAgentCommissionConfig;
import com.ruoyi.system.domain.SysCommissionFlow;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.domain.SysTbkAgentApply;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.mapper.SysAgentCommissionConfigMapper;
import com.ruoyi.system.mapper.SysCommissionConfigMapper;
import com.ruoyi.system.mapper.SysCommissionFlowMapper;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.system.mapper.SysTbkAgentApplyMapper;
import com.ruoyi.system.service.ICommissionCalculateService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Real MySQL acceptance for line 4. No in-memory database or hand-written schema
 * stands in for the application's migrations and MyBatis mappings.
 *
 * Set LINE4_MYSQL_URL, LINE4_MYSQL_USER and LINE4_MYSQL_PASSWORD to an isolated
 * MySQL 8 server. The connection must be allowed to create/drop a database and
 * create a trigger. Only a fresh, randomly named line4_commission_* database is
 * migrated, populated and dropped; the URL's existing database is never changed.
 * LINE4_MYSQL_URL is required: missing MySQL configuration fails acceptance
 * explicitly rather than reporting this accounting suite as skipped.
 */
public class CommissionAccountingMySqlTest
{
    private static final AtomicLong IDS = new AtomicLong(8_000_000_000L);

    private static String adminUrl;
    private static String mysqlUser;
    private static String mysqlPassword;
    private static String database;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static CommissionCalculateServiceImpl target;
    private static ICommissionCalculateService commissions;
    private static SysAgentCommissionAccountMapper accounts;
    private static SysAgentCommissionConfigMapper configs;
    private static SysCommissionFlowMapper flows;
    private static SysOrderCommissionMapper orders;
    private static SysAgentCommissionAccountServiceImpl accountService;

    @BeforeClass
    public static void migrateIsolatedMySqlDatabase() throws Exception
    {
        adminUrl = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL is required to execute real MySQL acceptance",
            adminUrl != null && !adminUrl.isBlank());
        assertTrue("This suite requires MySQL rather than an in-memory substitute",
            adminUrl.startsWith("jdbc:mysql://"));
        mysqlUser = environmentOrDefault("LINE4_MYSQL_USER", "root");
        mysqlPassword = environmentOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line4_commission_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
             Statement statement = connection.createStatement())
        {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue("MySQL 8 or newer is required", connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            databaseUrl(adminUrl, database), mysqlUser, mysqlPassword);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        assertTrue("Line 3 migrations must be applied before line 4 acceptance",
            jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE success = 1 AND CAST(version AS UNSIGNED) BETWEEN 1 AND 99", Long.class) > 0);
        assertTrue("Line 4 migrations must be applied rather than a test-only schema",
            jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE success = 1 AND CAST(version AS UNSIGNED) BETWEEN 100 AND 199", Long.class) > 0);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliases(SysTbkAgentApply.class, SysUser.class);
        factoryBean.setMapperLocations(
            new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionFlowMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"),
            new ClassPathResource("mapper/system/SysAgentCommissionConfigMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionConfigMapper.xml"),
            new ClassPathResource("mapper/system/SysTbkAgentApplyMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        assertNotNull(factory);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory);
        accounts = sessions.getMapper(SysAgentCommissionAccountMapper.class);
        configs = sessions.getMapper(SysAgentCommissionConfigMapper.class);
        flows = sessions.getMapper(SysCommissionFlowMapper.class);
        orders = sessions.getMapper(SysOrderCommissionMapper.class);

        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        target = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(target, "accountMapper", accounts);
        ReflectionTestUtils.setField(target, "flowMapper", flows);
        ReflectionTestUtils.setField(target, "orderCommissionMapper", orders);
        ReflectionTestUtils.setField(target, "commissionConfigMapper", sessions.getMapper(SysCommissionConfigMapper.class));
        ReflectionTestUtils.setField(target, "agentApplyMapper", sessions.getMapper(SysTbkAgentApplyMapper.class));
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ICommissionCalculateService.class);
        proxy.addAdvice(interceptor);
        commissions = (ICommissionCalculateService) proxy.getProxy();

        accountService = new SysAgentCommissionAccountServiceImpl();
        ReflectionTestUtils.setField(accountService, "sysAgentCommissionAccountMapper", accounts);
    }

    @AfterClass
    public static void dropOnlyTheDatabaseCreatedByThisSuite() throws Exception
    {
        if (database != null)
        {
            assertTrue(database.matches("line4_commission_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
                 Statement statement = connection.createStatement())
            {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test
    public void calculatedOrderSettlesAndRefundsThroughRealMappersWithoutLosingAlliancePrecision()
    {
        long buyerId = nextId();
        long referrerId = nextId();
        insertAgent(referrerId, null);
        insertAgent(buyerId, invitation(referrerId));
        configureLifecycleRates();

        SysOrderCommission calculated = commissions.calculateCommission(
            "line4-lifecycle-" + nextId(), "tbk", "synthetic-item", "line4 synthetic order",
            money("99.99"), money("1.2399"), money("12.34"), buyerId, "2026-10-01 10:11:12");
        assertNotNull(calculated.getId());
        SysOrderCommission stored = orders.selectSysOrderCommissionById(calculated.getId());
        assertEquals(Long.valueOf(buyerId), stored.getBuyerUserId());
        assertEquals(Long.valueOf(buyerId), stored.getAgentUserId());
        assertEquals(Long.valueOf(referrerId), stored.getReferrerUserId());
        assertMoney("1.2399", stored.getEstimatedCommission());
        assertMoney("12.34", stored.getCommissionRate());
        assertEquals(calculated.getOrderTime(), stored.getOrderTime());
        assertEquals("unsettled", stored.getSettleStatus());
        assertMoney("0.62", stored.getSelfCommissionAmount());
        assertMoney("0.12", stored.getPromotionCommissionAmount());
        assertNull(accounts.selectByUserId(buyerId));
        assertNull(accounts.selectByUserId(referrerId));
        assertEquals(0L, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id = ?",
            stored.getId()));

        SysOrderCommission sameOrder = commissions.calculateCommission(
            stored.getOrderId(), "tbk", "synthetic-item", "line4 synthetic order",
            money("99.99"), money("1.2399"), money("12.34"), buyerId, "2026-10-01 10:11:12");
        assertEquals(stored.getId(), sameOrder.getId());
        assertEquals(1L, count("SELECT COUNT(*) FROM sys_order_commission WHERE order_id = ? AND platform_type = 'tbk'",
            stored.getOrderId()));

        assertTrue(commissions.settleOrder(stored.getId(), money("1.2501")));
        assertTrue(commissions.settleOrder(stored.getId(), money("1.2501")));
        assertLedger(buyerId, "0.63", 1);
        assertLedger(referrerId, "0.13", 1);
        assertAccountTotals(buyerId, "0.63", "0.63", "0.00");
        assertAccountTotals(referrerId, "0.13", "0.00", "0.13");
        stored = orders.selectSysOrderCommissionById(stored.getId());
        assertMoney("1.2501", stored.getActualCommission());
        assertEquals("settled", stored.getSettleStatus());
        assertEquals("confirmed", stored.getOrderStatus());
        assertNotNull(stored.getSettleTime());
        assertMoney("1.2501", jdbc.queryForObject(
            "SELECT actual_commission FROM sys_order_commission WHERE id = ?", BigDecimal.class, stored.getId()));

        final Long orderId = stored.getId();
        assertThrows(RuntimeException.class, () -> commissions.settleOrder(orderId, money("1.2502")));
        assertLedger(buyerId, "0.63", 1);
        assertLedger(referrerId, "0.13", 1);

        SysOrderCommission ordinaryUpdate = new SysOrderCommission();
        ordinaryUpdate.setId(orderId);
        ordinaryUpdate.setRemark("line4 synthetic audit note");
        ordinaryUpdate.setActualCommission(money("987.6543"));
        ordinaryUpdate.setSettleStatus("unsettled");
        assertEquals(1, orders.updateSysOrderCommission(ordinaryUpdate));
        stored = orders.selectSysOrderCommissionById(orderId);
        assertEquals("line4 synthetic audit note", stored.getRemark());
        assertEquals("settled", stored.getSettleStatus());
        assertMoney("1.2501", stored.getActualCommission());

        assertTrue(commissions.refundOrder(orderId));
        assertTrue(commissions.refundOrder(orderId));
        assertLedger(buyerId, "0.00", 2);
        assertLedger(referrerId, "0.00", 2);
        assertAccountTotals(buyerId, "0.00", "0.00", "0.00");
        assertAccountTotals(referrerId, "0.00", "0.00", "0.00");
        assertFlow(buyerId, orderId, "income_self", "0.63");
        assertFlow(buyerId, orderId, "refund_self", "-0.63");
        assertFlow(referrerId, orderId, "income_promotion", "0.13");
        assertFlow(referrerId, orderId, "refund_promotion", "-0.13");
        stored = orders.selectSysOrderCommissionById(orderId);
        assertEquals("refunded", stored.getOrderStatus());
        assertEquals("settled", stored.getSettleStatus());
        assertMoney("1.2501", stored.getActualCommission());
        assertThrows(RuntimeException.class, () -> commissions.settleOrder(orderId, money("1.2501")));
    }

    @Test
    public void calculatedOrderWithOneBeneficiaryReversesSelfAndPromotionStatisticsSeparately()
    {
        long userId = nextId();
        insertAgent(userId, invitation(userId));
        configureLifecycleRates();
        SysOrderCommission order = commissions.calculateCommission(
            "line4-same-beneficiary-" + nextId(), "tbk", "synthetic-item", "line4 synthetic order",
            money("99.99"), money("1.2399"), money("12.34"), userId, "2026-10-01 10:11:12");

        assertTrue(commissions.settleOrder(order.getId(), money("1.2501")));
        assertLedger(userId, "0.76", 2);
        assertAccountTotals(userId, "0.76", "0.63", "0.13");
        assertTrue(commissions.refundOrder(order.getId()));
        assertTrue(commissions.refundOrder(order.getId()));
        assertLedger(userId, "0.00", 4);
        assertAccountTotals(userId, "0.00", "0.00", "0.00");
        assertFlow(userId, order.getId(), "refund_self", "-0.63");
        assertFlow(userId, order.getId(), "refund_promotion", "-0.13");
    }

    @Test
    public void backOfficeOrderWithoutBuyerPersistsAgentAsBuyer()
    {
        long userId = nextId();
        SysOrderCommission order = new SysOrderCommission();
        order.setOrderId("line4-backoffice-" + nextId());
        order.setPlatformType("tbk");
        order.setAgentUserId(userId);
        order.setEstimatedCommission(money("1.2399"));
        order.setCommissionRate(money("12.34"));
        order.setRemark("line4 synthetic back-office order");

        assertEquals(1, orders.insertSysOrderCommission(order));
        SysOrderCommission stored = orders.selectSysOrderCommissionById(order.getId());
        assertEquals(Long.valueOf(userId), stored.getBuyerUserId());
        assertMoney("1.2399", stored.getEstimatedCommission());
        assertMoney("12.34", stored.getCommissionRate());
        assertEquals("line4 synthetic back-office order", stored.getRemark());
    }

    @Test
    public void oneHundredConcurrentSettlementsPreserveBalanceAndLedger() throws Exception
    {
        long userId = nextId();
        jdbc.update("INSERT INTO sys_agent_commission_account (user_id) VALUES (?)", userId);
        List<Long> orders = new ArrayList<>();
        for (int index = 0; index < 100; index++)
        {
            orders.add(insertOrder(userId, "100.00", null, "0.00"));
        }

        concurrently(100, index -> assertTrue(commissions.settleOrder(orders.get(index), money("1.23"))));

        assertLedger(userId, "123.00", 100);
        assertMoney("123.00", accounts.selectByUserId(userId).getTotalEarned());
        assertMoney("123.00", accounts.selectByUserId(userId).getSelfEarned());
        assertEquals(100L, count("SELECT COUNT(*) FROM sys_order_commission "
            + "WHERE buyer_user_id = ? AND settle_status = 'settled'", userId));
    }

    @Test
    public void concurrentFirstCreditsCreateOneAccount() throws Exception
    {
        long userId = nextId();
        List<Long> orders = new ArrayList<>();
        for (int index = 0; index < 20; index++)
        {
            orders.add(insertOrder(userId, "100.00", null, "0.00"));
        }

        concurrently(20, index -> assertTrue(commissions.settleOrder(orders.get(index), money("0.17"))));

        assertEquals(1L, count("SELECT COUNT(*) FROM sys_agent_commission_account WHERE user_id = ?", userId));
        assertLedger(userId, "3.40", 20);
    }

    @Test
    public void differentUsersCanPostTheirFirstCreditsConcurrently() throws Exception
    {
        List<Long> users = new ArrayList<>();
        List<Long> businessIds = new ArrayList<>();
        for (int index = 0; index < 40; index++)
        {
            users.add(nextId());
            businessIds.add(nextId());
        }

        concurrently(40, index -> post(users.get(index), "0.37", "income_self", businessIds.get(index)));

        for (Long userId : users)
        {
            assertEquals(1L, count("SELECT COUNT(*) FROM sys_agent_commission_account WHERE user_id = ?", userId));
            assertLedger(userId, "0.37", 1);
        }
    }

    @Test
    public void oneHundredRequestsForTheSameOrderCreditOnlyOnce() throws Exception
    {
        long userId = nextId();
        long orderId = insertOrder(userId, "100.00", null, "0.00");

        concurrently(100, index -> assertTrue(commissions.settleOrder(orderId, money("2.31"))));
        assertTrue(commissions.settleOrder(orderId, money("2.31")));

        assertLedger(userId, "2.31", 1);
        assertThrows(RuntimeException.class, () -> commissions.settleOrder(orderId, money("2.32")));
        assertLedger(userId, "2.31", 1);
    }

    @Test
    public void inconsistentOrIllegalOrderStatesRejectPostingWithoutChangingData()
    {
        long inconsistentUser = nextId();
        long inconsistentOrder = insertOrder(inconsistentUser, "100.00", null, "0.00");
        jdbc.update("UPDATE sys_order_commission SET settle_status = 'settled', actual_commission = 1.23 WHERE id = ?",
            inconsistentOrder);
        assertThrows(RuntimeException.class, () -> commissions.settleOrder(inconsistentOrder, money("1.23")));
        assertUnpostedOrder(inconsistentUser, inconsistentOrder, "pending", "settled", "1.23");

        long cancelledUser = nextId();
        long cancelledOrder = insertOrder(cancelledUser, "100.00", null, "0.00");
        jdbc.update("UPDATE sys_order_commission SET order_status = 'cancelled' WHERE id = ?", cancelledOrder);
        assertThrows(RuntimeException.class, () -> commissions.settleOrder(cancelledOrder, money("1.23")));
        assertUnpostedOrder(cancelledUser, cancelledOrder, "cancelled", "unsettled", "0.00");

        long unsettledUser = nextId();
        long unsettledOrder = insertOrder(unsettledUser, "100.00", null, "0.00");
        assertThrows(RuntimeException.class, () -> commissions.refundOrder(unsettledOrder));
        assertUnpostedOrder(unsettledUser, unsettledOrder, "pending", "unsettled", "0.00");
    }

    @Test
    public void repeatedBusinessKeyIsIdempotentInsideTheUnifiedPostingMethod() throws Exception
    {
        long userId = nextId();
        long businessId = nextId();

        concurrently(40, index -> post(userId, "4.56", "income_self", businessId));
        post(userId, "4.56", "income_self", businessId);
        assertLedger(userId, "4.56", 1);

        assertThrows(RuntimeException.class, () -> post(userId, "4.57", "income_self", businessId));
        assertLedger(userId, "4.56", 1);
    }

    @Test
    public void databaseUniqueConstraintRejectsASecondRowForTheSameBusinessKey()
    {
        long userId = nextId();
        long businessId = nextId();
        post(userId, "1.00", "income_self", businessId);

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "INSERT INTO sys_commission_flow "
                + "(user_id, flow_type, amount, balance_before, balance_after, order_commission_id, related_order_id) "
                + "VALUES (?, 'income_self', 1.00, 1.00, 2.00, ?, ?)",
            userId, businessId, Long.toString(businessId)));
        assertLedger(userId, "1.00", 1);
    }

    @Test
    public void userMoneyIsRoundedHalfUpToCents()
    {
        long userId = nextId();
        post(userId, "1.239", "income_self", nextId());
        assertLedger(userId, "1.24", 1);

        long settlementUser = nextId();
        long orderId = insertOrder(settlementUser, "50.00", null, "0.00");
        assertTrue(commissions.settleOrder(orderId, money("0.05")));
        assertLedger(settlementUser, "0.03", 1);
    }

    @Test
    public void flowInsertFailureRollsBackNewAccountAndOrderSettlement()
    {
        long userId = nextId();
        long orderId = insertOrder(userId, "100.00", null, "0.00");
        jdbc.execute("CREATE TRIGGER line4_reject_flow BEFORE INSERT ON sys_commission_flow "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'line4 synthetic write failure'");
        try
        {
            assertThrows(RuntimeException.class, () -> commissions.settleOrder(orderId, money("8.11")));
            assertNull(accounts.selectByUserId(userId));
            assertEquals(0L, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id = ?", userId));
            assertEquals("unsettled", jdbc.queryForObject(
                "SELECT settle_status FROM sys_order_commission WHERE id = ?", String.class, orderId));
            assertEquals("pending", jdbc.queryForObject(
                "SELECT order_status FROM sys_order_commission WHERE id = ?", String.class, orderId));
            assertMoney("0.00", jdbc.queryForObject(
                "SELECT actual_commission FROM sys_order_commission WHERE id = ?", BigDecimal.class, orderId));
        }
        finally
        {
            jdbc.execute("DROP TRIGGER line4_reject_flow");
        }

        assertTrue(commissions.settleOrder(orderId, money("8.11")));
        assertLedger(userId, "8.11", 1);
    }

    @Test
    public void refundAfterWithdrawalCanProduceNegativeBalanceAndRepeatsSafely()
    {
        long userId = nextId();
        long orderId = insertOrder(userId, "100.00", null, "0.00");
        assertTrue(commissions.settleOrder(orderId, money("5.00")));
        post(userId, "-5.00", "withdraw", nextId());

        assertTrue(commissions.refundOrder(orderId));
        assertTrue(commissions.refundOrder(orderId));

        assertLedger(userId, "-5.00", 3);
        assertMoney("5.00", accounts.selectByUserId(userId).getWithdrawn());
        assertMoney("0.00", accounts.selectByUserId(userId).getTotalEarned());
        assertEquals("refunded", jdbc.queryForObject(
            "SELECT order_status FROM sys_order_commission WHERE id = ?", String.class, orderId));
    }

    @Test
    public void invitedBuyersRefundReversesTheReferrersCommissionAsWell()
    {
        long buyerId = nextId();
        long referrerId = nextId();
        long orderId = insertOrder(buyerId, "60.00", referrerId, "10.00");
        assertTrue(commissions.settleOrder(orderId, money("10.00")));
        assertLedger(buyerId, "6.00", 1);
        assertLedger(referrerId, "1.00", 1);

        assertTrue(commissions.refundOrder(orderId));
        assertTrue(commissions.refundOrder(orderId));

        assertLedger(buyerId, "0.00", 2);
        assertLedger(referrerId, "0.00", 2);
    }

    @Test
    public void reciprocalReferrersCanSettleConcurrentlyWithoutCrossAccountDeadlocks() throws Exception
    {
        long firstUser = nextId();
        long secondUser = nextId();
        List<Long> orders = new ArrayList<>();
        for (int index = 0; index < 40; index++)
        {
            orders.add(index % 2 == 0
                ? insertOrder(firstUser, "60.00", secondUser, "10.00")
                : insertOrder(secondUser, "60.00", firstUser, "10.00"));
        }

        concurrently(40, index -> assertTrue(commissions.settleOrder(orders.get(index), money("1.00"))));

        assertLedger(firstUser, "14.00", 40);
        assertLedger(secondUser, "14.00", 40);
    }

    @Test
    public void oneBeneficiaryGetsSeparateIdempotentRefundsForSelfAndPromotionIncome()
    {
        long userId = nextId();
        long orderId = insertOrder(userId, "60.00", userId, "10.00");
        assertTrue(commissions.settleOrder(orderId, money("10.00")));
        assertLedger(userId, "7.00", 2);

        assertTrue(commissions.refundOrder(orderId));
        assertLedger(userId, "0.00", 4);
        assertEquals(1L, count("SELECT COUNT(*) FROM sys_commission_flow "
            + "WHERE user_id = ? AND flow_type = 'refund_self' AND order_commission_id = ?", userId, orderId));
        assertEquals(1L, count("SELECT COUNT(*) FROM sys_commission_flow "
            + "WHERE user_id = ? AND flow_type = 'refund_promotion' AND order_commission_id = ?", userId, orderId));
        assertTrue(commissions.refundOrder(orderId));
        assertLedger(userId, "0.00", 4);
    }

    @Test
    public void freezingAndUnfreezingChangesStatusWithoutChangingAnyMoney()
    {
        long userId = nextId();
        post(userId, "3.27", "income_self", nextId());
        post(userId, "1.19", "income_promotion", nextId());
        post(userId, "-0.51", "withdraw", nextId());
        SysAgentCommissionAccount before = accounts.selectByUserId(userId);

        assertEquals(1, accountService.freezeAccount(before.getId()));
        assertEquals("1", accounts.selectByUserId(userId).getStatus());
        assertSameMoney(before, accounts.selectByUserId(userId));
        assertEquals(1, accountService.unfreezeAccount(before.getId()));
        assertEquals("0", accounts.selectByUserId(userId).getStatus());
        assertSameMoney(before, accounts.selectByUserId(userId));
        assertLedger(userId, "3.95", 3);
    }

    @Test
    public void commissionConfigurationMapperPersistsCrudAndFilters()
    {
        SysAgentCommissionConfig first = config(901, "7.13", "2.17", "0");
        assertEquals(1, configs.insertSysAgentCommissionConfig(first));
        assertNotNull(first.getId());
        SysAgentCommissionConfig selected = configs.selectSysAgentCommissionConfigByLevel(901);
        assertEquals(first.getId(), selected.getId());
        assertMoney("7.13", selected.getSelfCommissionRate());
        assertMoney("2.17", selected.getPromotionCommissionRate());
        assertEquals("line4 synthetic configuration", selected.getRemark());

        first.setSelfCommissionRate(money("3.19"));
        first.setStatus("1");
        assertEquals(1, configs.updateSysAgentCommissionConfig(first));
        selected = configs.selectSysAgentCommissionConfigById(first.getId());
        assertMoney("3.19", selected.getSelfCommissionRate());
        assertEquals("1", selected.getStatus());
        SysAgentCommissionConfig filter = new SysAgentCommissionConfig();
        filter.setAgentLevel(901);
        filter.setStatus("1");
        assertEquals(1, configs.selectSysAgentCommissionConfigList(filter).size());
        filter.setStatus("0");
        assertTrue(configs.selectSysAgentCommissionConfigList(filter).isEmpty());
        assertEquals(1, configs.deleteSysAgentCommissionConfigById(first.getId()));
        assertNull(configs.selectSysAgentCommissionConfigById(first.getId()));

        SysAgentCommissionConfig second = config(902, "1.13", "0.17", "0");
        SysAgentCommissionConfig third = config(903, "2.13", "0.27", "1");
        configs.insertSysAgentCommissionConfig(second);
        configs.insertSysAgentCommissionConfig(third);
        assertEquals(2, configs.deleteSysAgentCommissionConfigByIds(new Long[] {second.getId(), third.getId()}));
        assertNull(configs.selectSysAgentCommissionConfigById(second.getId()));
        assertNull(configs.selectSysAgentCommissionConfigById(third.getId()));
    }

    @Test
    public void persistedCommissionFlowsRejectUpdateAndDeletion()
    {
        long userId = nextId();
        post(userId, "1.91", "income_self", nextId());
        Long flowId = jdbc.queryForObject("SELECT id FROM sys_commission_flow WHERE user_id = ?", Long.class, userId);
        SysCommissionFlow flow = flows.selectSysCommissionFlowById(flowId);
        flow.setAmount(money("9.99"));

        assertThrows(DataAccessException.class, () -> flows.updateSysCommissionFlow(flow));
        assertThrows(DataAccessException.class, () -> flows.deleteSysCommissionFlowById(flowId));
        assertThrows(DataAccessException.class, () -> flows.deleteSysCommissionFlowByIds(new Long[] {flowId}));
        assertLedger(userId, "1.91", 1);
    }

    @Test
    public void partiallyReversedIncomeRefundsOnlyTheRemainingIncome()
    {
        long buyer = nextId(), referrer = nextId();
        long order = insertOrder(buyer, "60.00", referrer, "10.00");
        assertTrue(commissions.settleOrder(order, money("10.00")));
        long income = flowId(buyer, order, "income_self");
        String key = "partial-" + order;
        Long reversal = commissions.reverseCommissionFlow(income, "冲正自购", key, 42L).getId();
        assertEquals(reversal, commissions.reverseCommissionFlow(income, "冲正自购", key, 42L).getId());
        assertTrue(commissions.refundOrder(order));
        assertTrue(commissions.refundOrder(order));
        assertEquals(reversal, commissions.reverseCommissionFlow(income, "冲正自购", key, 42L).getId());
        assertLedger(buyer, "0.00", 2);
        assertLedger(referrer, "0.00", 2);
        assertAccountTotals(buyer, "0.00", "0.00", "0.00");
        assertAccountTotals(referrer, "0.00", "0.00", "0.00");
        assertEquals(0L, count("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id = ? AND flow_type = 'refund_self'", order));
        assertFlow(referrer, order, "refund_promotion", "-1.00");
        assertEquals("refunded", jdbc.queryForObject("SELECT order_status FROM sys_order_commission WHERE id = ?", String.class, order));
    }

    @Test
    public void fullyReversedIncomeRefundsWithoutNewFlows()
    {
        long buyer = nextId(), referrer = nextId();
        long order = insertOrder(buyer, "60.00", referrer, "10.00");
        assertTrue(commissions.settleOrder(order, money("10.00")));
        commissions.reverseCommissionFlow(flowId(buyer, order, "income_self"), "冲正自购", "full-self-" + order, 42L);
        commissions.reverseCommissionFlow(flowId(referrer, order, "income_promotion"), "冲正推广", "full-promo-" + order, 42L);
        long before = count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id IN (?, ?)", buyer, referrer);
        assertTrue(commissions.refundOrder(order));
        assertTrue(commissions.refundOrder(order));
        assertEquals(before, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id IN (?, ?)", buyer, referrer));
        assertEquals("refunded", jdbc.queryForObject("SELECT order_status FROM sys_order_commission WHERE id = ?", String.class, order));
        assertLedger(buyer, "0.00", 2);
        assertLedger(referrer, "0.00", 2);
        assertAccountTotals(buyer, "0.00", "0.00", "0.00");
        assertAccountTotals(referrer, "0.00", "0.00", "0.00");
    }

    @Test
    public void concurrentRefundAndReversalDebitIncomeOnlyOnce() throws Exception
    {
        long buyer = nextId();
        long order = insertOrder(buyer, "100.00", null, "0.00");
        assertTrue(commissions.settleOrder(order, money("10.00")));
        long income = flowId(buyer, order, "income_self");
        concurrently(2, index -> {
            if (index == 0)
            {
                try
                {
                    commissions.reverseCommissionFlow(income, "并发冲正", "race-" + order, 42L);
                }
                catch (com.ruoyi.common.exception.ServiceException expected)
                {
                    assertTrue(expected.getMessage().contains("退款"));
                }
            }
            else
            {
                assertTrue(commissions.refundOrder(order));
            }
        });
        assertEquals("refunded", jdbc.queryForObject("SELECT order_status FROM sys_order_commission WHERE id = ?", String.class, order));
        assertLedger(buyer, "0.00", 2);
        assertAccountTotals(buyer, "0.00", "0.00", "0.00");
    }

    @Test
    public void refundedIncomeAndRefundFlowsCannotBeReversedButWithdrawalsCan()
    {
        long buyer = nextId(), referrer = nextId();
        long order = insertOrder(buyer, "60.00", referrer, "10.00");
        assertTrue(commissions.settleOrder(order, money("10.00")));
        assertTrue(commissions.refundOrder(order));
        assertThrows(RuntimeException.class, () -> commissions.reverseCommissionFlow(flowId(buyer, order, "income_self"), "退款后", "after-refund-" + order, 42L));
        assertThrows(RuntimeException.class, () -> commissions.reverseCommissionFlow(flowId(referrer, order, "income_promotion"), "退款后", "after-promo-" + order, 42L));
        assertThrows(RuntimeException.class, () -> commissions.reverseCommissionFlow(flowId(buyer, order, "refund_self"), "退款流水", "refund-self-" + order, 42L));
        assertThrows(RuntimeException.class, () -> commissions.reverseCommissionFlow(flowId(referrer, order, "refund_promotion"), "退款流水", "refund-promo-" + order, 42L));
        long legacyOrder = nextId();
        jdbc.update("INSERT INTO sys_commission_flow (user_id, flow_type, amount, balance_before, balance_after, order_commission_id) VALUES (?, 'refund', 0.00, 0.00, 0.00, ?)", buyer, legacyOrder);
        assertThrows(RuntimeException.class, () -> commissions.reverseCommissionFlow(flowId(buyer, legacyOrder, "refund"), "旧退款流水", "legacy-" + order, 42L));
        long withdrawalOrder = nextId();
        post(buyer, "-1.00", "withdraw", withdrawalOrder);
        SysCommissionFlow reversal = commissions.reverseCommissionFlow(flowId(buyer, withdrawalOrder, "withdraw"), "撤销提现", "withdraw-" + order, 42L);
        assertThrows(RuntimeException.class, () -> commissions.reverseCommissionFlow(reversal.getId(), "冲正流水", "reverse-reversal-" + order, 42L));
        assertLedger(buyer, "0.00", 5);
        assertLedger(referrer, "0.00", 2);
    }

    private static long flowId(long userId, long orderId, String type)
    {
        return jdbc.queryForObject("SELECT id FROM sys_commission_flow WHERE user_id = ? AND order_commission_id = ? AND flow_type = ?", Long.class, userId, orderId, type);
    }

    private static String invitation(long userId)
    {
        return "test-" + userId;
    }

    private static void insertAgent(long userId, String referrerInvitation)
    {
        jdbc.update("INSERT INTO sys_tbk_agent_apply "
                + "(user_id, mini_app_id, real_name, phone, wechat, invitation_code, referrer_invitation_code, status) "
                + "VALUES (?, ?, 'line4 synthetic agent', 'test-phone', 'test-wechat', ?, ?, '1')",
            userId, "line4-synthetic-miniapp-" + userId, invitation(userId), referrerInvitation);
    }

    private static void configureLifecycleRates()
    {
        jdbc.update("INSERT INTO sys_commission_config (config_key, config_value) VALUES ('self_rate', 50.00) "
            + "ON DUPLICATE KEY UPDATE config_value = 50.00, status = '0'");
        jdbc.update("INSERT INTO sys_commission_config (config_key, config_value) VALUES ('promotion_rate', 10.00) "
            + "ON DUPLICATE KEY UPDATE config_value = 10.00, status = '0'");
    }

    private static void assertAccountTotals(long userId, String total, String self, String promotion)
    {
        SysAgentCommissionAccount account = accounts.selectByUserId(userId);
        assertNotNull(account);
        assertMoney(total, account.getTotalEarned());
        assertMoney(self, account.getSelfEarned());
        assertMoney(promotion, account.getPromotionEarned());
        assertMoney("0.00", account.getWithdrawn());
        assertMoney("0.00", account.getFrozenAmount());
    }

    private static void assertFlow(long userId, long orderId, String flowType, String amount)
    {
        assertEquals(1L, count("SELECT COUNT(*) FROM sys_commission_flow "
            + "WHERE user_id = ? AND order_commission_id = ? AND flow_type = ?", userId, orderId, flowType));
        assertMoney(amount, jdbc.queryForObject("SELECT amount FROM sys_commission_flow "
            + "WHERE user_id = ? AND order_commission_id = ? AND flow_type = ?",
            BigDecimal.class, userId, orderId, flowType));
    }

    private static long insertOrder(long buyerId, String selfRate, Long referrerId, String promotionRate)
    {
        long id = nextId();
        jdbc.update("INSERT INTO sys_order_commission "
                + "(id, order_id, platform_type, agent_user_id, buyer_user_id, self_commission_rate, "
                + "has_referrer, referrer_user_id, promotion_commission_rate, order_status, settle_status, actual_commission) "
                + "VALUES (?, ?, 'tbk', ?, ?, ?, ?, ?, ?, 'pending', 'unsettled', 0.00)",
            id, "line4-synthetic-" + id, buyerId, buyerId, money(selfRate),
            referrerId == null ? "0" : "1", referrerId, money(promotionRate));
        return id;
    }

    /** Exercise the internal entry point with the real surrounding transaction. */
    private static void post(long userId, String amount, String flowType, long businessId)
    {
        transactions.executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(target,
            "updateUserAccount", userId, money(amount), flowType, businessId, "line4 synthetic posting"));
    }

    private static void assertLedger(long userId, String expectedBalance, int expectedCount)
    {
        SysAgentCommissionAccount account = accounts.selectByUserId(userId);
        assertNotNull(account);
        assertMoney(expectedBalance, account.getBalance());
        List<LedgerRow> rows = jdbc.query("SELECT balance_before, amount, balance_after "
                + "FROM sys_commission_flow WHERE user_id = ? ORDER BY id",
            (result, row) -> new LedgerRow(result.getBigDecimal(1), result.getBigDecimal(2), result.getBigDecimal(3)),
            userId);
        assertEquals(expectedCount, rows.size());
        BigDecimal runningBalance = BigDecimal.ZERO;
        for (LedgerRow row : rows)
        {
            assertEquals("Ledger entries must form an unbroken balance chain", 0,
                runningBalance.compareTo(row.before()));
            runningBalance = runningBalance.add(row.amount());
            assertEquals("Each balance change must match its immutable flow", 0,
                runningBalance.compareTo(row.after()));
        }
        assertMoney(expectedBalance, runningBalance);
    }

    private static void assertSameMoney(SysAgentCommissionAccount expected, SysAgentCommissionAccount actual)
    {
        assertEquals(0, expected.getBalance().compareTo(actual.getBalance()));
        assertEquals(0, expected.getTotalEarned().compareTo(actual.getTotalEarned()));
        assertEquals(0, expected.getSelfEarned().compareTo(actual.getSelfEarned()));
        assertEquals(0, expected.getPromotionEarned().compareTo(actual.getPromotionEarned()));
        assertEquals(0, expected.getWithdrawn().compareTo(actual.getWithdrawn()));
        assertEquals(0, expected.getFrozenAmount().compareTo(actual.getFrozenAmount()));
    }

    private static void assertUnpostedOrder(long userId, long orderId, String orderStatus,
        String settlementStatus, String actualCommission)
    {
        assertNull(accounts.selectByUserId(userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id = ?", userId));
        assertEquals(orderStatus, jdbc.queryForObject(
            "SELECT order_status FROM sys_order_commission WHERE id = ?", String.class, orderId));
        assertEquals(settlementStatus, jdbc.queryForObject(
            "SELECT settle_status FROM sys_order_commission WHERE id = ?", String.class, orderId));
        assertMoney(actualCommission, jdbc.queryForObject(
            "SELECT actual_commission FROM sys_order_commission WHERE id = ?", BigDecimal.class, orderId));
    }

    private static SysAgentCommissionConfig config(int level, String selfRate, String promotionRate, String status)
    {
        SysAgentCommissionConfig config = new SysAgentCommissionConfig();
        config.setAgentLevel(level);
        config.setSelfCommissionRate(money(selfRate));
        config.setPromotionCommissionRate(money(promotionRate));
        config.setStatus(status);
        config.setRemark("line4 synthetic configuration");
        config.setCreateBy("line4-test");
        return config;
    }

    private static void concurrently(int count, ConcurrentAction action) throws Exception
    {
        ExecutorService workers = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> pending = new ArrayList<>();
        try
        {
            for (int index = 0; index < count; index++)
            {
                int item = index;
                pending.add(workers.submit(() -> {
                    assertTrue("Concurrent start timed out", start.await(30, TimeUnit.SECONDS));
                    action.run(item);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : pending)
            {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        finally
        {
            workers.shutdownNow();
            assertTrue("Database workers did not terminate", workers.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private static long count(String sql, Object... arguments)
    {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private static void assertMoney(String expected, BigDecimal actual)
    {
        assertNotNull(actual);
        assertEquals(expected + " expected, got " + actual, 0, money(expected).compareTo(actual));
    }

    private static BigDecimal money(String value)
    {
        return new BigDecimal(value);
    }

    private static long nextId()
    {
        return IDS.incrementAndGet();
    }

    private static String environmentOrDefault(String name, String fallback)
    {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static String databaseUrl(String url, String schema)
    {
        int query = url.indexOf('?');
        String path = query == -1 ? url : url.substring(0, query);
        String options = query == -1 ? "" : url.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        return (slash == -1 ? path : path.substring(0, slash)) + "/" + schema + options;
    }

    private record LedgerRow(BigDecimal before, BigDecimal amount, BigDecimal after) { }

    @FunctionalInterface
    private interface ConcurrentAction
    {
        void run(int index) throws Exception;
    }
}
