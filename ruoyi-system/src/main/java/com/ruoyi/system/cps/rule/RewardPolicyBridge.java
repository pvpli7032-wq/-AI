package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/**
 * Dependency-free bridge for the rules owner's policy incentives. The caller explicitly maps its
 * policy fields to these records; no RulePolicy dependency, reflection, live lookup, or default money.
 * Rules must validate full-stack margin with these SAME values before payment snapshot creation.
 * from/until is an explicit half-open payment-time window, never a guessed registration-day duration.
 */
public final class RewardPolicyBridge {
    private RewardPolicyBridge() {}
    public record Incentive(Type type, Mode mode, int n, BigDecimal value,
                            Set<String> platforms, Instant from, Instant until) {
        public Incentive { platforms = Set.copyOf(platforms); }
    }
    public record Policy(long version, BigDecimal minimumPaid, BigDecimal minimumCommission,
                         BigDecimal maximumReward, List<Incentive> incentives) {
        public Policy { incentives = List.copyOf(incentives); }
    }
    public static List<Config> configs(Policy policy) {
        Objects.requireNonNull(policy);
        List<Config> configs = policy.incentives().stream().map(i -> new Config(i.type(), policy.version(),
            i.mode(), i.n(), i.value(), i.platforms(), i.from(), i.until())).toList();
        if (configs.stream().map(Config::type).distinct().count() != configs.size())
            throw new IllegalArgumentException("duplicate reward type");
        return configs;
    }
    public static Snapshot snapshot(long orderId, OrderKey order, long buyerId, Long paymentTimeInviter,
                                    Instant paidAt, boolean historicalUnknown, Policy policy) {
        return new Snapshot(orderId, order, buyerId, paymentTimeInviter, paidAt, historicalUnknown,
            policy.minimumPaid(), policy.minimumCommission(), policy.maximumReward(), configs(policy));
    }
}
