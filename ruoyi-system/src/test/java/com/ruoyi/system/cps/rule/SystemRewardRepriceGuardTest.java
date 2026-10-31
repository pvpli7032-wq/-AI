package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/** Documents the remaining production gap: adjustments MUST fail closed, not silently replay. */
public class SystemRewardRepriceGuardTest {
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private static Snapshot snapshot(Type type, Mode mode, String value) {
        Config config = new Config("policy-key", type, 1, mode, 1, d(value), Set.of("A"), T, T.plusSeconds(100));
        return new Snapshot(1, new OrderKey("A", "o", "s"), 20, null, T, false,
            d("0"), d("0"), d("100"), List.of(config));
    }
    private static Qualification q(String rebate) {
        return new Qualification(T, T, true, false, d("10"), d("10"), d(rebate));
    }
    @Test public void actualRatioFiveToOneAndOneToFiveCannotSilentlyReplay() {
        for (String[] values : List.of(new String[]{"5", "1"}, new String[]{"1", "5"})) {
            var store = new RewardServiceTest.Memory(); var svc = new RewardService(store);
            svc.capture(snapshot(Type.FIRST_N_SUBSIDY, Mode.RATIO, "1"));
            Result grant = svc.qualify(1, q(values[0]));
            assertEquals(grant, svc.reprice(1, q(values[0])));
            var before = new LinkedHashMap<>(store.instructions);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> svc.reprice(1, q(values[1])));
            assertEquals("reward repricing requires append-only basis reconciliation; manual review required", failure.getMessage());
            assertEquals(before, store.instructions); assertEquals(grant, new Result(false, svc.replay(1)));
            assertEquals(d(values[0]).setScale(2), store.awards.values().iterator().next().amount());
        }
    }
    @Test public void guaranteeIncreaseDecreaseAndZeroToPositiveAreBlocked() {
        for (String[] values : List.of(new String[]{"5", "1"}, new String[]{"1", "5"}, new String[]{"10", "1"})) {
            var store = new RewardServiceTest.Memory(); var svc = new RewardService(store);
            svc.capture(snapshot(Type.FORCED_FIRST, Mode.FIXED, "10")); svc.qualify(1, q(values[0]));
            var before = new LinkedHashMap<>(store.instructions);
            assertThrows(IllegalStateException.class, () -> svc.reprice(1, q(values[1])));
            assertEquals(before, store.instructions);
        }
    }
    @Test public void fixedRemainsUnchangedButQualificationLossCannotSilentlyReplay() {
        var store = new RewardServiceTest.Memory(); var svc = new RewardService(store);
        svc.capture(snapshot(Type.FIRST_N_SUBSIDY, Mode.FIXED, "5")); Result grant = svc.qualify(1, q("5"));
        assertEquals(grant, svc.reprice(1, q("1")));
        Qualification invalid = new Qualification(T, T, true, false, d("10"), d("0"), d("0"));
        assertThrows(IllegalStateException.class, () -> svc.reprice(1, invalid));
        assertEquals(grant, new Result(false, svc.replay(1)));
    }
}
