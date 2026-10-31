package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.ruoyi.union.adapter.Platform;

/** Complete immutable publication unit. Fractions, not percentages. No production defaults. */
public record RulePolicy(Platform platform, BigDecimal serviceRate, BigDecimal jdNonSelfRate,
        BigDecimal maximumCommission, BigDecimal minimumMargin, BigDecimal payoutCostRate,
        BigDecimal minimumRebate, boolean targetedWithTier, List<Proportion> proportions,
        List<Incentive> incentives) {
    public RulePolicy {
        proportions = List.copyOf(proportions);
        incentives = List.copyOf(incentives);
    }
    public enum Kind { TARGETED, NEWCOMER, DORMANCY, TIERED, BASE }
    public enum Mode { FIXED, COMMISSION_TIERS, CUMULATIVE_TIERS, INVITATION_TIERS }
    public enum IncentiveKind { INVITATION_COMMISSION, INVITATION_FIRST, INVITATION_FIRST_N,
        ORDER_COUNT, FIRST_N_SUBSIDY, FORCED_FIRST }
    /** Highest qualifying threshold wins; amount is an AND condition for cumulative tiers. */
    public record Tier(long count, BigDecimal amount, BigDecimal rate) { }
    public record Target(Set<Long> users, Set<String> items, Set<String> shops, Set<String> categories) {
        public Target {
            users = Set.copyOf(users); items = Set.copyOf(items);
            shops = Set.copyOf(shops); categories = Set.copyOf(categories);
        }
    }
    /** Dormancy must carry a public activity label; tiers never stack with invitation mode. */
    public record Proportion(String key, Kind kind, Mode mode, BigDecimal rate, Target target,
            long firstN, long dormantDays, String publicActivity, List<Tier> tiers) {
        public Proportion { tiers = List.copyOf(tiers); }
    }
    /** Fixed and proportional components are additive; invitation commission uses rebate as base. */
    public record Incentive(String key, IncentiveKind kind, BigDecimal fixed, BigDecimal rate,
            long n, long withinDays, Instant startsAt, Instant endsAt) { }
}
