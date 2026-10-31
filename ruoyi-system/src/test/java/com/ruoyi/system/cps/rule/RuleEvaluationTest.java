package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RulePolicy.*;
import static com.ruoyi.system.cps.rule.RuleDecision.*;
import com.ruoyi.union.adapter.Platform;
import com.alibaba.fastjson2.JSON;

/** Synthetic values only; these are not rollout defaults. */
public class RuleEvaluationTest {
    static final Instant AT = Instant.parse("2020-01-01T00:00:00Z");
    static BigDecimal d(String s) { return new BigDecimal(s); }
    static Target any() { return new Target(Set.of(), Set.of(), Set.of(), Set.of()); }
    static Proportion base(String rate) {
        return new Proportion("base", Kind.BASE, Mode.FIXED, d(rate), any(), 0, 0, null, List.of());
    }
    static RulePolicy policy(List<Proportion> rows) {
        return new RulePolicy(Platform.TBK, d("0.10"), d("0"), d("10.00"), d("-1.00"), d("0.08"), d("0"), false, rows, List.of());
    }
    static Evidence evidence() {
        return new Evidence(AT, "audit:test-only", 2L, d("10"), 5L, 9L, false,
            AT.minusSeconds(100000), AT.minusSeconds(86400 * 50), 1L, null, null, null);
    }
    static Facts facts(Evidence e) { return new Facts(7, Platform.TBK, "sku", AT, d("2.00"), e); }
    static Snapshot snapshot(RulePolicy p, Evidence e) {
        return new Snapshot(1, 7, p.platform(), "o", "s", "sku", AT, 1, p, e, RuleEvaluation.choose(p, facts(e)));
    }
    @Test public void zeroIsConfiguredNotMissingAndCollectionsAreImmutable() {
        RulePolicy p = policy(List.of(base("0")));
        assertEquals(0, RuleEvaluation.calculate(snapshot(p, evidence()), d("3.00")).rebate().signum());
        assertThrows(UnsupportedOperationException.class, () -> p.proportions().add(base("1")));
    }
    @Test public void tierSelectionLocksEstimatedBandForActualRecalculation() {
        Proportion base = new Proportion("base", Kind.BASE, Mode.COMMISSION_TIERS, d("0.1"), any(), 0, 0, null,
            List.of(new Tier(0, d("1"), d("0.3")), new Tier(0, d("3"), d("0.2"))));
        Snapshot s = snapshot(policy(List.of(base)), evidence());
        assertEquals(d("0.3"), s.decision().userRate());
        assertEquals(d("1.35"), RuleEvaluation.calculate(s, d("5.00")).rebate());
    }
    @Test public void targetedPrecedesNewcomerAndUnknownShopNeverMatches() {
        Proportion target = new Proportion("target", Kind.TARGETED, Mode.FIXED, d("0"),
            new Target(Set.of(7L), Set.of(), Set.of(), Set.of()), 0, 0, null, List.of());
        Proportion newcomer = new Proportion("new", Kind.NEWCOMER, Mode.FIXED, d("0.7"), any(), 5, 0, null, List.of());
        assertEquals("target", RuleEvaluation.choose(policy(List.of(base("0.2"), newcomer, target)), facts(Evidence.unknown(AT))).proportionKey());
        Proportion shop = new Proportion("shop", Kind.TARGETED, Mode.FIXED, d("0.9"),
            new Target(Set.of(), Set.of(), Set.of("shop"), Set.of("category")), 0, 0, null, List.of());
        assertEquals("base", RuleEvaluation.choose(policy(List.of(base("0.2"), shop)), facts(Evidence.unknown(AT))).proportionKey());
    }
    @Test public void missingQualificationFailsRatherThanInventingNewcomer() {
        Proportion n = new Proportion("new", Kind.NEWCOMER, Mode.FIXED, d("0.7"), any(), 5, 0, null, List.of());
        assertThrows(IllegalStateException.class, () -> RuleEvaluation.choose(policy(List.of(base("0.2"), n)), facts(Evidence.unknown(AT))));
    }
    @Test public void cumulativeTiersRequireBothCountAndAmount() {
        Proportion t = new Proportion("tier", Kind.TIERED, Mode.CUMULATIVE_TIERS, d("0.1"), any(), 0, 0, null,
            List.of(new Tier(1, d("5"), d("0.2")), new Tier(3, d("10"), d("0.5"))));
        assertEquals(d("0.2"), RuleEvaluation.choose(policy(List.of(base("0"), t)), facts(evidence())).userRate());
    }
    @Test public void unmetCumulativeThresholdFallsBackToBase() {
        Proportion t = new Proportion("tier", Kind.TIERED, Mode.CUMULATIVE_TIERS, d("0.1"), any(), 0, 0, null,
            List.of(new Tier(3, d("10"), d("0.5"))));
        assertEquals("base", RuleEvaluation.choose(policy(List.of(base("0.2"), t)), facts(evidence())).proportionKey());
    }
    @Test public void optionalCombinationUsesSelectedTierNotFutureHighestTier() {
        Proportion target = new Proportion("target", Kind.TARGETED, Mode.FIXED, d("0.1"),
            new Target(Set.of(7L), Set.of(), Set.of(), Set.of()), 0, 0, null, List.of());
        Proportion tier = new Proportion("tier", Kind.TIERED, Mode.CUMULATIVE_TIERS, d("0"), any(), 0, 0, null,
            List.of(new Tier(1, d("5"), d("0.2")), new Tier(3, d("10"), d("0.9"))));
        RulePolicy p = policy(List.of(base("0"), target, tier));
        RulePolicy combined = new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), p.maximumCommission(), p.minimumMargin(),
            p.payoutCostRate(), p.minimumRebate(), true, p.proportions(), List.of());
        assertEquals(d("0.1"), RuleEvaluation.choose(p, facts(evidence())).userRate());
        assertEquals(d("0.2"), RuleEvaluation.choose(combined, facts(evidence())).userRate());
    }
    @Test public void invitationModeUsesOnlyQualifiedInvitations() {
        Proportion i = new Proportion("base", Kind.BASE, Mode.INVITATION_TIERS, d("0.1"), any(), 0, 0, null,
            List.of(new Tier(3, d("0"), d("0.4")), new Tier(8, d("0"), d("0.6"))));
        assertEquals(d("0.4"), RuleEvaluation.choose(policy(List.of(i)), facts(evidence())).userRate());
        assertThrows(IllegalStateException.class, () -> RuleEvaluation.choose(policy(List.of(i)), facts(Evidence.unknown(AT))));
    }
    @Test public void ambiguousRulesFail() {
        Proportion n = new Proportion("new", Kind.NEWCOMER, Mode.FIXED, d("0.7"), any(), 5, 0, null, List.of());
        Proportion dorm = new Proportion("return", Kind.DORMANCY, Mode.FIXED, d("0.8"), any(), 2, 30, "Public return activity", List.of());
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.choose(policy(List.of(base("0.2"), n, dorm)), facts(evidence())));
    }
    @Test public void dormancyMustBePublicAndDecisionCarriesActivity() {
        Proportion dorm = new Proportion("return", Kind.DORMANCY, Mode.FIXED, d("0.8"), any(), 2, 30, "Public return activity", List.of());
        assertEquals(List.of("Public return activity"), RuleEvaluation.choose(policy(List.of(base("0.2"), dorm)), facts(evidence())).publicActivities());
        Proportion hidden = new Proportion("return", Kind.DORMANCY, Mode.FIXED, d("0.8"), any(), 2, 30, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.validate(policy(List.of(base("0.2"), hidden))));
    }
    @Test public void roundedWorstMarginIncludesZeroMinimumAndPayoutCosts() {
        RulePolicy p = policy(List.of(base("1")));
        assertEquals(d("-0.72"), RuleEvaluation.worstMargin(p));
        RulePolicy rejects = new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), d("0.05"),
            d("0"), d("0.9"), d("0"), false, List.of(base("1")), List.of());
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.worstMargin(rejects));
        RulePolicy minimum = new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), d("0.01"),
            d("0"), d("0"), d("0.01"), false, List.of(base("0")), List.of());
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.worstMargin(minimum));
    }
    @Test public void commissionCapAndCentsCannotBeBypassed() {
        Snapshot s = snapshot(policy(List.of(base("0.4"))), evidence());
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.calculate(s, d("10.01")));
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.calculate(s, d("0.001")));
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.calculate(s, d("-1")));
    }
    @Test public void fullSnapshotJsonRoundTripPreservesRatesEvidenceAndIdentity() {
        Snapshot s = snapshot(policy(List.of(base("0.4"))), evidence());
        assertEquals(s, JSON.parseObject(JSON.toJSONString(s), Snapshot.class));
    }
    @Test public void rewardCostsCannotBeAssertedByClient() {
        RulePolicy p = policy(List.of(base("0.4")));
        RulePolicy rewards = new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), p.maximumCommission(), p.minimumMargin(),
            p.payoutCostRate(), p.minimumRebate(), false, p.proportions(),
            List.of(new Incentive("first", IncentiveKind.INVITATION_FIRST, d("1"), d("0"), 1, 5, null, null)));
        assertThrows(IllegalStateException.class, () -> RuleEvaluation.worstMargin(rewards));
    }
    @Test public void jdFeeRequiresAuthoritativeOperationEvidence() {
        RulePolicy p = new RulePolicy(Platform.JD, d("0.1"), d("0.2"), d("10"), d("-1"), d("0"), d("0"), false, List.of(base("0.5")), List.of());
        Snapshot unknown = new Snapshot(1, 7, Platform.JD, "o", "s", "sku", AT, 1, p, Evidence.unknown(AT),
            new RuleDecision("base", d("0.5"), List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> RuleEvaluation.calculate(unknown, d("10")));
        Evidence e = new Evidence(AT, "official:test-only", null, null, null, null, null, null, null, null, null, null, false);
        Snapshot known = new Snapshot(1, 7, Platform.JD, "o", "s", "sku", AT, 1, p, e, unknown.decision());
        assertEquals(d("7.20"), RuleEvaluation.calculate(known, d("10")).netCommission());
    }
}
