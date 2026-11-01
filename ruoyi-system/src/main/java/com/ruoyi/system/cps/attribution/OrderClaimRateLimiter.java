package com.ruoyi.system.cps.attribution;

import java.util.List;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Per-user fixed-window admission. Rejected requests cannot affect another user's budget. */
@Component
public class OrderClaimRateLimiter {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>(
        "local n=tonumber(redis.call('GET',KEYS[1]) or '0'); "
        + "if n>=tonumber(ARGV[1]) then return 0 end; "
        + "n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return 1", Long.class);
    private final StringRedisTemplate redis;
    private final AttributionSettings settings;
    public OrderClaimRateLimiter(StringRedisTemplate redis, AttributionSettings settings) {
        this.redis = redis;
        this.settings = settings;
    }
    public void check(Long userId) {
        if (userId == null || userId <= 0 || !Long.valueOf(1).equals(redis.execute(CHECK,
                List.of("cps:attribution:claim:user:" + userId), Long.toString(settings.claimRequestsPerMinute())))) {
            throw new ServiceException("订单暂未完成绑定，请等待核实或稍后重试");
        }
    }
}
