package com.ruoyi.system.cps.ledger;

import com.ruoyi.system.cps.settlement.PaymentInvitationSnapshotPort;
import com.ruoyi.system.cps.settlement.PaymentInvitationSnapshotService;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.union.adapter.*;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** Real MySQL unique-key/transaction tests; uses only a fresh disposable UUID database.
 * LINE4_MYSQL_URL is required. No production line 9 adapter is claimed by these stub-port tests.
 */
public class PaymentInvitationSnapshotMySqlTest
{
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static final Instant PAID = Instant.parse("2026-01-01T12:00:00.123456789Z");
    private static final Long BUYER = 11001L;

    @BeforeClass public static void database() throws Exception
    {
        url = System.getenv("LINE4_MYSQL_URL");
        assertNotNull("LINE4_MYSQL_URL is required", url);
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line11_snapshot_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement())
        {
            assertEquals("MySQL", c.getMetaData().getDatabaseProductName());
            assertTrue(c.getMetaData().getDatabaseMajorVersion() >= 8);
            s.execute("CREATE DATABASE `" + database + "`");
        }
        String base = url.substring(0, url.indexOf('/', "jdbc:mysql://".length()));
        String options = url.contains("?") ? url.substring(url.indexOf('?')) : "";
        var ds = new DriverManagerDataSource(base + "/" + database + options, user, password);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("ruoyi-admin"))) root = root.getParent();
        assertNotNull("Repository root not found", root);
        new ResourceDatabasePopulator(new FileSystemResource(root.resolve(
            "ruoyi-admin/src/main/resources/db/migration/V703__payment_invitation_snapshot.sql"))).execute(ds);
        jdbc.execute("CREATE TABLE sys_wechat_user(user_id BIGINT PRIMARY KEY, inviter_user_id BIGINT NULL)");
    }

    @AfterClass public static void cleanup() throws Exception
    {
        if (database == null) return;
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement())
        { s.execute("DROP DATABASE `" + database + "`"); }
    }

    @Before public void reset()
    {
        jdbc.update("DELETE FROM cps_payment_invitation_snapshot");
        jdbc.update("DELETE FROM sys_wechat_user");
    }

    private PaymentInvitationSnapshotService service(PaymentInvitationSnapshotPort port)
    {
        var beans = new StaticListableBeanFactory();
        if (port != null) beans.addBean("historicalEvidenceStub", port);
        return new PaymentInvitationSnapshotService(jdbc, beans.getBeanProvider(PaymentInvitationSnapshotPort.class));
    }
    private UnionOrderLine line(Instant paid)
    { return new UnionOrderLine(Platform.TBK, "order", "sub", null, null, null, null, null, null, null, paid, null, null); }
    private PaymentInvitationSnapshotService.Decision resolve(PaymentInvitationSnapshotService service, long id)
    { return tx.execute(s -> service.resolve(id, BUYER, line(PAID))); }
    private PaymentInvitationSnapshotPort.Snapshot evidence(Long inviter)
    { return new PaymentInvitationSnapshotPort.Snapshot(BUYER, inviter, PAID, "line9-payment-evidence"); }

    @Test public void paymentInviterSurvivesCurrentRelationChangesAndReplay()
    {
        jdbc.update("INSERT INTO sys_wechat_user VALUES (?,?)", BUYER, 22002L);
        var calls = new AtomicInteger();
        var service = service((p,o,s,b,t) -> {
            assertEquals(Platform.TBK, p); assertEquals("order", o); assertEquals("sub", s);
            assertEquals(BUYER, b); assertEquals(PAID, t); calls.incrementAndGet();
            return Optional.of(evidence(22001L));
        });
        var first = resolve(service, 1);
        assertEquals(Long.valueOf(22001), first.inviterUserId()); assertFalse(first.manualReview());
        jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=22003 WHERE user_id=?", BUYER);
        assertEquals(first, resolve(service, 1));
        assertEquals(first, resolve(service((p,o,s,b,t) -> Optional.of(evidence(22003L))), 1));
        assertEquals(1, calls.get());
    }

    @Test public void missingHistoryAndMissingPortArePersistedManualAndNeverAutoRetried()
    {
        var unknown = resolve(service((p,o,s,b,t) -> Optional.empty()), 2);
        assertTrue(unknown.manualReview()); assertNull(unknown.inviterUserId());
        assertEquals(unknown, resolve(service((p,o,s,b,t) -> Optional.of(evidence(22001L))), 2));
        assertTrue(resolve(service(null), 3).manualReview());
        assertEquals(Long.valueOf(2), jdbc.queryForObject(
            "SELECT COUNT(*) FROM cps_payment_invitation_snapshot WHERE review_status='MANUAL_REVIEW'", Long.class));
    }

    @Test public void knownNoInviterIsVerifiedNotUnknown()
    {
        var known = resolve(service((p,o,s,b,t) -> Optional.of(evidence(null))), 4);
        assertFalse(known.manualReview()); assertNull(known.inviterUserId());
        assertEquals("line9-payment-evidence", known.evidenceId());
    }

    @Test public void mismatchedBuyerTimeSelfInvitationAndBlankEvidenceAreQuarantined()
    {
        var bad = new PaymentInvitationSnapshotPort.Snapshot[] {
            new PaymentInvitationSnapshotPort.Snapshot(999L, 22001L, PAID, "wrong-buyer"),
            new PaymentInvitationSnapshotPort.Snapshot(BUYER, 22001L, PAID.plusNanos(1), "wrong-time"),
            evidence(BUYER), new PaymentInvitationSnapshotPort.Snapshot(BUYER, 22001L, PAID, " "),
            new PaymentInvitationSnapshotPort.Snapshot(BUYER, 22001L, null, "missing-time"), evidence(-1L)
        };
        for (int i = 0; i < bad.length; i++)
        {
            var snapshot = bad[i];
            var decision = resolve(service((p,o,s,b,t) -> Optional.of(snapshot)), 10 + i);
            assertTrue(decision.manualReview()); assertNull(decision.inviterUserId());
        }
    }

    @Test public void immutableIdentityCannotBeReusedForAnotherBuyerTimeOrSuborder()
    {
        var service = service((p,o,s,b,t) -> Optional.of(evidence(22001L)));
        resolve(service, 20);
        assertThrows(ServiceException.class, () -> tx.execute(s -> service.resolve(20, 999L, line(PAID))));
        assertThrows(ServiceException.class, () -> tx.execute(s -> service.resolve(20, BUYER, line(PAID.plusNanos(1)))));
        var other = new UnionOrderLine(Platform.TBK, "order", "other-sub", null,null,null,null,null,null,null,PAID,null,null);
        assertThrows(ServiceException.class, () -> tx.execute(s -> service.resolve(20, BUYER, other)));
        assertFalse(resolve(service, 20).manualReview());
    }

    @Test public void concurrentFirstDecisionsReturnTheSameImmutableWinner() throws Exception
    {
        var barrier = new CyclicBarrier(2);
        var sequence = new AtomicInteger();
        var service = service((p,o,s,b,t) -> {
            int n = sequence.incrementAndGet();
            try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
            return Optional.of(evidence(22000L + n));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> resolve(service, 30));
            var b = executor.submit(() -> resolve(service, 30));
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_payment_invitation_snapshot", Long.class));
        } finally { executor.shutdownNow(); }
    }

    @Test public void rollbackDoesNotLeaveASnapshot()
    {
        assertThrows(ServiceException.class, () -> tx.execute(s -> {
            service(null).resolve(40, BUYER, line(PAID)); throw new ServiceException("synthetic rollback");
        }));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_payment_invitation_snapshot", Long.class));
    }

    @Test public void legacyOrderTimeIsNotPaidAtAndOrdinaryBuyerStillGetsSelfRebate()
    {
        jdbc.update("INSERT INTO sys_wechat_user VALUES (?,22001)", BUYER);
        var accounts = new CommissionCalculateServiceImpl() {
            @Override public BigDecimal getSelfCommissionRate() { return new BigDecimal("50"); }
            @Override public BigDecimal getPromotionCommissionRate() { return new BigDecimal("20"); }
        };
        var mapper = (SysOrderCommissionMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{SysOrderCommissionMapper.class}, (proxy, method, args) -> {
                if (method.getName().startsWith("select")) return null;
                if (method.getName().equals("insertSysOrderCommission")) { ((SysOrderCommission)args[0]).setId(50L); return 1; }
                throw new AssertionError(method.getName());
            });
        ReflectionTestUtils.setField(accounts, "ledgerJdbc", jdbc);
        ReflectionTestUtils.setField(accounts, "orderCommissionMapper", mapper);
        // No provider at all is also safe for legacy manually wired callers.
        var order = tx.execute(s -> accounts.calculateCommission("legacy", "tbk", "item", "Synthetic",
            new BigDecimal("100"), new BigDecimal("20"), BigDecimal.ZERO, BUYER, "2026-01-01 12:00:00"));
        assertNull(order.getPaidTime()); assertNull(order.getReferrerUserId()); assertEquals("0", order.getHasReferrer());
        assertEquals(0, new BigDecimal("10").compareTo(order.getSelfCommissionAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(order.getPromotionCommissionAmount()));
        assertEquals("MANUAL_REVIEW", jdbc.queryForObject(
            "SELECT review_status FROM cps_payment_invitation_snapshot WHERE order_line_id=50", String.class));
        assertNull(jdbc.queryForObject("SELECT paid_at FROM cps_payment_invitation_snapshot WHERE order_line_id=50", String.class));
    }

    @Test public void absentPaidTimeDoesNotCallPort()
    {
        var service = service((p,o,s,b,t) -> { throw new AssertionError("must not infer payment time"); });
        assertTrue(tx.execute(s -> service.resolve(60, BUYER, line(null))).manualReview());
    }
}
