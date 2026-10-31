package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.settlement.CumulativeRecoveryAllocator.*;

public class CumulativeRecoveryAllocatorTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static List<BigDecimal> amounts(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(d("0.00"), BigDecimal::add);
    }

    @Test public void growingCumulativeIncludesReferralAndSubsidyAndIgnoresStaleTargets() {
        var snapshots = amounts("9.00", "1.80", "0.60");
        var first = cumulativeTargets(d("20"), d("5"), snapshots);
        assertEquals(amounts("2.25", "0.45", "0.15"), first);
        var next = cumulativeTargets(d("20"), d("10"), snapshots);
        assertEquals(first, differences(next, first));
        assertEquals(amounts("0.00", "0.00", "0.00"), differences(next, next));
        assertEquals(amounts("0.00", "0.00", "0.00"), differences(first, next));
        assertEquals(snapshots, cumulativeTargets(d("20"), d("20"), snapshots));
        assertEquals(amounts("0.00", "0.00", "0.00"), cumulativeTargets(d("20"), d("0"), snapshots));
        // Weights need not sum to commission: subsidies may exceed it.
        assertEquals(amounts("2.00"), cumulativeTargets(d("1"), d("1"), amounts("2.00")));
    }

    @Test public void impossibleIndependentRoundingExplicitlyUsesMonotonePoolApportionment() {
        var snapshots = amounts("0.01", "0.01", "0.01");
        // Independent first-two HALF_UP plus tail would drop tail 0.01 -> 0.
        assertEquals(d("0.00"), d("0.01").multiply(d("0.49")).setScale(2, RoundingMode.HALF_UP));
        assertEquals(d("0.01"), d("0.01").multiply(d("0.50")).setScale(2, RoundingMode.HALF_UP));
        assertEquals(amounts("0.00", "0.01", "0.00"), cumulativeTargets(d("1"), d("0.49"), snapshots));
        assertEquals(amounts("0.01", "0.01", "0.00"), cumulativeTargets(d("1"), d("0.50"), snapshots));
        assertEquals(amounts("0.01", "0.00", "0.00"),
                cumulativeTargets(d("1"), d("0.50"), amounts("0.01", "0.01", "0.00")));
        assertEquals(amounts("0.00", "0.01"), cumulativeTargets(d("3"), d("1"), amounts("0.01", "0.02")));
    }

    @Test public void exhaustiveSmallCentWeightsConservePoolAndAreMonotoneAndBounded() {
        for (int a = 0; a <= 5; a++) {
            for (int b = 0; b <= 5; b++) {
                for (int c = 0; c <= 5; c++) {
                    var snapshots = List.of(BigDecimal.valueOf(a, 2), BigDecimal.valueOf(b, 2), BigDecimal.valueOf(c, 2));
                    var prior = amounts("0.00", "0.00", "0.00");
                    for (int recovery = 0; recovery <= 100; recovery++) {
                        var ratio = BigDecimal.valueOf(recovery, 2);
                        var targets = cumulativeTargets(d("1"), ratio, snapshots);
                        assertEquals(sum(snapshots).multiply(ratio).setScale(2, RoundingMode.HALF_UP), sum(targets));
                        for (int i = 0; i < 3; i++) {
                            assertTrue(targets.get(i).compareTo(prior.get(i)) >= 0);
                            assertTrue(targets.get(i).compareTo(snapshots.get(i)) <= 0);
                        }
                        prior = targets;
                    }
                    assertEquals(snapshots, prior);
                }
            }
        }
    }

    @Test public void highPrecisionCommissionAndLargeAmountsDoNotIteratePerCent() {
        var snapshots = amounts("999999999999.99", "123456789012.34", "0.01");
        assertEquals(snapshots, cumulativeTargets(d("0.0001"), d("0.0001"), snapshots));
        assertEquals(List.of(), cumulativeTargets(d("1"), d("0.5"), List.of()));
        assertEquals(amounts("0.00"), cumulativeTargets(d("0"), d("0"), amounts("2.00")));
    }

    @Test public void invalidInputsFailWithoutSilentlyClampingRecovery() {
        for (var recovery : amounts("-0.01", "1.01")) {
            assertThrows(IllegalArgumentException.class, () -> cumulativeTargets(d("1"), recovery, amounts("1.00")));
        }
        assertThrows(IllegalArgumentException.class, () -> cumulativeTargets(d("0"), d("0.01"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> cumulativeTargets(d("-1"), d("0"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> cumulativeTargets(d("1"), d("0"), amounts("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> cumulativeTargets(d("1"), d("0"), amounts("0.001")));
        assertThrows(NullPointerException.class, () -> cumulativeTargets(null, d("0"), List.of()));
        assertThrows(NullPointerException.class, () -> cumulativeTargets(d("1"), null, List.of()));
        assertThrows(NullPointerException.class, () -> cumulativeTargets(d("1"), d("0"), null));
        assertThrows(IllegalArgumentException.class, () -> differences(amounts("1"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> differences(amounts("-1"), amounts("0")));
        assertThrows(IllegalArgumentException.class, () -> differences(amounts("1"), amounts("-1")));
        assertThrows(IllegalArgumentException.class, () -> differences(amounts("1"), amounts("0.001")));
    }
}
