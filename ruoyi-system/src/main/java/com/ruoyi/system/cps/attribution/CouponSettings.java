package com.ruoyi.system.cps.attribution;

import java.math.BigDecimal;
import com.ruoyi.system.service.ISysConfigService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Operational values and user-facing copy are editable through sys_config. */
@Component
public class CouponSettings {
    private final ISysConfigService config;
    private final Environment environment;
    public CouponSettings(ISysConfigService config, Environment environment) {
        this.config = config;
        this.environment = environment;
    }
    public String text(String key, String fallback) {
        String value = config.selectConfigByKey(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    public int integer(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(text(key, Integer.toString(fallback)));
            if (value < min || value > max) throw new IllegalArgumentException("Invalid setting: " + key);
            return value;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid setting: " + key); }
    }
    public BigDecimal nonNegative(String key, BigDecimal fallback) {
        try {
            BigDecimal value = new BigDecimal(text(key, fallback.toPlainString()));
            if (value.signum() < 0) throw new IllegalArgumentException("Invalid setting: " + key);
            return value;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid setting: " + key); }
    }
    public int existingLimit(String name, int fallback) {
        int value = environment.getProperty("wx.miniapp.rate-limit." + name, Integer.class, fallback);
        return integer("cps.coupon." + name, value, 1, 1000000);
    }
    public boolean enabled(String key, boolean fallback) {
        return Boolean.parseBoolean(text(key, Boolean.toString(fallback)));
    }
}
