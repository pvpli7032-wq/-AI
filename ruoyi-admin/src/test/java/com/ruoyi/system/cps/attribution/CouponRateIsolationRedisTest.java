package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionCouponException;

public class CouponRateIsolationRedisTest {
    @Test public void rejectedActorDoesNotConsumeSharedBudgets() {
        String port = System.getenv("LINE9_REDIS_PORT");
        if (port == null && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) port = "6379";
        assertNotNull("Provide LINE9_REDIS_PORT for real Redis", port);
        var connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(port)));
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String prefix = "line9-isolation-test:" + UUID.randomUUID() + ":";
        try {
            var config = mock(ISysConfigService.class);
            when(config.selectConfigByKey(anyString())).thenReturn("");
            var env = new MockEnvironment()
                .withProperty("wx.miniapp.rate-limit.max-requests-per-ip", "1")
                .withProperty("wx.miniapp.rate-limit.max-requests-global", "2");
            var limiter = new CouponRateLimiter(redis, new CouponSettings(config, env), prefix);
            limiter.check(1L, "address", Platform.TBK);
            for (int i = 0; i < 10; i++) {
                assertThrows(UnionCouponException.class, () -> limiter.check(1L, "address", Platform.TBK));
            }
            assertEquals("1", redis.opsForValue().get(prefix + "global"));
            assertEquals("1", redis.opsForValue().get(prefix + "platform:TBK"));
            limiter.check(2L, "address", Platform.TBK);
            assertEquals("2", redis.opsForValue().get(prefix + "global"));
        } finally {
            var keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
            connection.destroy();
        }
    }
}
