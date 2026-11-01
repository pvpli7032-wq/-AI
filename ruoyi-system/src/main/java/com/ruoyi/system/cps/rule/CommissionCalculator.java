package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Shared money calculations. Rates are fractions in [0,1], never percentages. */
public final class CommissionCalculator {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private CommissionCalculator() { }

    public record Quote(BigDecimal netCommission, BigDecimal rebate, BigDecimal referral,
                        BigDecimal subsidy, BigDecimal payoutCost, BigDecimal margin) { }

    /** actualCommissionLimit is configured per order/rule; there is no implicit default. */
    public static Quote calculate(BigDecimal commission, BigDecimal actualCommissionLimit,
            BigDecimal serviceRate, BigDecimal jdAdjustmentRate, BigDecimal userRate,
            BigDecimal referralRate, BigDecimal subsidy, BigDecimal payoutCostRate) {
        nonNegative(commission, "commission");
        nonNegative(actualCommissionLimit, "actualCommissionLimit");
        if (commission.compareTo(actualCommissionLimit) > 0) {
            throw new IllegalArgumentException("commission exceeds configured limit");
        }
        rate(serviceRate, "serviceRate");
        rate(jdAdjustmentRate, "jdAdjustmentRate");
        rate(userRate, "userRate");
        rate(referralRate, "referralRate");
        rate(payoutCostRate, "payoutCostRate");
        nonNegative(subsidy, "subsidy");
        BigDecimal net = money(commission.multiply(ONE.subtract(serviceRate)).multiply(ONE.subtract(jdAdjustmentRate)));
        BigDecimal rebate = money(net.multiply(userRate));
        BigDecimal referral = money(rebate.multiply(referralRate));
        BigDecimal reward = money(subsidy);
        BigDecimal cost = money(rebate.add(referral).add(reward).multiply(payoutCostRate));
        return new Quote(net, rebate, referral, reward, cost,
                money(net.subtract(rebate).subtract(referral).subtract(reward).subtract(cost)));
    }

    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static void nonNegative(BigDecimal value, String name) {
        if (Objects.requireNonNull(value, name).signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
    }
    private static void rate(BigDecimal value, String name) {
        nonNegative(value, name);
        if (value.compareTo(ONE) > 0) throw new IllegalArgumentException(name + " must not exceed one");
    }
}
