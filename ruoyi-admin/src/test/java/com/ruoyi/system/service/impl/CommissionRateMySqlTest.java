package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysCommissionConfig;
import com.ruoyi.system.mapper.SysCommissionConfigMapper;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.system.mapper.SysTbkAgentApplyMapper;
import com.ruoyi.system.domain.SysTbkAgentApply;
import com.ruoyi.system.service.ISysCommissionConfigService;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real MySQL and actual Flyway migrations; uses a dedicated temporary database. */
public class CommissionRateMySqlTest
{
    private static String url, user, password, database;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ISysCommissionConfigService service;
    private static SysCommissionConfigMapper mapper;
    private static long initialSeedCount;
    private static long initialDisabledCount;
    private static BigDecimal initialSeedSum;

    @BeforeClass
    public static void setup() throws Exception
    {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 is required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line7_rates_" + UUID.randomUUID().toString().replace("-", "");
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
        dataSource = new DriverManagerDataSource(databaseUrl, user, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        initialSeedCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_config WHERE config_key IN ('self_rate','promotion_rate')", Long.class);
        initialDisabledCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_config WHERE config_key IN ('self_rate','promotion_rate') AND status = '1'", Long.class);
        initialSeedSum = jdbc.queryForObject("SELECT SUM(config_value) FROM sys_commission_config WHERE config_key IN ('self_rate','promotion_rate')", BigDecimal.class);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new ClassPathResource("mapper/system/SysCommissionConfigMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        assertNotNull(factory);
        mapper = new SqlSessionTemplate(factory).getMapper(SysCommissionConfigMapper.class);
        SysCommissionConfigServiceImpl target = new SysCommissionConfigServiceImpl();
        ReflectionTestUtils.setField(target, "sysCommissionConfigMapper", mapper);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ISysCommissionConfigService.class);
        proxy.addAdvice(interceptor);
        service = (ISysCommissionConfigService) proxy.getProxy();
    }

    @AfterClass
    public static void cleanup() throws Exception
    {
        if (database != null)
        {
            assertTrue(database.matches("line7_rates_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement())
            {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Before
    public void reset()
    {
        jdbc.update("UPDATE sys_commission_config SET config_value = 0 WHERE config_key IN ('self_rate', 'promotion_rate')");
    }

    @Test
    public void migrationSeedsBothRatesAndPreservesExistingValuesOnRepeat() throws Exception
    {
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '90' AND success = 1", Long.class).longValue());
        assertEquals(2L, initialSeedCount);
        assertEquals(2L, initialDisabledCount);
        assertNotNull(initialSeedSum);
        assertEquals(0, BigDecimal.ZERO.compareTo(initialSeedSum));
        jdbc.update("UPDATE sys_commission_config SET config_value = 37 WHERE config_key = 'self_rate'");
        try (Connection connection = dataSource.getConnection())
        {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V90__seed_commission_rates.sql"));
        }
        assertEquals(0, new BigDecimal("37").compareTo(jdbc.queryForObject("SELECT config_value FROM sys_commission_config WHERE config_key = 'self_rate'", BigDecimal.class)));
    }

    @Test
    public void concurrentIndependentWritesCannotBreakTheCombinedLimit() throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            for (int iteration = 0; iteration < 20; iteration++)
            {
                reset();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> first = executor.submit(() -> update(start, "self_rate"));
                Future<Boolean> second = executor.submit(() -> update(start, "promotion_rate"));
                start.countDown();
                int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals("Exactly one 70% change must succeed", 1, successes);
                BigDecimal total = jdbc.queryForObject("SELECT SUM(config_value) FROM sys_commission_config WHERE config_key IN ('self_rate','promotion_rate')", BigDecimal.class);
                assertEquals(0, new BigDecimal("70").compareTo(total));
            }
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    public void placeholdersPreventCalculationUntilBothZeroRatesAreSaved()
    {
        jdbc.update("UPDATE sys_commission_config SET status = '1', config_value = 0 WHERE config_key IN ('self_rate','promotion_rate')");
        CommissionCalculateServiceImpl calculate = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(calculate, "commissionConfigMapper", mapper);
        SysOrderCommissionMapper orders = mock(SysOrderCommissionMapper.class);
        SysTbkAgentApplyMapper agents = mock(SysTbkAgentApplyMapper.class);
        when(agents.selectByUserId(1L)).thenReturn(new SysTbkAgentApply());
        ReflectionTestUtils.setField(calculate, "orderCommissionMapper", orders);
        ReflectionTestUtils.setField(calculate, "agentApplyMapper", agents);
        assertThrows(ServiceException.class, () -> calculate.calculateCommission("placeholder", "tbk", "item", "item",
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 1L, "2026-01-01 00:00:00"));
        SysCommissionConfig config = new SysCommissionConfig();
        config.setConfigKey("self_rate");
        config.setConfigValue(BigDecimal.ZERO);
        assertEquals(1, service.updateSysCommissionConfig(config));
        assertThrows(ServiceException.class, calculate::getSelfCommissionRate);
        config.setConfigKey("promotion_rate");
        assertEquals(1, service.updateSysCommissionConfig(config));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_config WHERE config_key IN ('self_rate','promotion_rate') AND status = '0'", Long.class).longValue());
        assertEquals(0, BigDecimal.ZERO.compareTo(calculate.calculateCommission("enabled", "tbk", "item", "item",
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 1L, "2026-01-01 00:00:00").getSelfCommissionAmount()));
    }

    @Test
    public void migrationLeavesOperatorEditedRemarksAndValuesUntouched() throws Exception
    {
        jdbc.update("UPDATE sys_commission_config SET status = '0', config_value = 37, remark = '运营配置' WHERE config_key = 'self_rate'");
        jdbc.update("UPDATE sys_commission_config SET status = '0', config_value = 0, update_time = NULL, update_by = '', remark = '占位配置，上线前设置推广比例' WHERE config_key = 'promotion_rate'");
        try (Connection connection = dataSource.getConnection())
        {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V92__disable_placeholder_commission_rates.sql"));
        }
        assertEquals("0", jdbc.queryForObject("SELECT status FROM sys_commission_config WHERE config_key = 'self_rate'", String.class));
        assertEquals(0, new BigDecimal("37").compareTo(jdbc.queryForObject("SELECT config_value FROM sys_commission_config WHERE config_key = 'self_rate'", BigDecimal.class)));
        assertEquals("1", jdbc.queryForObject("SELECT status FROM sys_commission_config WHERE config_key = 'promotion_rate'", String.class));
    }

    @Test
    public void migrationPreservesChangedValueWithOriginalRemark() throws Exception
    {
        jdbc.update("UPDATE sys_commission_config SET status = '0', config_value = 37, update_time = NULL, update_by = '', remark = '占位配置，上线前设置自购比例' WHERE config_key = 'self_rate'");
        jdbc.update("UPDATE sys_commission_config SET status = '0', config_value = 0, update_time = NOW(), update_by = '', remark = '占位配置，上线前设置推广比例' WHERE config_key = 'promotion_rate'");
        try (Connection connection = dataSource.getConnection())
        {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V92__disable_placeholder_commission_rates.sql"));
        }
        assertEquals("0", jdbc.queryForObject("SELECT status FROM sys_commission_config WHERE config_key = 'self_rate'", String.class));
        assertEquals("1", jdbc.queryForObject("SELECT status FROM sys_commission_config WHERE config_key = 'promotion_rate'", String.class));
    }

    private static boolean update(CountDownLatch start, String key) throws Exception
    {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        SysCommissionConfig config = new SysCommissionConfig();
        config.setConfigKey(key);
        config.setConfigValue(new BigDecimal("70"));
        try
        {
            assertEquals(1, service.updateSysCommissionConfig(config));
            return true;
        }
        catch (ServiceException expected)
        {
            assertTrue(expected.getMessage().contains("100%"));
            return false;
        }
    }
}
