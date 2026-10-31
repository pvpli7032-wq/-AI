package com.ruoyi.system.cps.attribution;

import com.ruoyi.union.adapter.CommissionQuote;
import com.ruoyi.union.adapter.Platform;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared product facts only. User-specific links and PID leases are never cached here. */
@Component
public class CouponQuoteCache {
    private final StringRedisTemplate redis;
    private final CouponSettings settings;
    private final String prefix;
    @Autowired
    public CouponQuoteCache(StringRedisTemplate redis, CouponSettings settings) { this(redis, settings, "cps:coupon:"); }
    CouponQuoteCache(StringRedisTemplate redis, CouponSettings settings, String prefix) {
        this.redis = redis; this.settings = settings; this.prefix = prefix;
    }
    private String key(Platform p, String item) { return prefix + "quote:v1:" + p + ":" + CouponRateLimiter.hash(item); }
    public CommissionQuote get(Platform platform, String itemId) {
        String value = redis.opsForValue().get(key(platform, itemId));
        if (value == null) return null;
        try {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 3) return null;
            return new CommissionQuote(new BigDecimal(fields[0]), new BigDecimal(fields[1]), new BigDecimal(fields[2]));
        } catch (NumberFormatException ex) { redis.delete(key(platform, itemId)); return null; }
    }
    public void put(Platform platform, String itemId, CommissionQuote quote) {
        String value = quote.rate().toPlainString() + "|" + quote.couponAmount().toPlainString() + "|" + quote.finalPrice().toPlainString();
        redis.opsForValue().set(key(platform, itemId), value,
            Duration.ofSeconds(settings.integer("cps.coupon.cache-seconds", 60, 1, 3600)));
    }
}
