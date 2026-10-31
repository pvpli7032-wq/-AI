package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.system.service.ISysOrderCommissionService;
import static org.junit.Assert.*;

/** Exercises real migrated schema, mapper SQL and Spring transactions against MySQL. */
public class OrderMutationMySqlTest
{
    private static String url, user, password, db;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ISysOrderCommissionService service;

    @BeforeClass
    public static void setup() throws Exception
    {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL is required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        db = "line7_order_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement())
        {
            assertEquals("MySQL", c.getMetaData().getDatabaseProductName());
            st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        String query = q < 0 ? "" : url.substring(q);
        int slash = base.indexOf('/', "jdbc:mysql://".length());
        dataSource = new DriverManagerDataSource((slash < 0 ? base : base.substring(0, slash)) + "/" + db + query, user, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMapperLocations(new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"));
        SqlSessionFactory factory = bean.getObject();
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        SysOrderCommissionServiceImpl target = new SysOrderCommissionServiceImpl();
        ReflectionTestUtils.setField(target, "accountMapper", session.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(target, "sysOrderCommissionMapper", session.getMapper(SysOrderCommissionMapper.class));
        TransactionInterceptor tx = new TransactionInterceptor();
        tx.setTransactionManager(new DataSourceTransactionManager(dataSource));
        tx.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ISysOrderCommissionService.class);
        proxy.addAdvice(tx);
        service = (ISysOrderCommissionService) proxy.getProxy();
    }

    @AfterClass
    public static void cleanup() throws Exception
    {
        if (db != null)
        {
            assertTrue(db.matches("line7_order_[0-9a-f]{32}"));
            try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement())
            { st.execute("DROP DATABASE IF EXISTS `" + db + "`"); }
        }
    }

    private long order(String status, String settled)
    {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000000000000L) + 1000000000000000L;
        jdbc.update("INSERT INTO sys_order_commission (id,order_id,platform_type,agent_user_id,buyer_user_id,order_status,settle_status,order_amount) VALUES (?,?, 'tbk', 9001, 9001, ?, ?, 10.00)", id, "line7-order-" + id, status, settled);
        return id;
    }

    private void flow(long id, boolean legacy)
    {
        jdbc.update("INSERT INTO sys_commission_flow (user_id,flow_type,amount,balance_before,balance_after,order_commission_id,related_order_id) VALUES (9001,'income_self',1,0,1,?,?)", legacy ? null : id, Long.toString(id));
    }

    @Test
    public void unsettledWithoutFlowsAllowsMetadataAndDeleteButRejectsMoney()
    {
        long id = order("pending", "unsettled");
        SysOrderCommission patch = new SysOrderCommission(); patch.setId(id); patch.setOrderAmount(new BigDecimal("11.00"));
        assertThrows(ServiceException.class, () -> service.updateSysOrderCommission(patch));
        assertEquals(new BigDecimal("10.00"), jdbc.queryForObject("SELECT order_amount FROM sys_order_commission WHERE id=?", BigDecimal.class, id));
        patch.setOrderAmount(null); patch.setItemTitle("line7 test title");
        assertEquals(1, service.updateSysOrderCommission(patch));
        assertEquals("line7 test title", jdbc.queryForObject("SELECT item_title FROM sys_order_commission WHERE id=?", String.class, id));
        assertEquals(1, service.deleteSysOrderCommissionById(id));
    }

    @Test
    public void settledOrFlowOrdersPermitOnlyRemarkAndCannotBeDeleted()
    {
        long settled = order("confirmed", "settled");
        long legacyFlow = order("pending", "unsettled"); flow(legacyFlow, true);
        for (long id : new long[] {settled, legacyFlow})
        {
            SysOrderCommission patch = new SysOrderCommission(); patch.setId(id); patch.setRemark("audited note");
            assertEquals(1, service.updateSysOrderCommission(patch));
            assertEquals("audited note", jdbc.queryForObject("SELECT remark FROM sys_order_commission WHERE id=?", String.class, id));
            patch.setBuyerUserId(9999L);
            assertThrows(ServiceException.class, () -> service.updateSysOrderCommission(patch));
            patch.setBuyerUserId(null); patch.setOrderStatus("pending");
            if (id == settled) assertThrows(ServiceException.class, () -> service.updateSysOrderCommission(patch));
            assertThrows(ServiceException.class, () -> service.deleteSysOrderCommissionById(id));
            assertEquals(Long.valueOf(9001), jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?", Long.class, id));
        }
    }

    @Test
    public void batchDeleteIsAtomicWhenOneOrderHasLedger()
    {
        long empty = order("pending", "unsettled"), protectedId = order("pending", "unsettled");
        flow(protectedId, false);
        assertThrows(ServiceException.class, () -> service.deleteSysOrderCommissionByIds(new Long[] {empty, protectedId}));
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT count(*) FROM sys_order_commission WHERE id IN (?,?)", Long.class, empty, protectedId));
    }

    @Test
    public void updateWaitsForOrderLockAndSeesConcurrentSettlement() throws Exception
    {
        long id = order("pending", "unsettled");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection(); Statement st = lock.createStatement())
        {
            lock.setAutoCommit(false);
            st.executeQuery("SELECT id FROM sys_order_commission WHERE id = " + id + " FOR UPDATE").close();
            Future<?> attempt = executor.submit(() -> {
                SysOrderCommission patch = new SysOrderCommission(); patch.setId(id); patch.setBuyerUserId(7777L);
                assertThrows(ServiceException.class, () -> service.updateSysOrderCommission(patch));
            });
            Thread.sleep(200);
            assertFalse("update must wait for settlement lock", attempt.isDone());
            st.executeUpdate("UPDATE sys_order_commission SET order_status='confirmed', settle_status='settled' WHERE id=" + id);
            lock.commit();
            attempt.get(15, TimeUnit.SECONDS);
            assertEquals(Long.valueOf(9001), jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?", Long.class, id));
        }
        finally { executor.shutdownNow(); }
    }
}
