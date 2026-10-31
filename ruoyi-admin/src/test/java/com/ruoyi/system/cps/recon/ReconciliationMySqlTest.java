package com.ruoyi.system.cps.recon;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.ReconBatchMapper;
import com.ruoyi.system.cps.mapper.ReconDiffMapper;

/** Real MySQL is mandatory: validates migrations, row locks, snapshots and SQL, never an in-memory substitute. */
public class ReconciliationMySqlTest {
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static ReconciliationService service;
    private static ExposureService exposure;
    private static ReconBatchMapper mapper;
    private static final LocalDate JAN = LocalDate.of(2026, 1, 20);

    @BeforeClass public static void setup() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 is required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line12_recon_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            (slash < 0 ? path : path.substring(0, slash)) + "/" + database + options, user, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMapperLocations(new ClassPathResource("mapper/cps/ReconBatchMapper.xml"),
            new ClassPathResource("mapper/cps/ReconDiffMapper.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(bean.getObject());
        mapper = session.getMapper(ReconBatchMapper.class);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        service = proxy(new ReconciliationService(mapper, session.getMapper(ReconDiffMapper.class)), transactions);
        exposure = proxy(new ExposureService(mapper), transactions);
    }

    @SuppressWarnings("unchecked") private static <T> T proxy(T target, DataSourceTransactionManager transactions) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    @AfterClass public static void cleanup() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line12_recon_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Before public void reset() {
        for (String table : List.of("cps_recon_coverage", "cps_recon_source", "cps_recon_diff", "cps_recon_batch",
            "cps_exposure_snapshot", "sys_commission_flow", "cps_withdrawal", "sys_order_commission", "sys_agent_commission_account")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE sys_config SET config_value='' WHERE config_key='cps.exposure.limit'");
    }

    @Test public void fourSourcesKeepGrossCommissionAndUserPayoutDistinct() {
        order(1, "order-a", "child-a", "20.0000");
        credit(1, "9.00");
        source("UNION_REPORT", "report-a", "order-a", "child-a", "20", "18", JAN);
        ReconSource receipt = source("UNION_RECEIPT", "receipt-a", "order-a", "child-a", "18", null, JAN);
        ReconBatch beforeAllocation = service.reconcile("tbk", "2026-01");
        assertEquals(Integer.valueOf(1), beforeAllocation.diffCount());
        assertEquals("COVERAGE_PENDING", service.differences(beforeAllocation.id()).get(0).type());
        amount("9.00", exposure.current().current());
        service.allocateReceipt(new ReconCoverageRequest("allocation-a", receipt.id(), decimal("9"), "synthetic finance proof"), 1L);
        assertEquals(Integer.valueOf(0), service.reconcile("tbk", "2026-01").diffCount());
        amount("0.00", exposure.current().current());

        paid("withdraw-a", "9", "2025-12-31 23:00:00", "2026-01-20 12:00:00");
        payoutFlow("withdraw-a", "9", "2026-01-20 12:00:00");
        service.importSource(new ReconImportRequest("PAYOUT", "PAYOUT", "payout-a", "", "", "withdraw-a", decimal("9"), null, JAN), 1L);
        ReconBatch batch = service.reconcile("PAYOUT", "2026-01");
        assertEquals(Integer.valueOf(0), batch.diffCount());
        assertEquals(batch.id(), service.reconcile("PAYOUT", "2026-01").id());
    }

    @Test public void changedExternalIdentityCannotOverwriteEvidence() {
        ReconSource first = source("UNION_REPORT", "external-1", "order-a", "", "20", "18", JAN);
        assertEquals(first.id(), source("UNION_REPORT", "external-1", "order-a", "", "20.0000", "18.00", JAN).id());
        assertThrows(ServiceException.class, () -> source("UNION_REPORT", "external-1", "order-a", "", "21", "18", JAN));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_recon_source", Integer.class));
        assertThrows(ServiceException.class, () -> service.importSource(new ReconImportRequest("tbk", "PAYOUT", "bad", "", "", "withdraw-a", decimal("1"), null, JAN), 1L));
    }

    @Test public void cashEvidenceCannotBeReusedByChangingPlatform() {
        source("UNION_RECEIPT", "synthetic-bank-account:line-1", "order-a", "", "18", null, JAN);
        assertThrows(ServiceException.class, () -> service.importSource(new ReconImportRequest("jd", "UNION_RECEIPT",
            "synthetic-bank-account:line-1", "order-b", "", "", decimal("18"), null, JAN), 1L));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_recon_source", Integer.class));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update(
            "INSERT INTO cps_recon_source(platform,source_type,external_id,order_no,amount,occurred_on,operator_id,created_at) VALUES('jd','UNION_RECEIPT','synthetic-bank-account:line-1','order-b',18,'2026-01-20',1,UTC_TIMESTAMP(3))"));
        // Alliance report line identifiers remain scoped to their own platform.
        source("UNION_REPORT", "report-line-1", "order-a", "", "20", "18", JAN);
        service.importSource(new ReconImportRequest("jd", "UNION_REPORT", "report-line-1", "order-b", "", "", decimal("20"), decimal("18"), JAN), 1L);
        assertEquals(Integer.valueOf(3), jdbc.queryForObject("SELECT COUNT(*) FROM cps_recon_source", Integer.class));
    }

    @Test public void siblingDifferencesNeverCancelEachOtherAndTinyCommissionDifferenceIsKept() {
        order(1, "same-parent", "a", "10.0000");
        order(2, "same-parent", "b", "10.0000");
        source("UNION_REPORT", "report-a", "same-parent", "a", "11", "0", JAN);
        source("UNION_REPORT", "report-b", "same-parent", "b", "9.0001", "0", JAN);
        source("UNION_RECEIPT", "receipt-a", "same-parent", "a", "0", null, JAN);
        source("UNION_RECEIPT", "receipt-b", "same-parent", "b", "0", null, JAN);
        List<ReconDiff> differences = service.differences(service.reconcile("tbk", "2026-01").id());
        assertEquals(2, differences.size());
        assertNotEquals(differences.get(0).refId(), differences.get(1).refId());
        assertTrue(differences.stream().allMatch(d -> d.type().equals("REPORT_ORDER")));
        assertTrue(differences.stream().anyMatch(d -> d.detail().contains("9.0001")));
    }

    @Test public void lateReceiptClosesItsOriginalStatementMonth() {
        order(1, "late-cash", "", "20");
        source("UNION_REPORT", "report-late", "late-cash", "", "20", "18", JAN);
        ReconBatch missing = service.reconcile("tbk", "2026-01");
        assertEquals(Integer.valueOf(1), missing.diffCount());
        source("UNION_RECEIPT", "receipt-late", "late-cash", "", "18", null, LocalDate.of(2026, 2, 20));
        ReconBatch complete = service.reconcile("tbk", "2026-01");
        assertNotEquals(missing.id(), complete.id());
        assertEquals(Integer.valueOf(0), complete.diffCount());
        assertEquals(Integer.valueOf(0), service.reconcile("tbk", "2026-02").diffCount());
    }

    @Test public void laterMonthReportAndCashCorrectionsUseCumulativeOrderCohort() {
        order(1, "corrected-later", "", "20");
        source("UNION_REPORT", "report-original", "corrected-later", "", "20", "18", JAN);
        source("UNION_RECEIPT", "receipt-original", "corrected-later", "", "18", null, JAN);
        ReconBatch original = service.reconcile("tbk", "2026-01");
        assertEquals(Integer.valueOf(0), original.diffCount());
        LocalDate february = LocalDate.of(2026, 2, 20);
        source("UNION_REPORT", "report-correction", "corrected-later", "", "-2", "-1.8", february);
        source("UNION_RECEIPT", "receipt-correction", "corrected-later", "", "-1.8", null, february);
        jdbc.update("UPDATE sys_order_commission SET actual_commission=18 WHERE id=1");
        ReconBatch corrected = service.reconcile("tbk", "2026-01");
        assertNotEquals(original.id(), corrected.id());
        assertEquals(Integer.valueOf(0), corrected.diffCount());
        assertEquals(Integer.valueOf(0), service.reconcile("tbk", "2026-02").diffCount());
    }

    @Test public void successfulWithdrawalNeedsBothChannelEvidenceAndLedgerPosting() {
        paid("withdraw-missing", "5", "2026-01-01 00:00:00", "2026-01-20 12:00:00");
        ReconBatch first = service.reconcile("PAYOUT", "2026-01");
        assertEquals(Integer.valueOf(2), first.diffCount());
        assertEquals(2, service.differences(first.id()).size());
        payoutFlow("withdraw-missing", "5", "2026-01-20 12:00:00");
        assertEquals(Integer.valueOf(1), service.reconcile("PAYOUT", "2026-01").diffCount());
    }

    @Test public void exposureCountsIncomeOnceAcrossWithdrawalAndRecovery() {
        order(1, "order-paid", "", "20");
        credit(1, "9");
        flow(1L, "apply-one", "USER_AVAILABLE", "USER_WITHDRAWING", "9", "WITHDRAW_APPLY", "withdraw-one");
        payoutFlow("withdraw-one", "9", "2026-01-20 12:00:00");
        amount("9.00", exposure.current().current());
        flow(1L, "recovery-one", "USER_AVAILABLE", "REBATE_COST", "6", "RECOVER", "order-paid:refund-1");
        amount("3.00", exposure.current().current());
        flow(1L, "recovery-two", "USER_AVAILABLE", "REBATE_COST", "3", "RECOVER", "order-paid:refund-2");
        amount("0.00", exposure.current().current());
    }

    @Test public void legacyWithdrawalReversalDoesNotCreateAnotherIncomeExposure() {
        order(1, "legacy-paid", "", "20");
        jdbc.update("INSERT INTO sys_commission_flow(id,user_id,flow_type,amount,order_commission_id) VALUES(101,1,'income_self',9,1),(102,1,'withdraw',-9,NULL)");
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,original_flow_id,idempotency_key) VALUES(1,'reversal',9,102,'legacy-return')");
        amount("9", exposure.current().current());
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,original_flow_id,idempotency_key) VALUES(1,'reversal',-9,101,'legacy-recovery')");
        amount("0", exposure.current().current());
    }

    @Test public void paidThenInvalidOrderRetainsUnallocatedDebtUntilItIsRepaid() {
        order(1, "invalid-after-paid", "", "20");
        credit(1, "9");
        payoutFlow("already-paid", "9", "2026-01-20 12:00:00");
        flow(1L, "invalid-recovery", "USER_AVAILABLE", "REBATE_COST", "9", "RECOVER", "invalid-event");
        // Test fixture models the ledger's recovery result; production recon code never writes balances.
        jdbc.update("INSERT INTO sys_agent_commission_account(user_id,balance) VALUES(991,-9)");
        ExposureSummary stillAtRisk = exposure.capture();
        amount("0", stillAtRisk.knownAmount());
        amount("9", stillAtRisk.debtAmount());
        amount("9", stillAtRisk.current());
        assertTrue(stillAtRisk.platforms().stream().anyMatch(p -> p.platform().equals("UNALLOCATED_USER_DEBT")));
        jdbc.update("UPDATE sys_agent_commission_account SET balance=-4 WHERE user_id=991");
        amount("4", exposure.current().debtAmount());
        jdbc.update("UPDATE sys_agent_commission_account SET balance=0 WHERE user_id=991");
        amount("0", exposure.current().current());
        amount("9", exposure.current().peak());
    }

    @Test public void partialReceiptsNeedExplicitCoverageAndNeverGuessUserAllocation() {
        order(1, "partial-cash", "", "20");
        credit(1, "9");
        ReconSource receipt = source("UNION_RECEIPT", "receipt-partial", "partial-cash", "", "5", null, JAN);
        amount("9", exposure.current().current());
        ReconCoverageRequest allocation = new ReconCoverageRequest("alloc-partial", receipt.id(), decimal("4"), "synthetic finance allocation");
        ReconCoverage first = service.allocateReceipt(allocation, 1L);
        assertEquals(first.id(), service.allocateReceipt(allocation, 1L).id());
        amount("5", exposure.current().current());
        assertThrows(ServiceException.class, () -> service.allocateReceipt(new ReconCoverageRequest("too-much", receipt.id(), decimal("2"), "synthetic"), 1L));
        // A bank correction is a new immutable source delta and restores the unsupported exposure.
        source("UNION_RECEIPT", "receipt-correction", "partial-cash", "", "-3", null, JAN);
        amount("7", exposure.current().current());
    }

    @Test public void concurrentAllocationCannotSpendSameReceiptTwice() throws Exception {
        order(1, "concurrent-cash", "", "20");
        credit(1, "9");
        ReconSource receipt = source("UNION_RECEIPT", "receipt-concurrent", "concurrent-cash", "", "5", null, JAN);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> actions = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int index = i;
                actions.add(() -> {
                    try { service.allocateReceipt(new ReconCoverageRequest("race-" + index, receipt.id(), decimal("3"), "synthetic"), 1L); return true; }
                    catch (ServiceException expected) { return false; }
                });
            }
            int success = 0;
            for (Future<Boolean> result : executor.invokeAll(actions)) if (result.get()) success++;
            assertEquals(1, success);
            amount("3", mapper.coveredForReceipt(receipt.id()));
            amount("6", exposure.current().current());
        } finally { executor.shutdownNow(); }
    }

    @Test public void concurrentRunsReuseOneBatchAndDoNotDuplicateDifferences() throws Exception {
        order(1, "missing-report", "", "20");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Long>> actions = new ArrayList<>();
            for (int i = 0; i < 12; i++) actions.add(() -> service.reconcile("tbk", "2026-01").id());
            Long expected = null;
            for (Future<Long> result : executor.invokeAll(actions)) {
                Long id = result.get();
                if (expected == null) expected = id;
                assertEquals(expected, id);
            }
            assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_recon_batch", Integer.class));
            assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_recon_diff", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test public void limitMustBeConfiguredAndDefaultsToManualAtOrAboveWhileZeroDisablesIt() {
        order(1, "limit-order", "", "20");
        credit(1, "9");
        assertTrue(exposure.evaluateWithdrawal(decimal("1")).blocked());
        jdbc.update("UPDATE sys_config SET config_value='10.00' WHERE config_key='cps.exposure.limit'");
        assertFalse(exposure.evaluateWithdrawal(decimal("9")).blocked());
        assertFalse(exposure.evaluateWithdrawal(decimal("9")).manualReview());
        jdbc.update("UPDATE sys_config SET config_value='9' WHERE config_key='cps.exposure.limit'");
        assertFalse(exposure.evaluateWithdrawal(decimal("1")).blocked());
        assertTrue(exposure.evaluateWithdrawal(decimal("1")).manualReview());
        jdbc.update("UPDATE sys_config SET config_value='8' WHERE config_key='cps.exposure.limit'");
        assertFalse(exposure.evaluateWithdrawal(decimal("1")).blocked());
        assertTrue(exposure.evaluateWithdrawal(decimal("1")).manualReview());
        jdbc.update("UPDATE sys_config SET config_value='0' WHERE config_key='cps.exposure.limit'");
        assertFalse(exposure.evaluateWithdrawal(decimal("1")).blocked());
        assertFalse(exposure.evaluateWithdrawal(decimal("1")).manualReview());
    }

    @Test public void exposurePeakSurvivesRecoveryAndUnknownOrderStaysVisible() {
        order(1, "peak-order", "", "20");
        credit(1, "9");
        amount("9", exposure.capture().peak());
        flow(1L, "peak-recovery", "USER_AVAILABLE", "REBATE_COST", "4", "RECOVER", "peak-refund");
        ExposureSummary smaller = exposure.capture();
        amount("5", smaller.current());
        amount("9", smaller.peak());
        flow(null, "orphan", "REBATE_COST", "USER_AVAILABLE", "2", "CREDIT", "orphan-credit");
        amount("7", exposure.current().current());
        assertTrue(exposure.positions().stream().anyMatch(p -> p.platform().equals("UNKNOWN")));
    }

    @Test public void resolvingDifferenceLeavesFinancialEvidenceAndMoneyUntouched() {
        order(1, "resolve-order", "", "20");
        ReconBatch batch = service.reconcile("tbk", "2026-01");
        ReconDiff difference = service.differences(batch.id()).get(0);
        ReconDiff resolved = service.resolve(difference.id(), "synthetic finance investigation", 1L);
        assertEquals("RESOLVED", resolved.status());
        assertEquals(Long.valueOf(1), resolved.resolvedBy());
        assertEquals(resolved.id(), service.resolve(difference.id(), "synthetic finance investigation", 1L).id());
        assertThrows(ServiceException.class, () -> service.resolve(difference.id(), "overwrite", 1L));
        assertEquals(batch.id(), service.reconcile("tbk", "2026-01").id());
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow", Integer.class));
    }

    private static ReconSource source(String type, String external, String order, String child, String amount, String expected, LocalDate day) {
        return service.importSource(new ReconImportRequest("tbk", type, external, order, child, "", decimal(amount),
            expected == null ? null : decimal(expected), day), 1L);
    }
    private static void order(long id, String order, String child, String commission) {
        jdbc.update("INSERT INTO sys_order_commission(id,order_id,sub_order_no,platform_type,agent_user_id,actual_commission,settle_time) VALUES(?,?,?,'tbk',1,?,'2026-01-20 12:00:00')",
            id, order, child, decimal(commission));
    }
    private static void credit(long orderId, String value) {
        flow(orderId, "income-" + orderId, "USER_FROZEN", "USER_AVAILABLE", value, "UNFREEZE", "order-" + orderId);
    }
    private static void flow(Long orderId, String key, String debit, String credit, String value, String businessType, String businessId) {
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,related_order_id,idempotency_key,business_type,business_id,account,debit_account,credit_account,create_time) VALUES(1,'withdraw',?,?,?,?,?,?,?,?,'2026-01-20 12:00:00')",
            decimal(value), orderId == null ? null : String.valueOf(orderId), key, businessType, businessId, debit, debit, credit);
    }
    private static void paid(String number, String value, String created, String completed) {
        jdbc.update("INSERT INTO cps_withdrawal(withdraw_no,user_id,amount,status,created_at,completed_at) VALUES(?,1,?,'SUCCEEDED',?,?)", number, decimal(value), created, completed);
    }
    private static void payoutFlow(String number, String value, String at) {
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,idempotency_key,business_type,business_id,account,debit_account,credit_account,create_time) VALUES(1,'withdraw_success',?,?,'WITHDRAW_SUCCESS',?,'USER_WITHDRAWING','USER_WITHDRAWING','PAYOUT',?)",
            decimal(value), "success:" + number, number, at);
    }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static void amount(String expected, BigDecimal actual) { assertEquals(0, decimal(expected).compareTo(actual)); }
}
