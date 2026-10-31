package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import com.ruoyi.system.cps.mapper.RuleMapper;

/** Authoritative, uncached server finance limits read from sys_config in one statement.
 * Administrators must explicitly configure BOTH decimal keys for each platform (TBK/JD/PDD):
 * cps.rule.safety.{PLATFORM}.maximumActualCommission : positive cents, <= proof domain
 * cps.rule.safety.{PLATFORM}.minimumMargin : signed cents (negative explicitly permits bounded loss).
 * No defaults/seeds/real rates. Missing, duplicate (even identical), case-ambiguous or malformed keys
 * fail closed. Sys_config administration must remain permission-protected, separate from rule editing.
 * Policy may tighten, never loosen these limits. Values and config IDs are frozen in publication audit.
 * Reward configuration is exclusively RulePolicy.incentives, never a separate RewardService write path.
 */
@Component
public final class RuleSafetyConfiguration implements RulePorts.PublicationSafety {
    public static final String PREFIX = "cps.rule.safety.";
    private final RuleMapper mapper;
    public RuleSafetyConfiguration(RuleMapper mapper) { this.mapper = mapper; }
    public record Entry(long id, String key, String value) { }
    @Override public RulePorts.Limits limits(RulePolicy policy) {
        String prefix = PREFIX + policy.platform().name() + ".";
        String maxKey = prefix + "maximumActualCommission", minKey = prefix + "minimumMargin";
        List<Entry> entries = mapper.financialLimits(maxKey, minKey);
        if (entries == null || entries.size() != 2)
            throw new IllegalStateException("exactly one sys_config row per rule safety key required: " + prefix);
        Entry max = unique(entries, maxKey), min = unique(entries, minKey);
        BigDecimal maximum = decimal(max), minimum = decimal(min);
        if (maximum.signum() <= 0 || maximum.compareTo(BigDecimal.valueOf(RuleEvaluation.MAX_PROOF_CENTS, 2)) > 0)
            throw new IllegalStateException("server maximumActualCommission outside bounded proof domain");
        return new RulePorts.Limits(maximum, minimum,
            "sys_config:" + max.id() + ":" + maxKey + "=" + maximum.toPlainString()
                + ";" + min.id() + ":" + minKey + "=" + minimum.toPlainString());
    }
    private static Entry unique(List<Entry> entries, String key) {
        List<Entry> matching = entries.stream().filter(e -> key.equals(e.key())).toList();
        if (matching.size() != 1) throw new IllegalStateException("missing/duplicate/ambiguous rule safety key: " + key);
        return matching.getFirst();
    }
    private static BigDecimal decimal(Entry entry) {
        String value = entry.value();
        if (value == null || !value.matches("-?(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?"))
            throw new IllegalStateException("rule safety value must be explicit signed decimal cents: " + entry.key());
        return new BigDecimal(value);
    }
}
