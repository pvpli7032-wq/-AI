package com.ruoyi.system.cps.attribution;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionCouponException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Redis atomically enforces global, platform and authenticated-user budgets across nodes. */
@Component
public class CouponRateLimiter {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>(
        "if redis.call('EXISTS',KEYS[4])==1 then return 0 end "
        + "for i=1,3 do local n=redis.call('INCR',KEYS[i]); if n==1 then redis.call('EXPIRE',KEYS[i],60) end; "
        + "if n>tonumber(ARGV[i]) then return 0 end end; return 1", Long.class);
    private static final DefaultRedisScript<Long> FAIL = new DefaultRedisScript<>(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; "
        + "if n>=tonumber(ARGV[1]) then redis.call('SET',KEYS[2],'1','EX',ARGV[2]) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final CouponSettings settings;
    private final String prefix;
    @Autowired
    public CouponRateLimiter(StringRedisTemplate redis, CouponSettings settings) { this(redis, settings, "cps:coupon:"); }
    CouponRateLimiter(StringRedisTemplate redis, CouponSettings settings, String prefix) {
        this.redis = redis; this.settings = settings; this.prefix = prefix;
    }
    public void check(Long userId, String remoteAddress, Platform platform) {
        String actor = actor(userId, remoteAddress);
        int global = settings.existingLimit("max-requests-global", 1000);
        int ip = settings.existingLimit("max-requests-per-ip", 10);
        int perUser = settings.integer("cps.coupon.max-requests-per-user", ip, 1, 1000000);
        int perPlatform = settings.integer("cps.coupon.max-requests-" + platform.name().toLowerCase(), global, 1, 1000000);
        Long allowed = redis.execute(CHECK, List.of(prefix + "global", prefix + "platform:" + platform,
            prefix + "actor:" + actor, prefix + "lock:" + actor),
            Integer.toString(global), Integer.toString(perPlatform), Integer.toString(userId == null ? ip : perUser));
        if (!Long.valueOf(1).equals(allowed)) throw new UnionCouponException("RATE_LIMITED", "查询频繁，请稍后再试");
    }
    public void failed(Long userId, String remoteAddress) {
        String actor = actor(userId, remoteAddress);
        redis.execute(FAIL, List.of(prefix + "fail:" + actor, prefix + "lock:" + actor),
            Integer.toString(settings.existingLimit("max-failures", 5)),
            Integer.toString(Math.multiplyExact(settings.existingLimit("lock-duration", 30), 60)));
    }
    public void succeeded(Long userId, String remoteAddress) { redis.delete(prefix + "fail:" + actor(userId, remoteAddress)); }
    private static String actor(Long userId, String address) {
        return userId == null ? "ip:" + hash(address == null ? "unknown" : address) : "user:" + userId;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
