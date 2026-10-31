package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.union.adapter.Platform;

/** Append-only server settings. Delayed orders resolve the version effective at payment, not ingestion. */
@Service
public class SettlementConfigurationService {
    public record Settings(ConfiguredSettlementFreezePort.Configuration freeze,
            BigDecimal minimumPaid, BigDecimal minimumCommission, BigDecimal maximumReward) { }
    public record Version(long id, Instant effectiveAt, Settings settings) { }
    private final JdbcTemplate jdbc;
    public SettlementConfigurationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional public void publish(Settings settings, long operator) {
        if (operator <= 0) throw new IllegalArgumentException("authenticated operator required");
        validate(settings);
        jdbc.update("INSERT INTO cps_settlement_configuration(configuration_json,created_by) VALUES(?,?)",
                JSON.toJSONString(settings), operator);
    }
    @Transactional(readOnly=true) public List<Version> history() {
        return jdbc.query("SELECT id,effective_at,configuration_json FROM cps_settlement_configuration ORDER BY id DESC LIMIT 100",
                (r,n) -> new Version(r.getLong(1), r.getTimestamp(2).toInstant(), JSON.parseObject(r.getString(3), Settings.class)));
    }
    public Version atPayment(Instant at) {
        if (at == null || at.isAfter(Instant.now())) throw new IllegalArgumentException("trusted payment time required");
        List<Version> found = jdbc.query("SELECT id,effective_at,configuration_json FROM cps_settlement_configuration WHERE effective_at<=? ORDER BY effective_at DESC,id DESC LIMIT 1",
                (r,n) -> new Version(r.getLong(1), r.getTimestamp(2).toInstant(), JSON.parseObject(r.getString(3), Settings.class)), Timestamp.from(at));
        if (found.isEmpty()) throw new IllegalStateException("No settlement configuration existed at payment; manual review required");
        validate(found.getFirst().settings());
        return found.getFirst();
    }
    public static void validate(Settings settings) {
        if (settings == null || settings.freeze() == null) throw new IllegalArgumentException("freeze configuration required");
        for (BigDecimal amount : new BigDecimal[] { settings.minimumPaid(), settings.minimumCommission(), settings.maximumReward() }) {
            if (amount == null || amount.signum()<0 || amount.stripTrailingZeros().scale()>2 || amount.precision()>18)
                throw new IllegalArgumentException("explicit nonnegative cent amount required");
        }
        var config = settings.freeze();
        if (config.tiers()==null || config.fastSeconds()==null || config.userDisabledFreeze()==null)
            throw new IllegalArgumentException("complete freeze settings required");
        var tiers = new EnumMap<Platform,List<FreezePolicy.Tier>>(Platform.class);
        var fast = new EnumMap<Platform,Duration>(Platform.class);
        config.tiers().forEach((p,bands) -> tiers.put(p, bands.stream().map(b -> {
            if (b.seconds()==null) throw new IllegalArgumentException("explicit freeze duration required");
            return new FreezePolicy.Tier(b.minimumAmount(), Duration.ofSeconds(b.seconds()));
        }).toList()));
        config.fastSeconds().forEach((p,seconds) -> fast.put(p, Duration.ofSeconds(seconds)));
        new FreezePolicy(tiers, fast);
    }
}
