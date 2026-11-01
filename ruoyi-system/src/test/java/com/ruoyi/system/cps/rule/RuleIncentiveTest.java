package com.ruoyi.system.cps.rule;

import java.util.List;
import org.junit.Test;
import com.alibaba.fastjson2.JSON;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RulePolicy.*;
import static com.ruoyi.system.cps.rule.RuleDecision.*;
import static com.ruoyi.system.cps.rule.RuleEvaluationTest.*;

/** Synthetic cost examples only. Advisory payments never write reward/ledger state. */
public class RuleIncentiveTest {
    static Incentive incentive(String key, IncentiveKind kind, String fixed, String ratio, long n) {
        return new Incentive(key, kind, d(fixed), d(ratio), n, 5, null, null);
    }
    static RulePolicy configured(List<Incentive> incentives, String minimum) {
        RulePolicy p = policy(List.of(base("0.5")));
        return new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), p.maximumCommission(), d(minimum),
            p.payoutCostRate(), p.minimumRebate(), false, p.proportions(), incentives);
    }
    static Evidence prior(long count, Long inviter) {
        return new Evidence(AT, "historical:test-only", count, d("1"), 0L, inviter, false,
            AT.minusSeconds(100000), null, null, null, null, null);
    }
    static List<Incentive> all() {
        return List.of(incentive("ratio", IncentiveKind.INVITATION_COMMISSION, "0", "0.2", 0),
            incentive("first", IncentiveKind.INVITATION_FIRST, "0.10", "0", 0),
            incentive("inviteN", IncentiveKind.INVITATION_FIRST_N, "0.20", "0", 3),
            incentive("count", IncentiveKind.ORDER_COUNT, "0.30", "0", 1),
            incentive("subsidy", IncentiveKind.FIRST_N_SUBSIDY, "0", "0.1", 3),
            incentive("forced", IncentiveKind.FORCED_FIRST, "1.00", "0", 0));
    }
    @Test public void allSixKindsStackWithInvitationStrictlyOnRebateAndCostsIncludeAll() {
        Snapshot s = snapshot(configured(all(), "-10"), prior(0, 9L));
        Calculation c = RuleEvaluation.calculate(s, d("2"));
        assertEquals(6, c.rewards().size()); assertEquals(d("0.90"), c.rebate());
        assertEquals(d("0.18"), c.rewards().get(0).amount());
        assertEquals(Long.valueOf(9), c.rewards().get(0).recipientUserId());
        assertEquals(Long.valueOf(7), c.rewards().get(4).recipientUserId());
        assertEquals(d("0.10"), c.rewards().get(5).amount());
        assertTrue(c.rewards().stream().allMatch(Payment::requiresValidOrderAndFreeze));
        assertEquals(d("0.15"), c.payoutCost()); assertEquals(d("-0.22"), c.margin());
        assertEquals(s, JSON.parseObject(JSON.toJSONString(s), Snapshot.class));
    }
    @Test public void legacySnapshotsWithoutReviewFieldRemainReadable() {
        Snapshot s = snapshot(configured(List.of(), "-10"), prior(0, 9L));
        var document = JSON.parseObject(JSON.toJSONString(s));
        document.getJSONObject("decision").remove("manualReviewReasons");
        assertEquals(s, JSON.parseObject(document.toJSONString(), Snapshot.class));
    }
    @Test public void conservativeStackProofAllowsOnlyExplicitBoundedLoss() {
        assertEquals(d("-1.73"), RuleEvaluation.worstMargin(configured(all(), "-1.73")));
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.worstMargin(configured(all(), "-1.72")));
    }
    @Test public void subsidyFixedOrRatioInvariantAndOtherKindsCannotSmuggleRatio() {
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.validate(configured(List.of(
            incentive("bad", IncentiveKind.FIRST_N_SUBSIDY, "1", "0.1", 1)), "-10")));
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.validate(configured(List.of(
            incentive("bad", IncentiveKind.ORDER_COUNT, "1", "0.1", 1)), "-10")));
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.validate(configured(List.of(
            incentive("bad", IncentiveKind.INVITATION_COMMISSION, "1", "0", 0)), "-10")));
        Snapshot fixed = snapshot(configured(List.of(incentive("fixed", IncentiveKind.FIRST_N_SUBSIDY, "0.25", "0", 2)), "-10"), prior(0, null));
        assertEquals(d("0.25"), RuleEvaluation.calculate(fixed, d("2")).rewards().getFirst().amount());
    }
    @Test public void unknownQualificationNeverGloballyBlocksBaseAndIsDurablyReviewable() {
        Snapshot s = snapshot(configured(all(), "-10"), Evidence.unknown(AT));
        assertEquals("base", s.decision().proportionKey()); assertEquals(6, s.decision().manualReviewReasons().size());
        assertTrue(RuleEvaluation.calculate(s, d("2")).rewards().isEmpty());
        Snapshot knownCountsUnknownInviter = snapshot(configured(all(), "-10"), prior(0, null));
        assertEquals(List.of("count", "subsidy", "forced"), knownCountsUnknownInviter.decision().incentiveKeys());
    }
    @Test public void firstAndCountBoundariesExcludeRefundsViaHistoricalPortCounts() {
        RulePolicy p = configured(all(), "-10");
        assertEquals(List.of("ratio", "inviteN", "subsidy"), snapshot(p, prior(1, 9L)).decision().incentiveKeys());
        assertEquals(List.of("ratio"), snapshot(p, prior(3, 9L)).decision().incentiveKeys());
        Evidence self = new Evidence(AT, "historical:test-only", 0L, d("0"), 0L, 9L, true,
            AT.minusSeconds(1), null, null, null, null, null);
        assertTrue(snapshot(p, self).decision().incentiveKeys().isEmpty());
    }
    @Test public void activePeriodEndAndSignupWindowAreExclusiveAndMissingSignupIsNotFabricated() {
        Incentive expired = new Incentive("expired", IncentiveKind.ORDER_COUNT, d("1"), d("0"), 1, 0, null, AT);
        assertTrue(snapshot(configured(List.of(expired), "-10"), prior(0, 9L)).decision().incentiveKeys().isEmpty());
        Evidence boundary = new Evidence(AT, "history", 0L, d("0"), 0L, 9L, false,
            AT.minusSeconds(5 * 86400), null, null, null, null, null);
        assertTrue(snapshot(configured(all(), "-10"), boundary).decision().incentiveKeys().isEmpty());
        Evidence missingSignup = new Evidence(AT, "history", 0L, d("0"), 0L, 9L, false,
            null, null, null, null, null, null);
        assertEquals(6, snapshot(configured(all(), "-10"), missingSignup).decision().manualReviewReasons().size());
    }
    @Test public void forcedGuaranteeAtZeroIsIncludedAndDoesNotMultiplyReferralBase() {
        Snapshot s = snapshot(configured(all(), "-10"), prior(0, 9L));
        Calculation zero = RuleEvaluation.calculate(s, d("0"));
        assertEquals(d("0.00"), zero.rewards().getFirst().amount());
        assertEquals(d("1.00"), zero.rewards().getLast().amount());
        assertEquals(d("-1.73"), zero.margin());
        assertEquals(d("0.00"), RuleEvaluation.calculate(s, d("10")).rewards().getLast().amount());
    }
    @Test public void roundedProofBoundsEveryCentWithMinimumRebateJdFeesAndStacking() {
        var b = new Proportion("base", Kind.BASE, Mode.COMMISSION_TIERS, d("0.1"), any(), 0, 0, null,
            List.of(new Tier(0, d("0.03"), d("0.9"))));
        var p = new RulePolicy(com.ruoyi.union.adapter.Platform.JD, d("0.1"), d("0.2"), d("0.10"), d("-10"),
            d("0.08"), d("0.03"), false, List.of(b), all());
        var bound = RuleEvaluation.worstMargin(p);
        for (boolean operated : List.of(true, false)) {
            var e = new Evidence(AT, "history:test-only", 0L, d("0"), 0L, 9L, false,
                AT.minusSeconds(100000), null, null, null, null, operated);
            for (int estimated = 0; estimated <= 10; estimated++) {
                var f = new Facts(7, p.platform(), "sku", AT, java.math.BigDecimal.valueOf(estimated, 2), e);
                var s = new Snapshot(1, 7, p.platform(), "o", "s", "sku", AT, 1, p, e, RuleEvaluation.choose(p, f));
                for (int actual = 0; actual <= 10; actual++) {
                    var c = RuleEvaluation.calculate(s, java.math.BigDecimal.valueOf(actual, 2));
                    assertTrue(c.margin().compareTo(bound) >= 0);
                    assertTrue(c.rebate().compareTo(d("0.03")) >= 0);
                    assertEquals(RuleEvaluation.money(c.rebate().multiply(d("0.2"))), c.rewards().getFirst().amount());
                }
            }
        }
    }
    @Test public void proofWorkLimitIsExplicitAndCannotBeExpandedByClient() {
        RulePolicy p = configured(all(), "-10");
        RulePolicy excessive = new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), d("10000"),
            p.minimumMargin(), p.payoutCostRate(), p.minimumRebate(), false, p.proportions(), p.incentives());
        assertThrows(IllegalArgumentException.class, () -> RuleEvaluation.worstMargin(excessive));
    }
}
