package com.ruoyi.system.cps.order;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.system.cps.mapper.OrderReadMapper;
import static org.junit.Assert.*;

/** Exercises real MySQL queries and the ownership boundary. */
public class OrderViewsMySqlTest {
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static OrderReadMapper orders;
    @BeforeClass public static void setup() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line10_views_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            assertEquals("MySQL", c.getMetaData().getDatabaseProductName());
            assertTrue(c.getMetaData().getDatabaseMajorVersion() >= 8);
            st.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int q = url.indexOf('?'); String base = q < 0 ? url : url.substring(0, q);
        String query = q < 0 ? "" : url.substring(q); int slash = base.indexOf('/', "jdbc:mysql://".length());
        DriverManagerDataSource ds = new DriverManagerDataSource((slash < 0 ? base : base.substring(0, slash)) + "/" + database + query, user, password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean(); bean.setDataSource(ds);
        bean.setMapperLocations(new ClassPathResource("mapper/cps/OrderReadMapper.xml"));
        orders = new SqlSessionTemplate(bean.getObject()).getMapper(OrderReadMapper.class);
    }
    @AfterClass public static void cleanup() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line10_views_[a-f0-9]{32}"));
            try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }
    @Test public void currentAttributionIsolatesUsersAndStatusesDoNotLeak() {
        jdbc.update("INSERT INTO sys_order_commission(id,order_id,platform_type,agent_user_id,line_status) VALUES (900001,'one','tbk',1,'PAID'),(900002,'two','jd',1,'RECEIVED'),(900003,'three','pdd',1,'SETTLED'),(900004,'four','tbk',1,'FULL_REFUND'),(900005,'other','tbk',1,'PAID')");
        jdbc.update("INSERT INTO cps_attribution(order_line_id,user_id,method,is_current,created_at) VALUES (900001,7,'CLAIM',1,NOW()),(900002,7,'CLAIM',1,NOW()),(900003,7,'CLAIM',1,NOW()),(900004,7,'CLAIM',1,NOW()),(900005,7,'CLAIM',1,NOW()),(900005,8,'CLAIM',1,NOW())");
        jdbc.update("INSERT INTO cps_settle_item(order_line_id,user_id,type,status) VALUES (900003,7,'test_rebate','CREDITED')");
        List<Map<String,Object>> mine = orders.listForUser(7L);
        assertEquals(4, mine.size());
        assertEquals(java.util.Set.of("待收货","冻结中","已结算","已退款"),
            mine.stream().map(row -> (String)row.get("displayStatus")).collect(java.util.stream.Collectors.toSet()));
        for (Map<String,Object> row : mine) {
            assertNotEquals("other", row.get("orderNo"));
            assertFalse(row.containsKey("line_status"));
            assertFalse(row.containsKey("status"));
        }
        assertEquals(1, orders.listForUser(8L).size());
        jdbc.update("INSERT INTO sys_order_commission(id,order_id,platform_type,agent_user_id,line_status) VALUES (900006,'unknown','tbk',1,'UNKNOWN')");
        jdbc.update("INSERT INTO cps_attribution(order_line_id,user_id,method,is_current,created_at) VALUES (900006,8,'CLAIM',1,NOW())");
        assertNull(orders.listForUser(8L).get(0).get("displayStatus"));
        assertEquals(1, orders.listAdmin("jd", "RECEIVED", "CLAIM").size());
        assertEquals(0, orders.listAdmin("jd", "PAID", null).size());
    }
}
