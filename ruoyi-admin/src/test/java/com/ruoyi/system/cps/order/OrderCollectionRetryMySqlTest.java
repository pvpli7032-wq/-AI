package com.ruoyi.system.cps.order;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.system.cps.mapper.OrderCollectionRetryMapper;
import com.ruoyi.union.adapter.Platform;
import static org.junit.Assert.*;

/** MySQL only: validates the actual migration, unique key and atomic lease predicates. */
public class OrderCollectionRetryMySqlTest {
    private static String url, user, password, database;
    private static JdbcTemplate jdbc;
    private static OrderCollectionRetryQueue queue;
    private static final LocalDateTime FROM = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final LocalDateTime TO = FROM.plusHours(1);

    @BeforeClass public static void setup() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line10_retry_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            assertEquals("MySQL", c.getMetaData().getDatabaseProductName());
            assertTrue(c.getMetaData().getDatabaseMajorVersion() >= 8);
            st.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int q = url.indexOf('?'); String base = q < 0 ? url : url.substring(0, q);
        String options = q < 0 ? "" : url.substring(q); int slash = base.indexOf('/', "jdbc:mysql://".length());
        DriverManagerDataSource ds = new DriverManagerDataSource((slash < 0 ? base : base.substring(0, slash)) + "/" + database + options, user, password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean(); bean.setDataSource(ds);
        bean.setMapperLocations(new ClassPathResource("mapper/cps/OrderCollectionRetryMapper.xml"));
        queue = new OrderCollectionRetryQueue(new SqlSessionTemplate(bean.getObject()).getMapper(OrderCollectionRetryMapper.class));
    }
    @AfterClass public static void cleanup() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line10_retry_[a-f0-9]{32}"));
            try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }
    @Before public void clearRetryQueue() { jdbc.update("DELETE FROM cps_order_collection_retry"); }

    @Test public void repeatRecordIsIdempotentAndPreservesWindowAndCursor() throws Exception {
        UUID run = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) futures.add(pool.submit(() -> {
                start.await();
                return queue.record(run, Platform.TBK, OrderCollectionRetryQueue.TaskType.INCREMENTAL,
                    FROM, TO, "page+/=2", "RATE_LIMIT").getId();
            }));
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (Future<Long> future : futures) ids.add(future.get(30, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            OrderCollectionRetry claimed = queue.claimNext(60, 4);
            assertNotNull(claimed);
            assertEquals("TBK", claimed.getPlatform());
            assertEquals("INCREMENTAL", claimed.getTaskType());
            assertEquals(FROM, claimed.getWindowFrom());
            assertEquals(TO, claimed.getWindowTo());
            assertEquals("page+/=2", claimed.getPageCursor());
            assertEquals("RATE_LIMIT", claimed.getErrorCode());
        } finally { pool.shutdownNow(); }
    }

    @Test public void concurrentClaimsHaveOneOwnerAndExpiredOwnerCannotAcknowledge() throws Exception {
        OrderCollectionRetry saved = queue.record(UUID.randomUUID(), Platform.JD,
            OrderCollectionRetryQueue.TaskType.BACKFILL, FROM, TO, "cursor-2", "NETWORK_ERROR");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<OrderCollectionRetry>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> { start.await(); return queue.claimNext(60, 12); }));
            start.countDown();
            List<OrderCollectionRetry> owners = new ArrayList<>();
            for (Future<OrderCollectionRetry> future : futures) { OrderCollectionRetry item = future.get(30, TimeUnit.SECONDS); if (item != null) owners.add(item); }
            assertEquals(1, owners.size());
            OrderCollectionRetry first = owners.get(0);
            jdbc.update("UPDATE cps_order_collection_retry SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", saved.getId());
            assertThrows(IllegalStateException.class, () -> queue.complete(first.getId(), first.getLeaseToken()));
            OrderCollectionRetry second = queue.claimNext(60, 4);
            assertNotNull(second);
            assertEquals(saved.getId(), second.getId());
            assertEquals(Integer.valueOf(2), second.getAttempts());
            assertNotEquals(first.getLeaseToken(), second.getLeaseToken());
            assertThrows(IllegalStateException.class, () -> queue.fail(first.getId(), first.getLeaseToken(), FROM, "NETWORK_ERROR"));
            queue.complete(second.getId(), second.getLeaseToken());
            assertNull(queue.claimNext(60, 4));
            assertEquals("DONE", jdbc.queryForObject("SELECT status FROM cps_order_collection_retry WHERE id=?", String.class, saved.getId()));
        } finally { pool.shutdownNow(); }
    }

    @Test public void failedAttemptRemainsRetryableAndPreservesOriginalCursor() {
        queue.record(UUID.randomUUID(), Platform.PDD, OrderCollectionRetryQueue.TaskType.REFUND,
            FROM, TO, "last-42", "NETWORK_ERROR");
        OrderCollectionRetry first = queue.claimNext(60, 4);
        queue.fail(first.getId(), first.getLeaseToken(), LocalDateTime.of(2000, 1, 1, 0, 0), "RATE_LIMIT");
        OrderCollectionRetry next = queue.claimNext(60, 4);
        assertEquals(first.getId(), next.getId());
        assertEquals("last-42", next.getPageCursor());
        assertEquals("RATE_LIMIT", next.getErrorCode());
        assertEquals(Integer.valueOf(2), next.getAttempts());
        queue.complete(next.getId(), next.getLeaseToken());
    }

    @Test public void rejectsInvalidCursorAndInputs() {
        UUID run = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> queue.record(run, Platform.TBK,
            OrderCollectionRetryQueue.TaskType.INCREMENTAL, FROM, TO,
            "bad\ncursor", "NETWORK_ERROR"));
        assertThrows(IllegalArgumentException.class, () -> queue.record(run, Platform.TBK,
            OrderCollectionRetryQueue.TaskType.INCREMENTAL, FROM, TO, null, "Exception: token value"));
        assertThrows(IllegalArgumentException.class, () -> queue.record(run, Platform.TBK,
            OrderCollectionRetryQueue.TaskType.INCREMENTAL, TO, FROM, null, "NETWORK_ERROR"));
        assertThrows(IllegalArgumentException.class, () -> queue.claimNext(0, 1));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_order_collection_retry", Long.class));
    }
}
