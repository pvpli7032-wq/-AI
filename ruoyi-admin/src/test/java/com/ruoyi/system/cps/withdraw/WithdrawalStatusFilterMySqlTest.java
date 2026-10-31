package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.ledger.LedgerService;
import com.ruoyi.system.cps.mapper.WithdrawalMapper;
import com.ruoyi.system.cps.recon.ExposureService;
import com.ruoyi.system.cps.risk.HealthMonitorService;
import com.ruoyi.system.cps.risk.RiskService;

/** Real MySQL enum filtering, count/pagination and user isolation using the production migrations/mapper. */
public class WithdrawalStatusFilterMySqlTest {
    private static final long USER_A = 12_401L, USER_B = 12_402L;
    private static String adminUrl, mysqlUser, mysqlPassword, database;
    private static WithdrawalService service;

    @BeforeClass
    public static void migrateAndSeedDedicatedRealMySqlDatabase() throws Exception {
        adminUrl = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL is required for withdrawal status SQL acceptance",
            adminUrl != null && adminUrl.startsWith("jdbc:mysql://"));
        mysqlUser = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        mysqlPassword = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line12_status_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
             Statement statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue("MySQL 8 or later is required", connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = adminUrl.indexOf('?');
        String path = query < 0 ? adminUrl : adminUrl.substring(0, query);
        String options = query < 0 ? "" : adminUrl.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        String base = slash < 0 ? path : path.substring(0, slash);
        DriverManagerDataSource source = new DriverManagerDataSource(base + "/" + database + options, mysqlUser, mysqlPassword);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/cps/WithdrawalMapper.xml"));
        WithdrawalMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WithdrawalMapper.class);
        StaticListableBeanFactory providers = new StaticListableBeanFactory();
        service = new WithdrawalService(mapper, providers.getBeanProvider(LedgerService.class),
            providers.getBeanProvider(WithdrawChannel.class), mock(RiskService.class), mock(ExposureService.class),
            mock(HealthMonitorService.class), new DataSourceTransactionManager(source));
        for (long user : List.of(USER_A, USER_B)) {
            for (WithdrawalStatus status : WithdrawalStatus.values()) mapper.insertWithdrawal(row(user, status));
        }
    }

    @AfterClass
    public static void dropOnlyTheDatabaseCreatedByThisSuite() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line12_status_[0-9a-f]{32}"));
            try (Connection connection = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPassword);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test
    public void everyBackendStatusFiltersRowsAndTotalIndependently() {
        assertEquals(10, WithdrawalStatus.values().length);
        for (WithdrawalStatus status : WithdrawalStatus.values()) {
            Map<String, Object> response = service.listAdmin(status.name(), 1, 10);
            assertEquals(status.name(), 2L, ((Number) response.get("total")).longValue());
            List<Map<String, Object>> rows = adminRows(response);
            assertEquals(status.name(), 2, rows.size());
            assertEquals(Set.of(USER_A, USER_B), rows.stream().map(row -> (Long) row.get("userId")).collect(Collectors.toSet()));
            assertEquals(Set.of(withdrawNo(USER_A, status), withdrawNo(USER_B, status)), rows.stream()
                .map(row -> ((WithdrawalView) row.get("withdrawal")).withdrawNo()).collect(Collectors.toSet()));
            for (Map<String, Object> row : rows) assertEquals(status, ((WithdrawalView) row.get("withdrawal")).status());
        }
    }

    @Test
    public void allOptionAndPaginationKeepCountsWithoutDroppingPendingStatuses() {
        for (String filter : new String[] {null, "", " "}) {
            Map<String, Object> response = service.listAdmin(filter, 1, 100);
            assertEquals(20L, ((Number) response.get("total")).longValue());
            assertEquals(20, adminRows(response).size());
            assertEquals(Set.of(WithdrawalStatus.values()), adminRows(response).stream()
                .map(row -> ((WithdrawalView) row.get("withdrawal")).status()).collect(Collectors.toSet()));
        }
        for (WithdrawalStatus status : WithdrawalStatus.values()) {
            List<Map<String, Object>> first = adminRows(service.listAdmin(status.name(), 1, 1));
            Map<String, Object> secondPage = service.listAdmin(status.name(), 2, 1);
            List<Map<String, Object>> second = adminRows(secondPage);
            assertEquals(1, first.size());
            assertEquals(1, second.size());
            assertNotEquals(first.getFirst().get("userId"), second.getFirst().get("userId"));
            assertEquals(2L, ((Number) secondPage.get("total")).longValue());
            Map<String, Object> emptyPage = service.listAdmin(status.name(), 3, 1);
            assertTrue(adminRows(emptyPage).isEmpty());
            assertEquals(2L, ((Number) emptyPage.get("total")).longValue());
        }
    }

    @Test
    public void invalidRawStatusAndDisplayLabelsCannotBecomeSqlFilters() {
        for (String invalid : List.of("审核中", "打款中", "approved", "PENDING", "FAILED ", "SUCCEEDED' OR 1=1 --")) {
            ServiceException failure = assertThrows(invalid, ServiceException.class, () -> service.listAdmin(invalid, 1, 10));
            assertEquals("无效提现状态", failure.getMessage());
        }
        assertEquals(20L, ((Number) service.listAdmin(null, 1, 100).get("total")).longValue());
    }

    @Test
    public void miniappListKeepsAllTenDisplayStatusesWithinTheAuthenticatedUsersRows() {
        for (long user : List.of(USER_A, USER_B)) {
            Map<String, Object> response = service.list(user, 1, 100);
            assertEquals(10L, ((Number) response.get("total")).longValue());
            @SuppressWarnings("unchecked") List<WithdrawalView> rows = (List<WithdrawalView>) response.get("rows");
            assertEquals(10, rows.size());
            assertEquals(Set.of(WithdrawalStatus.values()), rows.stream().map(WithdrawalView::status).collect(Collectors.toSet()));
            for (WithdrawalView row : rows) {
                assertEquals(withdrawNo(user, row.status()), row.withdrawNo());
                boolean returned = row.status() == WithdrawalStatus.FAILED || row.status() == WithdrawalStatus.REJECTED;
                assertEquals(returned, row.fundsReturned());
                assertEquals(returned, row.message().contains("金额已退回余额"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> adminRows(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("rows");
    }

    private static String withdrawNo(long user, WithdrawalStatus status) { return "synthetic-filter-" + user + "-" + status; }

    private static WithdrawalRecord row(long user, WithdrawalStatus status) {
        WithdrawalRecord row = new WithdrawalRecord();
        row.setWithdrawNo(withdrawNo(user, status));
        row.setUserId(user);
        row.setAmount(new BigDecimal("12.34"));
        row.setFee(new BigDecimal("0.00"));
        row.setStatus(status);
        row.setChannel("mock");
        row.setRequestKey("synthetic-status-" + status);
        row.setRecipientName("Synthetic status fixture");
        row.setRecipientAccount("synthetic-payee-1234");
        row.setReviewReasons("[]");
        row.setPolicySnapshot("{}");
        row.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }
}
