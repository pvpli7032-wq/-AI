package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;

/** Versioned on each application. Zero disables a threshold, never supplies a business default. */
public record WithdrawalPolicy(boolean enabled, String channel, ZoneId businessTimezone,
    BigDecimal minimum, BigDecimal maximum, long dailyCount, long recipientDailyCount,
    BigDecimal monthlyAmount, Set<Integer> monthDays, boolean integerOnly, boolean requireReceivedOrder,
    BigDecimal realNameSingle, BigDecimal realNameMonthly, IdentityAction identityAction,
    BigDecimal reviewAmount, long reviewFirstCount, FirstCountBasis firstCountBasis,
    BigDecimal channelSingle, BigDecimal channelDaily, BigDecimal channelUserDaily,
    Map<String, List<ReviewRule>> groups, String snapshot) {

    public enum Metric { AMOUNT, WITHDRAWAL_COUNT, DISPUTE_COUNT, SETTLED_COUNT, FIRST_COMMISSION_RATIO }
    public enum Comparison { GTE, LTE, GT, LT }
    public enum FirstCountBasis { APPLICATIONS, SUCCEEDED }
    public enum IdentityAction { MANUAL_REVIEW, REJECT }
    public record ReviewRule(Metric metric, Comparison comparison, BigDecimal threshold) {
        public boolean triggered(BigDecimal actual) {
            if (threshold.signum() == 0) return false;
            // Missing settled commission evidence cannot silently pass an enabled check.
            if (actual == null) return true;
            int result = actual.compareTo(threshold);
            return switch (comparison) {
                case GTE -> result >= 0; case LTE -> result <= 0;
                case GT -> result > 0; case LT -> result < 0;
            };
        }
    }

    public static WithdrawalPolicy parse(String value, String legacyMinimum) {
        try {
            JSONObject json = value == null ? null : JSON.parseObject(value);
            if (json == null || !Boolean.TRUE.equals(json.get("enabled"))) {
                throw new ServiceException("提现申请暂未开放");
            }
            String channel = requiredText(json, "channel");
            if (!channel.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("channel");
            ZoneId zone = ZoneId.of(requiredText(json, "businessTimezone"));
            BigDecimal minimum = decimal(legacyMinimum, "commission.min.withdraw");
            BigDecimal maximum = money(json, "maximum");
            if (maximum.signum() > 0 && maximum.compareTo(minimum) < 0) throw new IllegalArgumentException("maximum");
            Set<Integer> days = new HashSet<>();
            String allowedDays = requiredTextAllowEmpty(json, "monthDays");
            if (!allowedDays.isBlank() && !"0".equals(allowedDays)) {
                for (String day : allowedDays.split("\\|", -1)) {
                    int d = Integer.parseInt(day);
                    if (d < 1 || d > 31 || !days.add(d)) throw new IllegalArgumentException("monthDays");
                }
            }
            JSONObject groupJson = json.getJSONObject("groups");
            if (groupJson == null) throw new IllegalArgumentException("groups");
            Map<String, List<ReviewRule>> groups = new HashMap<>();
            for (String key : groupJson.keySet()) {
                if (!key.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("group");
                JSONArray rules = groupJson.getJSONArray(key);
                if (rules == null) throw new IllegalArgumentException("rules");
                List<ReviewRule> parsed = new ArrayList<>();
                Set<Metric> metrics = new HashSet<>();
                for (int i = 0; i < rules.size(); i++) {
                    JSONObject r = rules.getJSONObject(i);
                    Metric metric = Metric.valueOf(requiredText(r, "metric"));
                    if (!metrics.add(metric)) throw new IllegalArgumentException("duplicate metric");
                    parsed.add(new ReviewRule(metric, Comparison.valueOf(requiredText(r, "comparison")),
                        decimal(requiredText(r, "threshold"), "threshold")));
                }
                groups.put(key, List.copyOf(parsed));
            }
            // Store the inherited minimum too, so a later configuration edit cannot rewrite history.
            JSONObject snapshot = JSON.parseObject(json.toJSONString());
            snapshot.put("minimumAtApplication", minimum);
            long firstCount = count(json, "reviewFirstCount");
            FirstCountBasis firstBasis = firstCount == 0 ? null
                : FirstCountBasis.valueOf(requiredText(json, "firstCountBasis"));
            BigDecimal realNameSingle = money(json, "realNameSingle"), realNameMonthly = money(json, "realNameMonthly");
            IdentityAction identityAction = realNameSingle.signum() == 0 && realNameMonthly.signum() == 0 ? null
                : IdentityAction.valueOf(requiredText(json, "identityAction"));
            return new WithdrawalPolicy(true, channel, zone, minimum, maximum,
                count(json, "dailyCount"), count(json, "recipientDailyCount"), money(json, "monthlyAmount"),
                Set.copyOf(days), bool(json, "integerOnly"), bool(json, "requireReceivedOrder"),
                realNameSingle, realNameMonthly, identityAction, money(json, "reviewAmount"),
                firstCount, firstBasis, money(json, "channelSingle"), money(json, "channelDaily"),
                money(json, "channelUserDaily"), Map.copyOf(groups), snapshot.toJSONString());
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ServiceException("提现配置不完整或格式错误，请联系管理员");
        }
    }

    public static WithdrawalPolicy fromSnapshot(String snapshot) {
        JSONObject json = JSON.parseObject(snapshot);
        return parse(snapshot, json.getString("minimumAtApplication"));
    }

    public static BigDecimal requestedAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.precision() > 18 || value.scale() < -12 || value.scale() > 12)
            throw new ServiceException("提现金额必须是有效的正数");
        BigDecimal money = value.setScale(2, RoundingMode.HALF_UP);
        if (money.signum() <= 0 || money.compareTo(new BigDecimal("999999999999.99")) > 0)
            throw new ServiceException("提现金额超出有效范围");
        return money;
    }

    public List<String> reviewReasons(BigDecimal amount, long successfulCount, long priorRequests,
            long disputeCount, long settledCount, BigDecimal firstCommissionRatio, String group) {
        List<String> reasons = new ArrayList<>();
        if (hit(reviewAmount, amount)) reasons.add("DEFAULT_AMOUNT");
        long counted = firstCountBasis == FirstCountBasis.APPLICATIONS ? priorRequests : successfulCount;
        if (reviewFirstCount > 0 && counted < reviewFirstCount) reasons.add("FIRST_WITHDRAWALS");
        if (group != null && !groups.containsKey(group)) reasons.add("GROUP_POLICY_MISSING");
        List<ReviewRule> rules = groups.getOrDefault(group == null ? "default" : group, List.of());
        for (ReviewRule rule : rules) {
            BigDecimal actual = switch (rule.metric()) {
                case AMOUNT -> amount;
                case WITHDRAWAL_COUNT -> BigDecimal.valueOf(priorRequests + 1);
                case DISPUTE_COUNT -> BigDecimal.valueOf(disputeCount);
                case SETTLED_COUNT -> BigDecimal.valueOf(settledCount);
                case FIRST_COMMISSION_RATIO -> firstCommissionRatio;
            };
            if (rule.triggered(actual)) reasons.add("GROUP_" + rule.metric());
        }
        return reasons;
    }

    public static boolean hit(BigDecimal threshold, BigDecimal value) {
        return threshold.signum() > 0 && value.compareTo(threshold) >= 0;
    }

    private static BigDecimal money(JSONObject j, String key) {
        return decimal(requiredText(j, key), key).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal decimal(String value, String key) {
        if (value == null || value.isBlank() || value.length() > 40) throw new IllegalArgumentException(key);
        BigDecimal n = new BigDecimal(value);
        if (n.signum() < 0 || n.compareTo(new BigDecimal("999999999999.99")) > 0 || n.scale() > 12)
            throw new IllegalArgumentException(key);
        return n;
    }

    private static long count(JSONObject j, String key) {
        return decimal(requiredText(j, key), key).longValueExact();
    }

    private static boolean bool(JSONObject j, String key) {
        Object value = j.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key);
        return (Boolean) value;
    }

    private static String requiredText(JSONObject j, String key) {
        String value = requiredTextAllowEmpty(j, key);
        if (value.isBlank()) throw new IllegalArgumentException(key);
        return value;
    }

    private static String requiredTextAllowEmpty(JSONObject j, String key) {
        if (!j.containsKey(key) || j.get(key) == null) throw new IllegalArgumentException(key);
        return j.getString(key);
    }
}
