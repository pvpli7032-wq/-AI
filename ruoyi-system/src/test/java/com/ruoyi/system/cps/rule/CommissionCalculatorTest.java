package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CommissionCalculatorTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    @Test public void exampleAndRounding() {
        var q = CommissionCalculator.calculate(d("20"), d("20"), d("0.10"), d("0"), d("0.50"), d("0.20"), d("0"), d("0.08"));
        assertEquals(d("18.00"), q.netCommission());
        assertEquals(d("9.00"), q.rebate());
        assertEquals(d("1.80"), q.referral());
        assertEquals(d("0.86"), q.payoutCost());
        assertEquals(d("6.34"), q.margin());
    }
    @Test public void configuredLimitAndRoundedMarginAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> CommissionCalculator.calculate(d("20.01"), d("20"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0")));
        var q = CommissionCalculator.calculate(d("0.01"), d("1"), d("0"), d("0"), d("0.5"), d("0.5"), d("0"), d("0"));
        assertEquals(d("-0.01"), q.margin());
        assertThrows(IllegalArgumentException.class, () -> CommissionCalculator.requireMinimumMargin(q, d("0.00")));
    }
    @Test public void zeroRateIsAValidHigherPriorityRule() {
        var base = new ProportionSelector.Candidate(1, ProportionSelector.Priority.BASE, d("0.5"));
        var target = new ProportionSelector.Candidate(2, ProportionSelector.Priority.TARGETED, d("0"));
        assertEquals(target, ProportionSelector.select(List.of(base, target)));
        assertThrows(IllegalArgumentException.class, () -> ProportionSelector.select(List.of(target, target)));
    }
}
