package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.withdraw.WithdrawalPolicy.Comparison;
import com.ruoyi.system.cps.withdraw.WithdrawalPolicy.Metric;

/** Synthetic values only. Exercises operator configuration and OR-based review decisions. */
public class WithdrawalPolicyTest {
    private static final List<String> MONEY_KEYS = List.of("maximum", "monthlyAmount", "realNameSingle", "realNameMonthly",
        "reviewAmount", "channelSingle", "channelDaily", "channelUserDaily");
    private static final List<String> COUNT_KEYS = List.of("dailyCount", "recipientDailyCount", "reviewFirstCount");

    private JSONObject base() {
        JSONObject config = new JSONObject();
        config.put("enabled", true);
        config.put("channel", "mock");
        config.put("businessTimezone", "Asia/Shanghai");
        for (String key : MONEY_KEYS) config.put(key, "0");
        for (String key : COUNT_KEYS) config.put(key, "0");
        config.put("monthDays", "0");
        config.put("integerOnly", false);
        config.put("requireReceivedOrder", false);
        config.put("groups", new JSONObject());
        return config;
    }

    private WithdrawalPolicy parse(JSONObject config) { return WithdrawalPolicy.parse(config.toJSONString(), "0"); }

    private JSONObject rule(Metric metric, Comparison comparison, String threshold) {
        JSONObject rule = new JSONObject();
        rule.put("metric", metric.name());
        rule.put("comparison", comparison.name());
        rule.put("threshold", threshold);
        return rule;
    }

    private WithdrawalPolicy grouped(Metric metric, Comparison comparison, String threshold) {
        JSONObject config = base();
        JSONArray rules = new JSONArray();
        rules.add(rule(metric, comparison, threshold));
        config.getJSONObject("groups").put("fixture", rules);
        return parse(config);
    }

    private List<String> reasons(WithdrawalPolicy policy, String amount, long successful, long requests,
                                 long disputes, long settled, String ratio, String group) {
        return policy.reviewReasons(new BigDecimal(amount), successful, requests, disputes, settled,
            ratio == null ? null : new BigDecimal(ratio), group);
    }

    @Test
    public void requestedAmountsUseHalfUpToCentsAndCannotRoundToZero() {
        assertEquals(new BigDecimal("1.01"), WithdrawalPolicy.requestedAmount(new BigDecimal("1.005")));
        assertEquals(new BigDecimal("1.00"), WithdrawalPolicy.requestedAmount(new BigDecimal("1.004")));
        assertEquals(new BigDecimal("0.01"), WithdrawalPolicy.requestedAmount(new BigDecimal("0.005")));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.requestedAmount(new BigDecimal("0.004")));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.requestedAmount(BigDecimal.ZERO));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.requestedAmount(new BigDecimal("-0.01")));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.requestedAmount(null));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.requestedAmount(new BigDecimal("1000000000000")));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.requestedAmount(new BigDecimal("1E+999999")));
    }

    @Test
    public void explicitZeroDisablesEveryReviewThresholdIncludingMissingRatio() {
        JSONObject config = base();
        JSONArray rules = new JSONArray();
        for (Metric metric : Metric.values()) rules.add(rule(metric, Comparison.LTE, "0"));
        config.getJSONObject("groups").put("default", rules);
        WithdrawalPolicy policy = parse(config);
        assertTrue(reasons(policy, "999.00", 0, 99, 99, 0, null, null).isEmpty());
        assertEquals(0, policy.minimum().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.00"), policy.channelDaily());
        assertEquals(0, policy.dailyCount());
        assertEquals(0, policy.recipientDailyCount());
    }

    @Test
    public void defaultAmountAloneTriggersAtItsBoundary() {
        JSONObject config = base();
        config.put("reviewAmount", "9.00");
        WithdrawalPolicy policy = parse(config);
        assertEquals(List.of("DEFAULT_AMOUNT"), reasons(policy, "9.00", 99, 99, 0, 99, "1", null));
        assertTrue(reasons(policy, "8.99", 99, 99, 0, 99, "1", null).isEmpty());
    }

    @Test
    public void firstNAloneUsesExplicitlyConfiguredSuccessfulPayoutCount() {
        JSONObject config = base();
        config.put("reviewFirstCount", "3");
        config.put("firstCountBasis", "SUCCEEDED");
        WithdrawalPolicy policy = parse(config);
        assertEquals(List.of("FIRST_WITHDRAWALS"), reasons(policy, "1", 2, 50, 0, 99, "1", null));
        assertTrue(reasons(policy, "1", 3, 50, 0, 99, "1", null).isEmpty());
    }

    @Test
    public void anySingleGroupMetricCanIndependentlyRequireReview() {
        for (Metric metric : Metric.values()) {
            WithdrawalPolicy policy = grouped(metric, Comparison.GTE, "2");
            assertEquals(metric.name(), List.of("GROUP_" + metric), reasons(policy, "2", 99, 1, 2, 2, "2", "fixture"));
            assertTrue(metric.name(), reasons(policy, "1", 99, 0, 1, 1, "1", "fixture").isEmpty());
        }
    }

    @Test
    public void multipleRulesUseAnyTriggerRatherThanRequiringAll() {
        JSONObject config = base();
        config.put("reviewAmount", "90");
        config.put("reviewFirstCount", "1");
        config.put("firstCountBasis", "APPLICATIONS");
        JSONArray rules = new JSONArray();
        for (Metric metric : Metric.values()) rules.add(rule(metric, Comparison.GTE, "90"));
        config.getJSONObject("groups").put("fixture", rules);
        WithdrawalPolicy policy = parse(config);
        assertEquals(List.of("GROUP_DISPUTE_COUNT"), reasons(policy, "1", 10, 1, 90, 1, "1", "fixture"));
    }

    @Test
    public void allFourComparisonDirectionsPreserveStrictAndInclusiveBoundaries() {
        for (Comparison comparison : Comparison.values()) {
            WithdrawalPolicy policy = grouped(Metric.AMOUNT, comparison, "9");
            boolean lower = !reasons(policy, "8", 99, 0, 0, 0, "0", "fixture").isEmpty();
            boolean equal = !reasons(policy, "9", 99, 0, 0, 0, "0", "fixture").isEmpty();
            boolean higher = !reasons(policy, "10", 99, 0, 0, 0, "0", "fixture").isEmpty();
            assertEquals(comparison.name(), comparison == Comparison.LT || comparison == Comparison.LTE, lower);
            assertEquals(comparison.name(), comparison == Comparison.LTE || comparison == Comparison.GTE, equal);
            assertEquals(comparison.name(), comparison == Comparison.GT || comparison == Comparison.GTE, higher);
        }
    }

    @Test
    public void enabledRatioWithoutEvidenceAndUnconfiguredAssignedGroupRequireReview() {
        WithdrawalPolicy policy = grouped(Metric.FIRST_COMMISSION_RATIO, Comparison.LTE, "2");
        assertEquals(List.of("GROUP_FIRST_COMMISSION_RATIO"), reasons(policy, "1", 99, 0, 0, 0, null, "fixture"));
        assertEquals(List.of("GROUP_POLICY_MISSING"), reasons(policy, "1", 99, 0, 0, 0, "1", "missing"));
    }

    @Test
    public void inheritedMinimumAndApplicationSnapshotRemainStableAfterConfigurationChange() {
        JSONObject config = base();
        config.put("reviewAmount", "9.00");
        WithdrawalPolicy original = WithdrawalPolicy.parse(config.toJSONString(), "7.25");
        config.put("reviewAmount", "99.00");
        WithdrawalPolicy changed = WithdrawalPolicy.parse(config.toJSONString(), "8.25");
        WithdrawalPolicy replay = WithdrawalPolicy.fromSnapshot(original.snapshot());
        assertEquals(new BigDecimal("7.25"), replay.minimum());
        assertEquals(new BigDecimal("8.25"), changed.minimum());
        assertEquals(List.of("DEFAULT_AMOUNT"), reasons(replay, "9.00", 99, 0, 0, 0, null, null));
        assertTrue(reasons(changed, "9.00", 99, 0, 0, 0, null, null).isEmpty());
    }

    @Test
    public void incompleteConfigurationDoesNotInventDefaults() {
        JSONObject complete = base();
        for (String key : complete.keySet()) {
            JSONObject missing = base();
            missing.remove(key);
            assertThrows("missing " + key, ServiceException.class, () -> parse(missing));
        }
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.parse(null, "0"));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.parse("{}", "0"));
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.parse(base().toJSONString(), null));
    }

    @Test
    public void negativeLimitsFractionalCountsAndSubCentMoneyAreRejected() {
        for (String key : MONEY_KEYS) {
            JSONObject negative = base(); negative.put(key, "-0.01");
            negative.put("identityAction", "MANUAL_REVIEW");
            assertThrows(key, ServiceException.class, () -> parse(negative));
            JSONObject subCent = base(); subCent.put(key, "0.004");
            subCent.put("identityAction", "MANUAL_REVIEW");
            assertThrows(key, ServiceException.class, () -> parse(subCent));
        }
        for (String key : COUNT_KEYS) {
            JSONObject negative = base(); negative.put(key, "-1");
            negative.put("firstCountBasis", "APPLICATIONS");
            assertThrows(key, ServiceException.class, () -> parse(negative));
            JSONObject fractional = base(); fractional.put(key, "1.5");
            fractional.put("firstCountBasis", "APPLICATIONS");
            assertThrows(key, ServiceException.class, () -> parse(fractional));
        }
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.parse(base().toJSONString(), "-1"));
        JSONObject conflicting = base(); conflicting.put("maximum", "5.00");
        assertThrows(ServiceException.class, () -> WithdrawalPolicy.parse(conflicting.toJSONString(), "6.00"));
    }

    @Test
    public void enabledAndBooleanControlsRequireActualBooleans() {
        for (String key : List.of("enabled", "integerOnly", "requireReceivedOrder")) {
            for (Object invalid : List.of("true", 1, "yes")) {
                JSONObject config = base(); config.put(key, invalid);
                assertThrows(key, ServiceException.class, () -> parse(config));
            }
        }
        JSONObject disabled = base(); disabled.put("enabled", false);
        assertThrows(ServiceException.class, () -> parse(disabled));
    }

    @Test
    public void datesAndGroupRulesAreValidatedWithoutImplicitFallback() {
        JSONObject config = base(); config.put("monthDays", "10|20");
        assertEquals(Set.of(10, 20), parse(config).monthDays());
        for (String invalid : List.of("0|10", "32", "10|10", "10|", "-1")) {
            JSONObject dates = base(); dates.put("monthDays", invalid);
            assertThrows(invalid, ServiceException.class, () -> parse(dates));
        }
        JSONObject duplicate = base();
        JSONArray rules = new JSONArray();
        rules.add(rule(Metric.AMOUNT, Comparison.GTE, "2"));
        rules.add(rule(Metric.AMOUNT, Comparison.LTE, "1"));
        duplicate.getJSONObject("groups").put("fixture", rules);
        assertThrows(ServiceException.class, () -> parse(duplicate));
        JSONObject unknown = base();
        JSONObject malformed = rule(Metric.AMOUNT, Comparison.GTE, "2");
        malformed.put("metric", "UNRECOGNIZED_METRIC");
        JSONArray unknownRules = new JSONArray(); unknownRules.add(malformed);
        unknown.getJSONObject("groups").put("fixture", unknownRules);
        assertThrows(ServiceException.class, () -> parse(unknown));
    }

    @Test
    public void enabledFirstNRequiresAnExplicitCountingBasis() {
        JSONObject config = base(); config.put("reviewFirstCount", "3");
        assertThrows(ServiceException.class, () -> parse(config));
        config.put("firstCountBasis", "APPLICATIONS");
        assertTrue(reasons(parse(config), "1", 0, 3, 0, 0, null, null).isEmpty());
        config.put("firstCountBasis", "SUCCEEDED");
        assertEquals(List.of("FIRST_WITHDRAWALS"), reasons(parse(config), "1", 0, 3, 0, 0, null, null));
        config.put("firstCountBasis", "UNDEFINED");
        assertThrows(ServiceException.class, () -> parse(config));
    }

    @Test
    public void enabledIdentityThresholdRequiresAnExplicitAction() {
        for (String key : List.of("realNameSingle", "realNameMonthly")) {
            JSONObject config = base(); config.put(key, "9.00");
            assertThrows(ServiceException.class, () -> parse(config));
            for (String action : List.of("MANUAL_REVIEW", "REJECT")) {
                config.put("identityAction", action);
                assertNotNull(parse(config));
            }
            config.put("identityAction", "AUTOMATICALLY_IGNORE");
            assertThrows(ServiceException.class, () -> parse(config));
        }
    }
}
