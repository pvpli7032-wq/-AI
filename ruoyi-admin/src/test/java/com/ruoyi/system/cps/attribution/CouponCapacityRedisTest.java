package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.CommissionQuote;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionCouponException;

/** Real Redis gate uses the existing CI service or an explicitly supplied local test port. */
public class CouponCapacityRedisTest {
    private static final String PREFIX = "line9-test:" + UUID.randomUUID() + ":";
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private final Map<String,String> configured = new ConcurrentHashMap<>();
    private CouponSettings settings;
    private CouponRateLimiter limits;
    @BeforeClass public static void start() {
        String port = System.getenv("LINE9_REDIS_PORT");
        if (port == null && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) port = "6379";
        assertNotNull("Provide LINE9_REDIS_PORT for real Redis outside the configured GitHub CI service", port);
        int number = Integer.parseInt(port);
        assertTrue("Redis port must be valid", number > 0 && number <= 65535);
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", number));
        connections.afterPropertiesSet(); connections.start();
        redis = new StringRedisTemplate(connections); redis.afterPropertiesSet();
        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }
    @Before public void reset() {
        var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        configured.clear();
        ISysConfigService values = mock(ISysConfigService.class);
        when(values.selectConfigByKey(anyString())).thenAnswer(invocation -> configured.getOrDefault(invocation.getArgument(0), ""));
        MockEnvironment environment = new MockEnvironment()
            .withProperty("wx.miniapp.rate-limit.max-requests-per-ip", "3")
            .withProperty("wx.miniapp.rate-limit.max-requests-global", "100")
            .withProperty("wx.miniapp.rate-limit.max-failures", "2")
            .withProperty("wx.miniapp.rate-limit.lock-duration", "1");
        settings = new CouponSettings(values, environment);
        limits = new CouponRateLimiter(redis, settings, PREFIX);
    }
    @AfterClass public static void stop() {
        if (redis != null) { var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); }
        if (connections != null) connections.destroy();
    }
    @Test public void concurrentCallsCannotExceedOneUsersBudget() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) results.add(pool.submit(() -> {
                start.await();
                try { limits.check(7L, "test-address", Platform.TBK); return true; }
                catch (UnionCouponException denied) { assertEquals("RATE_LIMITED", denied.code()); return false; }
            }));
            start.countDown(); int accepted = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted);
            // Another authenticated user on the same address retains a separate budget.
            limits.check(8L, "test-address", Platform.TBK);
        } finally { pool.shutdownNow(); }
    }
    @Test public void globalLimitAndAnonymousIpLimitUseExistingConfiguration() {
        for (int i = 0; i < 3; i++) limits.check(null, "203.0.113.1", Platform.TBK);
        assertThrows(UnionCouponException.class, () -> limits.check(null, "203.0.113.1", Platform.TBK));
        limits.check(null, "203.0.113.2", Platform.TBK);
        configured.put("cps.coupon.max-requests-global", "5");
        assertThrows(UnionCouponException.class, () -> limits.check(99L, "new-address", Platform.JD));
    }
    @Test public void failureLockAndSuccessResetAreAtomic() {
        limits.failed(7L, "a"); limits.succeeded(7L, "a"); limits.failed(7L, "a");
        limits.check(7L, "a", Platform.JD);
        limits.failed(7L, "a");
        assertThrows(UnionCouponException.class, () -> limits.check(7L, "b", Platform.PDD));
        Long ttl = redis.getExpire(PREFIX + "lock:user:7");
        assertNotNull(ttl); assertTrue(ttl > 0 && ttl <= 60);
    }
    @Test public void cachedQuoteHasExpiryAndContainsNoUserLink() {
        CouponQuoteCache cache = new CouponQuoteCache(redis, settings, PREFIX);
        CommissionQuote quote = new CommissionQuote(new BigDecimal("0.20"), new BigDecimal("10.00"), new BigDecimal("90.00"));
        cache.put(Platform.TBK, "123", quote);
        assertEquals(quote, cache.get(Platform.TBK, "123"));
        String key = PREFIX + "quote:v1:TBK:" + CouponRateLimiter.hash("123");
        assertEquals("0.20|10.00|90.00", redis.opsForValue().get(key));
        Long ttl = redis.getExpire(key); assertNotNull(ttl); assertTrue(ttl > 0 && ttl <= 60);
        assertNull(cache.get(Platform.JD, "123"));
    }
}
