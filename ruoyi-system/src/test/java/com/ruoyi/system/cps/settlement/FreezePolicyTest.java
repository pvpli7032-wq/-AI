package com.ruoyi.system.cps.settlement;

import com.ruoyi.union.adapter.Platform;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class FreezePolicyTest {
    private static final Instant PAID = Instant.parse("2026-01-01T00:00:00Z");
    private static FreezePolicy.Tier tier(String amount, long days) {
        return new FreezePolicy.Tier(new BigDecimal(amount), Duration.ofDays(days));
    }
    private static FreezePolicy policy() {
        return new FreezePolicy(Map.of(
                Platform.TBK, List.of(tier("0", 2), tier("10", 4)),
                Platform.JD, List.of(tier("0", 6))),
                Map.of(Platform.TBK, Duration.ofDays(1), Platform.JD, Duration.ofDays(3)));
    }
    private static FreezePolicy.Decision evaluate(FreezePolicy policy, Platform platform, String amount,
            Instant paid, Instant received, Instant settled, boolean disabled) {
        return policy.evaluate(platform, new BigDecimal(amount), paid, received, settled, disabled);
    }

    @Test public void platformTiersAreLowerInclusiveAndFastReceiptIsInclusive24Hours() {
        var p = policy();
        Instant slow = PAID.plus(Duration.ofHours(25));
        assertEquals(Duration.ofDays(2), evaluate(p, Platform.TBK, "9.99", PAID, slow, null, false).duration());
        assertEquals(Duration.ofDays(4), evaluate(p, Platform.TBK, "10", PAID, slow, null, false).duration());
        assertEquals(Duration.ofDays(6), evaluate(p, Platform.JD, "10", PAID, slow, null, false).duration());
        var fast = evaluate(p, Platform.TBK, "100", PAID, PAID.plus(Duration.ofHours(24)), null, false);
        assertTrue(fast.fastReceipt());
        assertEquals(Duration.ofDays(1), fast.duration());
        assertFalse(evaluate(p, Platform.TBK, "100", PAID,
                PAID.plus(Duration.ofHours(24)).plusNanos(1), null, false).fastReceipt());
        assertEquals(Duration.ofDays(3), evaluate(p, Platform.JD, "0", PAID, PAID, null, false).duration());
    }

    @Test public void userDisabledOverridesDurationButNotSuspicionWhichIsStrictlyUnder24Hours() {
        var p = policy();
        Instant receipt = PAID.plusSeconds(1);
        var disabled = evaluate(p, Platform.TBK, "100", PAID, receipt, receipt, true);
        assertEquals(Duration.ZERO, disabled.duration());
        assertTrue(disabled.suspiciousSettlement());
        assertFalse(evaluate(p, Platform.TBK, "0", PAID, receipt,
                PAID.plus(Duration.ofHours(24)), false).suspiciousSettlement());
        assertTrue(evaluate(p, Platform.TBK, "0", PAID, receipt,
                PAID.plus(Duration.ofHours(24)).minusNanos(1), false).suspiciousSettlement());
        assertEquals(Duration.ZERO, evaluate(p, Platform.TBK, "100", PAID,
                PAID.plus(Duration.ofDays(2)), null, true).duration());
    }

    @Test public void missingTimesNeverUseWallClockAndInvalidChronologyFails() {
        var missing = evaluate(policy(), Platform.TBK, "10", null, PAID, null, false);
        assertEquals(Duration.ofDays(4), missing.duration());
        assertFalse(missing.fastReceipt());
        assertFalse(missing.suspiciousSettlement());
        assertThrows(NullPointerException.class, () -> evaluate(policy(), Platform.TBK, "1", PAID, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> evaluate(policy(), Platform.TBK, "1", PAID, PAID.minusNanos(1), null, false));
        assertThrows(IllegalArgumentException.class, () -> evaluate(policy(), Platform.TBK, "1", PAID, PAID, PAID.minusSeconds(1), false));
        assertThrows(IllegalArgumentException.class, () -> evaluate(policy(), Platform.TBK, "1", null, PAID, PAID.minusSeconds(1), false));
        assertThrows(IllegalArgumentException.class, () -> evaluate(policy(), Platform.PDD, "1", PAID, PAID, null, true));
        assertThrows(IllegalArgumentException.class, () -> evaluate(policy(), Platform.TBK, "-1", PAID, PAID, null, false));
    }

    @Test public void configurationIsValidatedAndDefensivelyCopiedWithSyntheticDefaultsOnly() {
        var list = new ArrayList<>(List.of(tier("0", 2)));
        var tiers = new EnumMap<Platform, List<FreezePolicy.Tier>>(Platform.class);
        tiers.put(Platform.TBK, list);
        var fast = new EnumMap<Platform, Duration>(Platform.class);
        fast.put(Platform.TBK, Duration.ZERO);
        var p = new FreezePolicy(tiers, fast);
        list.clear(); tiers.clear(); fast.clear();
        assertEquals(Duration.ofDays(2), evaluate(p, Platform.TBK, "0", null, PAID, null, false).duration());
        for (var invalid : List.of(List.<FreezePolicy.Tier>of(), List.of(tier("1", 2)),
                List.of(tier("0", 2), tier("0", 3)), List.of(tier("0", 2), tier("10", 2), tier("5", 3)))) {
            assertThrows(IllegalArgumentException.class, () -> new FreezePolicy(Map.of(Platform.TBK, invalid), Map.of(Platform.TBK, Duration.ZERO)));
        }
        assertThrows(IllegalArgumentException.class, () -> tier("-1", 2));
        assertThrows(IllegalArgumentException.class, () -> tier("0", -1));
        assertThrows(IllegalArgumentException.class, () -> new FreezePolicy(Map.of(Platform.TBK, List.of(tier("0", 1))), Map.of()));
        for (Platform platform : Platform.values()) {
            assertEquals(Duration.ofDays(3), evaluate(FreezePolicy.syntheticDefaults(), platform, "100", null, PAID, null, false).duration());
        }
    }
}
