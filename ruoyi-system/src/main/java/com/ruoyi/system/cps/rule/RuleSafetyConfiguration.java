package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import com.ruoyi.system.cps.mapper.RuleMapper;

/** Authoritative, uncached server commission limit read from sys_config in one statement.
 * Administrators must explicitly configure the decimal key for each platform (TBK/JD/PDD):
 * cps.rule.safety.{PLATFORM}.maximumActualCommission : positive cents, bounded to prevent abusive inputs.
 * No defaults/seeds/real rates. Missing, duplicate (even identical), case-ambiguous or malformed keys
 * fail closed. Sys_config administration must remain permission-protected, separate from rule editing.
 * The server commission cap is frozen in publication audit. Margin is not a save/publish gate.
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
        String maxKey = prefix + "maximumActualCommission";
        List<Entry> entries = mapper.financialLimits(maxKey);
        if (entries == null || entries.size() != 1)
            throw new IllegalStateException("exactly one sys_config row per rule safety key required: " + maxKey);
        Entry max = unique(entries, maxKey);
        BigDecimal maximum = decimal(max);
        if (maximum.signum() <= 0 || maximum.compareTo(BigDecimal.valueOf(RuleEvaluation.MAX_ACTUAL_COMMISSION_CENTS, 2)) > 0)
            throw new IllegalStateException("server maximumActualCommission outside bounded domain");
        return new RulePorts.Limits(maximum,
            "sys_config:" + max.id() + ":" + maxKey + "=" + maximum.toPlainString());
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
