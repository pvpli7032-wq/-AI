package com.ruoyi.system.cps.rule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.mapper.RuleVersionMapper;
import com.ruoyi.union.adapter.Platform;

/** Public projection deliberately excludes target identities, private rates and margin configuration. */
@Service
public class RuleActivityService {
    public record Activity(Platform platform, String type, String label, long orderCount,
            Instant startsAt, Instant endsAt) { }
    private final RuleVersionMapper versions;
    public RuleActivityService(RuleVersionMapper versions) { this.versions = versions; }
    @Transactional(readOnly = true)
    public List<Activity> current() {
        Instant now = Instant.now();
        var result = new ArrayList<Activity>();
        for (Platform platform : Platform.values()) {
            var version = versions.asOf(platform.name(), now);
            if (version == null || !version.enabled()) continue;
            var policy = JSON.parseObject(version.config(), RulePolicy.class);
            for (var proportion : policy.proportions()) {
                if (proportion.kind() == RulePolicy.Kind.DORMANCY)
                    result.add(new Activity(platform, "DORMANCY", proportion.publicActivity(),
                            proportion.firstN(), null, null));
            }
            for (var incentive : policy.incentives()) {
                if (incentive.startsAt() != null && now.isBefore(incentive.startsAt())
                        || incentive.endsAt() != null && !now.isBefore(incentive.endsAt())) continue;
                result.add(new Activity(platform, incentive.kind().name(), incentive.kind().name(),
                        incentive.n(), incentive.startsAt(), incentive.endsAt()));
            }
        }
        return List.copyOf(result);
    }
}
