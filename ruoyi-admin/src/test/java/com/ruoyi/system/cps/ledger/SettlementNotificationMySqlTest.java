package com.ruoyi.system.cps.ledger;

import com.ruoyi.system.cps.settlement.SettlementNotificationChannel;
import com.ruoyi.system.cps.settlement.SettlementNotificationRepository;
import com.ruoyi.system.cps.settlement.SettlementNotificationService;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.Assert.*;
import com.ruoyi.system.cps.withdraw.WithdrawalMySqlTest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * REAL MySQL 8 tests; not an in-memory SQL substitute. When the dedicated URL is
 * absent, reuse the common SQL harness's newly created UUID schema (including CI).
 * CPS_NOTIFICATION_MYSQL_URL, when supplied, must target a disposable schema whose name
 * ends in _notification_test (no query parameters). Set CPS_NOTIFICATION_MYSQL_USER
 * and CPS_NOTIFICATION_MYSQL_PASSWORD separately. Creates/drops only the new
 * outbox table, and fails rather than overwriting a pre-existing table.
 */
public class SettlementNotificationMySqlTest
{
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SettlementNotificationRepository repository;
    private SettlementNotificationService service;
    private boolean created;
    private boolean commonHarness;

    @Before
    public void setup() throws Exception
    {
        String url = System.getenv("CPS_NOTIFICATION_MYSQL_URL");
        DriverManagerDataSource source;
        if (url == null || url.isBlank())
        {
            WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
            commonHarness = true;
            jdbc = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "jdbc");
            source = (DriverManagerDataSource) jdbc.getDataSource();
        }
        else
        {
            assertTrue("Use a disposable schema ending in _notification_test", url.matches("jdbc:mysql://[^?]+/_?[a-zA-Z0-9]+_notification_test"));
            source = new DriverManagerDataSource(url,
                    System.getenv("CPS_NOTIFICATION_MYSQL_USER"), System.getenv("CPS_NOTIFICATION_MYSQL_PASSWORD"));
            jdbc = new JdbcTemplate(source);
        }
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        repository = new SettlementNotificationRepository(jdbc);
        service = new SettlementNotificationService(repository,
                new DefaultListableBeanFactory().getBeanProvider(SettlementNotificationChannel.class), manager);
        if (commonHarness) return; // All immutable migrations, including V705, already applied.
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("ruoyi-admin")))
        {
            root = root.getParent();
        }
        assertNotNull("Repository root not found", root);
        jdbc.execute(Files.readString(root.resolve("ruoyi-admin/src/main/resources/db/migration/V705__settlement_notification_outbox.sql")));
        created = true;
    }

    @After
    public void cleanup() throws Exception
    {
        if (commonHarness)
        {
            WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
        }
        else if (created)
        {
            jdbc.execute("DROP TABLE cps_settlement_notification");
        }
    }

    @Test
    public void ledgerRollbackDedupePayloadConflictAndPendingWithoutChannel()
    {
        tx.executeWithoutResult(status -> {
            enqueue("rollback");
            status.setRollbackOnly();
        });
        assertEquals(0, count());
        tx.executeWithoutResult(status -> enqueue("same-key"));
        tx.executeWithoutResult(status -> enqueue("same-key"));
        assertEquals(1, count());
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status ->
                service.enqueue("same-key", 2L, null, "REFUND", BigDecimal.ONE, BigDecimal.ZERO)));
        assertEquals(0, service.retryPending(100));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM cps_settlement_notification", String.class));
        assertEquals(64, jdbc.queryForObject("SELECT LENGTH(business_key_hash) FROM cps_settlement_notification", Integer.class).intValue());
    }

    @Test
    public void concurrentWorkerSkipsLockedClaimAndCommittedLeaseExcludesOthers() throws Exception
    {
        tx.executeWithoutResult(status -> enqueue("concurrent"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            tx.executeWithoutResult(status -> {
                assertNotNull(repository.claim());
                Future<SettlementNotificationRepository.Claim> other = executor.submit(() -> tx.execute(ignored -> repository.claim()));
                try
                {
                    assertNull(other.get(5, TimeUnit.SECONDS));
                }
                catch (Exception failure)
                {
                    throw new AssertionError(failure);
                }
            });
            assertNull(tx.execute(status -> repository.claim()));
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    public void expiredLeaseReusesDeliveryKeyAndFencesStaleAcknowledgements()
    {
        tx.executeWithoutResult(status -> enqueue("lease"));
        SettlementNotificationRepository.Claim first = tx.execute(status -> repository.claim());
        jdbc.update("UPDATE cps_settlement_notification SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))");
        SettlementNotificationRepository.Claim second = tx.execute(status -> repository.claim());
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.message().idempotencyKey(), second.message().idempotencyKey());
        assertNotEquals(first.token(), second.token());
        assertFalse(Boolean.TRUE.equals(tx.execute(status -> repository.delivered(first))));
        tx.executeWithoutResult(status -> repository.retry(first));
        assertTrue(Boolean.TRUE.equals(tx.execute(status -> repository.delivered(second))));
        assertNull(tx.execute(status -> repository.claim()));
        assertEquals(2L, jdbc.queryForObject("SELECT attempts FROM cps_settlement_notification", Long.class).longValue());
    }

    @Test
    public void failedSendBacksOffWithoutSavingProviderDetails()
    {
        tx.executeWithoutResult(status -> enqueue("retry"));
        SettlementNotificationRepository.Claim first = tx.execute(status -> repository.claim());
        assertNotNull(first);
        tx.executeWithoutResult(status -> repository.retry(first));
        assertEquals("CHANNEL_FAILURE", jdbc.queryForObject(
                "SELECT last_error_code FROM cps_settlement_notification", String.class));
        assertNull(tx.execute(status -> repository.claim()));
        jdbc.update("UPDATE cps_settlement_notification SET next_attempt_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))");
        SettlementNotificationRepository.Claim second = tx.execute(status -> repository.claim());
        assertNotNull(second);
        assertEquals(first.message().idempotencyKey(), second.message().idempotencyKey());
    }

    private void enqueue(String key)
    {
        service.enqueue(key, 1L, null, "REFUND", BigDecimal.ONE, new BigDecimal("-1.00"));
    }

    private int count()
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cps_settlement_notification", Integer.class);
    }
}
