package com.ruoyi.web.controller.system;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;
import org.aspectj.lang.JoinPoint;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.framework.aspectj.LogAspect;
import com.ruoyi.system.domain.SysOperLog;
import com.ruoyi.system.domain.SysUnionPlatform;
import com.ruoyi.system.mapper.SysOperLogMapper;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercises the log aspect's real request serialization and mapper against MySQL. */
public class UnionPlatformOperLogMySqlTest
{
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static SysOperLogMapper mapper;

    @BeforeClass
    public static void setup() throws Exception
    {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 is required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line7_log_" + UUID.randomUUID().toString().replace("-", "");
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
        DriverManagerDataSource ds = new DriverManagerDataSource((slash < 0 ? path : path.substring(0, slash)) + "/" + database + options, user, password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setTypeAliasesPackage("com.ruoyi.system.domain");
        factoryBean.setMapperLocations(new ClassPathResource("mapper/system/SysOperLogMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        mapper = new SqlSessionTemplate(factory).getMapper(SysOperLogMapper.class);
    }

    @AfterClass
    public static void cleanup() throws Exception
    {
        RequestContextHolder.resetRequestAttributes();
        if (database != null)
        {
            assertTrue(database.matches("line7_log_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement())
            {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test
    public void addAndEditPersistLogWithoutSecrets() throws Exception
    {
        for (String method : new String[] {"add", "edit"})
        {
            String httpMethod = "add".equals(method) ? "POST" : "PUT";
            MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/system/unionPlatform");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            SysUnionPlatform platform = new SysUnionPlatform();
            platform.setAppKey("public-app-key");
            platform.setAppSecret("synthetic-secret-value");
            platform.setAccessToken("synthetic-token-value");
            JoinPoint joinPoint = mock(JoinPoint.class);
            when(joinPoint.getArgs()).thenReturn(new Object[] { platform });
            Log annotation = SysUnionPlatformController.class.getMethod(method, SysUnionPlatform.class).getAnnotation(Log.class);
            SysOperLog log = new SysOperLog();
            log.setRequestMethod(httpMethod);
            new LogAspect().getControllerMethodDescription(joinPoint, annotation, log, null);
            mapper.insertOperlog(log);
            String stored = jdbc.queryForObject("SELECT oper_param FROM sys_oper_log ORDER BY oper_id DESC LIMIT 1", String.class);
            assertNotNull(stored);
            assertTrue("ordinary fields remain logged", stored.contains("public-app-key"));
            assertFalse(stored.contains("synthetic-secret-value"));
            assertFalse(stored.contains("synthetic-token-value"));
            assertFalse(stored.contains("appSecret"));
            assertFalse(stored.contains("accessToken"));
        }
    }
}
