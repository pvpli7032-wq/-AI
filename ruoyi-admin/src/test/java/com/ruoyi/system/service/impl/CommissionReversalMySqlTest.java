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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.domain.SysCommissionFlow;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.mapper.SysCommissionFlowMapper;
import com.ruoyi.system.service.ICommissionCalculateService;
import static org.junit.Assert.*;

/** Full reversal acceptance against MySQL 8, production migrations and mappings. */
public class CommissionReversalMySqlTest
{
    private static final AtomicLong IDS = new AtomicLong(9200000000L);
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static SysAgentCommissionAccountMapper accounts;
    private static CommissionCalculateServiceImpl target;
    private static ICommissionCalculateService service;
    private static TransactionTemplate transactions;

    @BeforeClass
    public static void setup() throws Exception
    {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line7_reversal_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement())
        {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        String databaseUrl = (slash < 0 ? path : path.substring(0, slash)) + "/" + database + options;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(databaseUrl, user, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMapperLocations(new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionFlowMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"));
        SqlSessionFactory factory = bean.getObject();
        assertNotNull(factory);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory);
        accounts = sessions.getMapper(SysAgentCommissionAccountMapper.class);
        target = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(target, "accountMapper", accounts);
        ReflectionTestUtils.setField(target, "flowMapper", sessions.getMapper(SysCommissionFlowMapper.class));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ICommissionCalculateService.class);
        proxy.addAdvice(interceptor);
        service = (ICommissionCalculateService) proxy.getProxy();
    }

    @AfterClass
    public static void cleanup() throws Exception
    {
        if (database != null)
        {
            assertTrue(database.matches("line7_reversal_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement())
            {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test
    public void reversesOriginalOnceAndMakesRepeatIdempotent()
    {
        long userId = IDS.incrementAndGet();
        long originalId = post(userId, "10.00", "income_self");
        String key = "line7-reverse-" + IDS.incrementAndGet();
        SysCommissionFlow reversed = service.reverseCommissionFlow(originalId, "测试冲正原因", key, 42L);
        assertNotNull(reversed);
        assertNotNull(reversed.getId());
        assertEquals(0, new BigDecimal("-10.00").compareTo(reversed.getAmount()));
        assertEquals(Long.valueOf(originalId), jdbc.queryForObject("SELECT original_flow_id FROM sys_commission_flow WHERE id = ?", Long.class, reversed.getId()));
        assertEquals(Long.valueOf(42L), jdbc.queryForObject("SELECT operator_id FROM sys_commission_flow WHERE id = ?", Long.class, reversed.getId()));
        assertEquals("测试冲正原因", jdbc.queryForObject("SELECT reversal_reason FROM sys_commission_flow WHERE id = ?", String.class, reversed.getId()));
        assertEquals(key, jdbc.queryForObject("SELECT idempotency_key FROM sys_commission_flow WHERE id = ?", String.class, reversed.getId()));
        assertEquals("reversal", jdbc.queryForObject("SELECT flow_type FROM sys_commission_flow WHERE id = ?", String.class, reversed.getId()));
        SysAgentCommissionAccount account = accounts.selectByUserId(userId);
        assertMoney("0.00", account.getBalance());
        assertMoney("0.00", account.getTotalEarned());
        assertMoney("0.00", account.getSelfEarned());
        assertEquals(reversed.getId(), service.reverseCommissionFlow(originalId, "测试冲正原因", key, 42L).getId());
        assertThrows(RuntimeException.class, () -> service.reverseCommissionFlow(originalId, "另一个请求", key + "-other", 42L));
        long otherUserId = IDS.incrementAndGet();
        long otherOriginalId = post(otherUserId, "4.00", "income_self");
        assertThrows(RuntimeException.class, () -> service.reverseCommissionFlow(otherOriginalId, "重复键跨流水", key, 42L));
        assertMoney("4.00", accounts.selectByUserId(otherUserId).getBalance());
        assertEquals(1L, countFlows(otherUserId));
        assertThrows(RuntimeException.class, () -> service.reverseCommissionFlow(reversed.getId(), "不能冲正冲正", key + "-reversal", 42L));
        assertEquals(2L, countFlows(userId));
    }

    @Test
    public void concurrentSameOriginalAndKeyCreatesOnlyOneReversal() throws Exception
    {
        long userId = IDS.incrementAndGet();
        long originalId = post(userId, "3.21", "income_promotion");
        String key = "line7-concurrent-" + IDS.incrementAndGet();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try
        {
            for (int i = 0; i < 20; i++)
            {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.reverseCommissionFlow(originalId, "并发冲正", key, 42L).getId();
                }));
            }
            start.countDown();
            Long id = futures.get(0).get(30, TimeUnit.SECONDS);
            for (Future<Long> future : futures) assertEquals(id, future.get(30, TimeUnit.SECONDS));
            assertEquals(2L, countFlows(userId));
            SysAgentCommissionAccount account = accounts.selectByUserId(userId);
            assertMoney("0.00", account.getBalance());
            assertMoney("0.00", account.getTotalEarned());
            assertMoney("0.00", account.getPromotionEarned());
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    public void concurrentDifferentKeysForOneOriginalAllowOnlyOneReversal() throws Exception
    {
        long userId = IDS.incrementAndGet();
        long originalId = post(userId, "7.12", "income_self");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Boolean> first = executor.submit(() -> reverseDifferentKey(start, originalId, "line7-key-a-" + originalId));
            Future<Boolean> second = executor.submit(() -> reverseDifferentKey(start, originalId, "line7-key-b-" + originalId));
            start.countDown();
            int successes = (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(2L, countFlows(userId));
            assertMoney("0.00", accounts.selectByUserId(userId).getBalance());
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private static boolean reverseDifferentKey(CountDownLatch start, long originalId, String key) throws Exception
    {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try
        {
            service.reverseCommissionFlow(originalId, "不同键并发", key, 42L);
            return true;
        }
        catch (RuntimeException expected)
        {
            return false;
        }
    }

    @Test
    public void reversalCanCreateNegativeBalanceAfterWithdrawal()
    {
        long userId = IDS.incrementAndGet();
        long originalId = post(userId, "10.00", "income_self");
        post(userId, "-8.00", "withdraw");
        service.reverseCommissionFlow(originalId, "余额不足时冲正", "line7-negative-" + IDS.incrementAndGet(), 42L);
        SysAgentCommissionAccount account = accounts.selectByUserId(userId);
        assertMoney("-8.00", account.getBalance());
        assertMoney("0.00", account.getTotalEarned());
        assertMoney("0.00", account.getSelfEarned());
        assertMoney("8.00", account.getWithdrawn());
        assertEquals(3L, countFlows(userId));
    }

    @Test
    public void sameKeyAcrossDifferentUsersAllowsOnlyOneReversal() throws Exception
    {
        long user1 = IDS.incrementAndGet(), user2 = IDS.incrementAndGet();
        long original1 = post(user1, "6.00", "income_self");
        long original2 = post(user2, "6.00", "income_self");
        String key = "line7-cross-user-" + IDS.incrementAndGet();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Boolean> first = executor.submit(() -> reverseDifferentKey(start, original1, key));
            Future<Boolean> second = executor.submit(() -> reverseDifferentKey(start, original2, key));
            start.countDown();
            boolean one = first.get(30, TimeUnit.SECONDS), two = second.get(30, TimeUnit.SECONDS);
            assertTrue(one != two);
            assertMoney(one ? "0.00" : "6.00", accounts.selectByUserId(user1).getBalance());
            assertMoney(two ? "0.00" : "6.00", accounts.selectByUserId(user2).getBalance());
            assertEquals(one ? 2L : 1L, countFlows(user1));
            assertEquals(two ? 2L : 1L, countFlows(user2));
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    public void reversesRefundTypesAndWithdrawalStatistics()
    {
        long userId = IDS.incrementAndGet();
        post(userId, "10.00", "income_self");
        post(userId, "5.00", "income_promotion");
        long selfRefund = post(userId, "-2.00", "refund_self");
        long promotionRefund = post(userId, "-1.00", "refund_promotion");
        long withdrawal = post(userId, "-3.00", "withdraw");
        service.reverseCommissionFlow(selfRefund, "撤销自购退款", "line7-refund-self-" + userId, 42L);
        service.reverseCommissionFlow(promotionRefund, "撤销推广退款", "line7-refund-promo-" + userId, 42L);
        service.reverseCommissionFlow(withdrawal, "撤销提现", "line7-withdraw-" + userId, 42L);
        SysAgentCommissionAccount account = accounts.selectByUserId(userId);
        assertMoney("15.00", account.getBalance());
        assertMoney("15.00", account.getTotalEarned());
        assertMoney("10.00", account.getSelfEarned());
        assertMoney("5.00", account.getPromotionEarned());
        assertMoney("0.00", account.getWithdrawn());
        assertEquals(8L, countFlows(userId));
    }

    @Test
    public void legacyRefundWithoutOrderIdRestoresOnlyBalanceAndTotal()
    {
        long userId = IDS.incrementAndGet();
        post(userId, "10.00", "income_self");
        post(userId, "5.00", "income_promotion");
        // Emulate the old refund implementation and its historical missing order ID.
        jdbc.update("UPDATE sys_agent_commission_account SET balance=13.00, total_earned=13.00 WHERE user_id=?", userId);
        jdbc.update("INSERT INTO sys_commission_flow (user_id, flow_type, amount, balance_before, balance_after, order_commission_id) VALUES (?, 'refund', -2.00, 15.00, 13.00, NULL)", userId);
        long originalId = jdbc.queryForObject("SELECT id FROM sys_commission_flow WHERE user_id=? AND flow_type='refund'", Long.class, userId);
        SysCommissionFlow reversal = service.reverseCommissionFlow(originalId, "历史退款冲正", "line7-legacy-" + userId, 42L);
        assertNotNull(reversal.getId());
        assertEquals(Long.valueOf(originalId), jdbc.queryForObject("SELECT original_flow_id FROM sys_commission_flow WHERE id=?", Long.class, reversal.getId()));
        SysAgentCommissionAccount account = accounts.selectByUserId(userId);
        assertMoney("15.00", account.getBalance());
        assertMoney("15.00", account.getTotalEarned());
        assertMoney("10.00", account.getSelfEarned());
        assertMoney("5.00", account.getPromotionEarned());
        assertEquals(4L, countFlows(userId));
    }

    private static long post(long userId, String amount, String type)
    {
        long businessId = IDS.incrementAndGet();
        transactions.executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(target,
            "updateUserAccount", userId, new BigDecimal(amount), type, businessId, "line7 synthetic posting"));
        return jdbc.queryForObject("SELECT id FROM sys_commission_flow WHERE user_id = ? AND flow_type = ? AND order_commission_id = ?", Long.class, userId, type, businessId);
    }

    private static long countFlows(long userId)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id = ?", Long.class, userId);
    }

    private static void assertMoney(String expected, BigDecimal actual)
    {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
