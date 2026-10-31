package com.ruoyi.system.cps;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import static org.junit.Assert.*;

/** Flyway and order keys against an isolated MySQL 8 database. */
public class CpsContractMySqlTest {
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static SysOrderCommissionMapper orders;
    @BeforeClass public static void setup() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line8_contract_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            assertEquals("MySQL", c.getMetaData().getDatabaseProductName());
            assertTrue(c.getMetaData().getDatabaseMajorVersion() >= 8);
            s.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int q = url.indexOf('?');
        String path = q < 0 ? url : url.substring(0, q);
        String options = q < 0 ? "" : url.substring(q);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        DriverManagerDataSource ds = new DriverManagerDataSource((slash < 0 ? path : path.substring(0, slash)) + "/" + database + options, user, password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"));
        orders = new SqlSessionTemplate(bean.getObject()).getMapper(SysOrderCommissionMapper.class);
    }
    @AfterClass public static void cleanup() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line8_contract_[a-f0-9]{32}"));
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }
    @Test public void migrationAndOrderLineUniqueness() {
        assertEquals(Long.valueOf(14), jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('cps_pid_lease','cps_query_log','cps_order_claim','cps_attribution','cps_order_raw','cps_order_event','cps_rule','cps_rule_version','cps_reward_progress','cps_settle_item','cps_withdrawal','cps_recon_batch','cps_recon_diff','cps_punish_import')", Long.class));
        jdbc.update("INSERT INTO sys_order_commission(order_id, platform_type, sub_order_no, agent_user_id) VALUES ('line8-order','tbk','',1),('line8-order','tbk','a',1),('line8-order','tbk','b',1)");
        assertEquals("", orders.selectSysOrderCommissionByOrderIdAndPlatformType("line8-order", "tbk").getSubOrderNo());
        assertEquals("a", orders.selectByOrderLineKey("line8-order", "tbk", "a").getSubOrderNo());
        assertEquals("b", orders.selectByOrderLineKey("line8-order", "tbk", "b").getSubOrderNo());
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("INSERT INTO sys_order_commission(order_id, platform_type, sub_order_no, agent_user_id) VALUES ('line8-order','tbk','a',1)"));
    }
    @Test public void duplicateEventsAndWithdrawalPostingsAreRejected() {
        jdbc.update("INSERT INTO cps_order_event(event_key,order_line_id,to_status,source,created_at) VALUES ('event-1',1,'PENALIZED','test',NOW())");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("INSERT INTO cps_order_event(event_key,order_line_id,to_status,source,created_at) VALUES ('event-1',1,'PENALIZED','test',NOW())"));
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,business_type,business_id,account,debit_account,credit_account,idempotency_key) VALUES (1,'withdraw',10,'WITHDRAW','withdraw-1','USER_AVAILABLE','USER_AVAILABLE','USER_WITHDRAWING','withdraw-request-1')");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,business_type,business_id,account,debit_account,credit_account,idempotency_key) VALUES (1,'withdraw',10,'WITHDRAW','withdraw-1','USER_AVAILABLE','USER_AVAILABLE','USER_WITHDRAWING','withdraw-request-2')"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,business_type,business_id,account,idempotency_key) VALUES (1,'withdraw',10,'WITHDRAW','withdraw-2','USER_AVAILABLE','withdraw-request-1')"));
    }

}
