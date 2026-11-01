package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.ruoyi.union.adapter.Platform;

/** Complete immutable publication unit. Fractions, not percentages. No production defaults.
 * minimumRebate is a rounded rebate floor, including at zero commission, and is fully costed. */
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
    /** Exclusive immutable reward configuration; no independent RewardService configuration.
     * INVITATION_COMMISSION is rebate ratio only. FIRST_N_SUBSIDY is fixed OR rebate ratio.
     * Other kinds are fixed only. ORDER_COUNT pays once when prior valid orders == n-1.
     * FORCED_FIRST is a top-up to fixed guaranteed rebate within withinDays of signup, first valid
     * order only; it does not alter the rebate base for invitation commission. All components may
     * stack across distinct keys, including multiple forced guarantees (each relative to base rebate).
     * Periods are [startsAt,endsAt); null endpoints mean unbounded, not disabled. */
    public record Incentive(String key, IncentiveKind kind, BigDecimal fixed, BigDecimal rate,
            long n, long withinDays, Instant startsAt, Instant endsAt) { }
}
