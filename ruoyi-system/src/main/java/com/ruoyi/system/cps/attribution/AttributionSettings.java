package com.ruoyi.system.cps.attribution;

import java.time.Duration;
import java.util.List;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the same database parameters edited by the existing configuration UI. */
@Component
public class AttributionSettings {
    private final JdbcTemplate jdbc;
    public AttributionSettings(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Duration leaseDuration() { return Duration.ofHours(requiredPositive("cps.attribution.lease.hours")); }
    public Duration unclaimedDuration() { return Duration.ofDays(requiredPositive("cps.attribution.unclaimed.days")); }
    public Duration claimDuration() { return Duration.ofHours(requiredPositive("cps.attribution.claim.hours")); }
    public long claimRequestsPerMinute() { return requiredPositive("cps.attribution.claim.requests-per-minute"); }
    public long claimMaxPending() { return requiredPositive("cps.attribution.claim.max-pending"); }
    public long settlementMaxAttempts() { return requiredPositive("cps.attribution.settlement.max-attempts"); }
    public Duration matchDuration() {
        long hours=requiredPositive("cps.attribution.item.match.hours");
        if(hours>24) throw new ServiceException("商品匹配候选窗口不能超过24小时");
        return Duration.ofHours(hours);
    }
    private long requiredPositive(String key) {
        List<String> values = jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_key=?", String.class, key);
        try {
            if (values.size() != 1) throw new IllegalArgumentException();
            long value = Long.parseLong(values.get(0).trim());
            if (value <= 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException error) {
            throw new ServiceException("归因参数尚未正确配置: " + key);
        }
    }
}
