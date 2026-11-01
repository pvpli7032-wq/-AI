package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import org.springframework.stereotype.Component;

/** freezePolicy is immutable JSON of Configuration, supplied by the terms adapter. No live/default settings. */
@Component
public class ConfiguredSettlementFreezePort implements SettlementFreezePort {
    public record Band(BigDecimal minimumAmount, Long seconds) {}
    public record Configuration(Map<Platform,List<Band>> tiers, Map<Platform,Long> fastSeconds, Boolean userDisabledFreeze) {}
    @Override
    public Duration duration(SettlementTermsPort.Terms terms, UnionOrderLine line, SettlementTermsPort.Benefit benefit) {
        Configuration config=JSON.parseObject(terms.freezePolicy(),Configuration.class);
        if (config==null || config.tiers()==null || config.fastSeconds()==null || config.userDisabledFreeze()==null)
            throw new IllegalArgumentException("Missing snapshotted freeze policy configuration");
        Map<Platform,List<FreezePolicy.Tier>> tiers=new EnumMap<>(Platform.class);
        Map<Platform,Duration> fast=new EnumMap<>(Platform.class);
        config.tiers().forEach((platform,bands) -> tiers.put(platform,bands.stream().map(b -> {
            if (b.seconds()==null) throw new IllegalArgumentException("Missing freeze seconds");
            return new FreezePolicy.Tier(b.minimumAmount(),Duration.ofSeconds(b.seconds()));
        }).toList()));
        config.fastSeconds().forEach((platform,seconds) -> fast.put(platform,Duration.ofSeconds(seconds)));
        FreezePolicy policy=new FreezePolicy(tiers,fast);
        if (line.receivedAt()!=null)
            return policy.evaluate(line.platform(),benefit.amount(),line.paidAt(),line.receivedAt(),line.settledAt(),config.userDisabledFreeze()).duration();
        // First observation can be SETTLED with no receipt timestamp. Apply configured tiers,
        // but do not invent receipt time or infer the <=24h receipt shortcut.
        List<FreezePolicy.Tier> bands=tiers.get(line.platform());
        if (bands==null) throw new IllegalArgumentException("Platform freeze policy missing");
        Duration duration=bands.getFirst().duration();
        for (FreezePolicy.Tier band:bands) if (benefit.amount().compareTo(band.minimumAmount())>=0) duration=band.duration();
        return config.userDisabledFreeze() ? Duration.ZERO : duration;
    }
}
