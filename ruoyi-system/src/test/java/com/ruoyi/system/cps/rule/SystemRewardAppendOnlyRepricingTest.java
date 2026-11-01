package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/** Behavioral regressions only. SQL rollback/locking is covered separately by the real MySQL suite. */
public class SystemRewardAppendOnlyRepricingTest {
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    static BigDecimal d(String v) { return new BigDecimal(v); }
    static Qualification q(String rebate) { return q(rebate, "10"); }
    static Qualification q(String rebate, String commission) {
        return new Qualification(T, T, true, false, d("100"), d(commission), d(rebate));
    }
    static Snapshot snapshot(long id, Config... configs) {
        return new Snapshot(id, new OrderKey("A", "o" + id, "s"), 20, 10L, T, false,
            d("0"), d("0"), d("100"), List.of(configs));
    }
    static Config config(Type type, Mode mode, int n, String value) {
        return new Config(type, 1, mode, n, d(value), Set.of("A"), T, T.plusSeconds(100));
    }
    static class Store implements RewardRepricingStore {
        final Map<String, Basis> current = new HashMap<>();
        final Map<String, Basis> revisions = new LinkedHashMap<>();
        public Basis basis(String id) { return current.get(id); }
        public void revise(Basis previous, Basis next, BigDecimal grant, String evidence) {
            assertNull(revisions.putIfAbsent(next.revisionKey(), next)); current.put(next.awardId(), next);
        }
        public void recover(String id, BigDecimal amount) {
            Basis b = basis(id); current.put(id, new Basis(id, b.entitlement(), amount, b.revisionKey()));
        }
    }
    private static BigDecimal net(Result r) {
        return r.instructions().stream().map(i -> i.recovery() ? i.amount().negate() : i.amount())
            .reduce(d("0.00"), BigDecimal::add);
    }
    @Test public void downUpReplayAndRepeatedCyclesKeepOriginalGrant() {
        var m = new RewardServiceTest.Memory(); var store = new Store(); var service = new RewardService(m, store);
        service.capture(snapshot(1, config(Type.FIRST_N_SUBSIDY, Mode.RATIO, 1, "1")));
        service.qualify(1, q("5"));
        Result down = service.reprice(1, q("1"));
        assertEquals(d("1.00"), net(down)); assertEquals(d("4.00"), down.instructions().getLast().amount());
        assertTrue(down.instructions().getLast().recovery());
        assertEquals(down, service.reprice(1, q("1"))); assertEquals(1, store.revisions.size());
        assertEquals(d("5.00"), m.awards.values().iterator().next().amount());
        assertEquals(d("5.00"), net(service.reprice(1, q("5"))));
        assertEquals(d("1.00"), net(service.reprice(1, q("1"))));
        assertEquals(3, store.revisions.size()); // repeated cycle must not collide with the earlier down
        assertEquals(4, m.instructions.size());
    }
    @Test public void upCreditsDeltaAndStableIdentityDoesNotDependOnNow() {
        var m = new RewardServiceTest.Memory(); var store = new Store(); var service = new RewardService(m, store);
        service.capture(snapshot(1, config(Type.INVITATION_RATIO, Mode.RATIO, 1, "1")));
        service.qualify(1, q("1")); Result up = service.reprice(1, q("5"));
        assertEquals(d("4.00"), up.instructions().getLast().amount()); assertFalse(up.instructions().getLast().recovery());
        assertEquals(10, up.instructions().getLast().userId());
        Qualification later = new Qualification(T.plusSeconds(1), T, true, false, d("100.0"), d("10.00"), d("5"));
        assertEquals(up, service.reprice(1, later));
    }
    @Test public void forcedTopupCanStartAtZeroAndIsNotAnExtraFixedBonus() {
        var m = new RewardServiceTest.Memory(); var service = new RewardService(m, new Store());
        service.capture(snapshot(1, config(Type.FORCED_FIRST, Mode.FIXED, 1, "10")));
        assertTrue(service.qualify(1, q("10")).instructions().isEmpty());
        assertEquals(d("9.00"), net(service.reprice(1, q("1"))));
        assertEquals(d("0.00"), m.awards.values().iterator().next().amount());
        assertEquals(d("5.00"), net(service.reprice(1, q("5"))));
        assertEquals(d("0.00"), net(service.reprice(1, q("11"))));
    }
    @Test public void refundAfterDownAndUpUsesRevisedBasisWithoutRechargingTheReprice() {
        for (String[] amounts : List.of(new String[]{"5", "1"}, new String[]{"1", "5"})) {
            var m = new RewardServiceTest.Memory(); var store = new Store(); var service = new RewardService(m, store);
            service.capture(snapshot(1, config(Type.FIRST_N_SUBSIDY, Mode.RATIO, 1, "1")));
            service.qualify(1, q(amounts[0], "3")); service.reprice(1, q(amounts[1], "3"));
            Refund half = Refund.fromCommissionRecovery(d("1"), d("3"), d("50"), d("2"));
            Result partial = service.refund(1, half);
            BigDecimal loss = d(amounts[1]).divide(d("3"), 2, java.math.RoundingMode.HALF_UP);
            assertEquals(loss, partial.instructions().getLast().amount());
            assertEquals(partial, service.refund(1, half));
            Result full = service.refund(1, Refund.fromCommissionRecovery(d("3"), d("3"), d("0"), d("0")));
            assertEquals(d("0.00"), net(full));
            assertEquals(d(amounts[0]).setScale(2), m.awards.values().iterator().next().amount());
        }
    }
    @Test public void qualificationLossReversesInviteeAndCrossOrderQuantityWithoutRecyclingSlots() {
        var m = new RewardServiceTest.Memory(); var service = new RewardService(m, new Store());
        service.capture(snapshot(1, config(Type.INVITEE_FIRST_FIXED, Mode.FIXED, 1, "3")));
        service.qualify(1, q("5"));
        service.capture(snapshot(2, config(Type.OWN_QUANTITY, Mode.FIXED, 2, "7")));
        service.qualify(2, q("5"));
        Result invalid = service.reprice(1, q("0", "0"));
        assertEquals(3, invalid.instructions().size());
        assertEquals(2, invalid.instructions().getLast().awardOrderId());
        assertEquals(1, m.validCount(20)); assertEquals(2, m.lifetimeCount(20));
        assertEquals(invalid, service.reprice(1, q("0", "0")));
        assertThrows(IllegalStateException.class, () -> service.reprice(1, q("5")));
    }
    @Test public void revisedLargeBasisUsesExactOriginalCommissionAndStableFinancialEvidenceKeys() {
        List<String> keys = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            var m = new RewardServiceTest.Memory(); var service = new RewardService(m, new Store());
            Snapshot small = snapshot(1, config(Type.FIRST_N_SUBSIDY, Mode.RATIO, 1, "1"));
            service.capture(new Snapshot(small.orderId(), small.order(), small.buyerId(), small.inviterId(),
                small.paidAt(), false, d("0"), d("0"), d("9999999999999999.99"), small.configs()));
            service.qualify(1, q("1"));
            Qualification actual = new Qualification(T.plusSeconds(run), T, true, false,
                d("100"), d("3"), d("9999999999999999.98"));
            Result up = service.reprice(1, actual); keys.add(up.instructions().getLast().businessKey());
            Result refund = service.refund(1, Refund.fromCommissionRecovery(d("1"), d("3"), d("50"), d("2")));
            assertEquals(d("3333333333333333.33"), refund.instructions().getLast().amount());
        }
        assertEquals(keys.getFirst(), keys.getLast());
    }
    @Test public void lostQuantityThresholdDoesNotRegrantWhenLaterOrdersRestoreCount() {
        var m = new RewardServiceTest.Memory(); var service = new RewardService(m, new Store());
        service.capture(snapshot(1)); service.qualify(1, q("5"));
        service.capture(snapshot(2, config(Type.OWN_QUANTITY, Mode.FIXED, 2, "7")));
        service.qualify(2, q("5")); service.reprice(1, q("0", "0"));
        service.capture(snapshot(3)); service.qualify(3, q("5"));
        assertEquals(1, service.reprice(2, q("7")).instructions().size()); // original grant only
        assertEquals(3, m.lifetimeCount(20)); // invalidated first order still owns its lifetime slot
    }
    @Test public void fixedUnchangedAndAmbiguousHistoricalRefundRequiresManualReview() {
        var m = new RewardServiceTest.Memory(); var service = new RewardService(m, new Store());
        service.capture(snapshot(1, config(Type.FIRST_N_SUBSIDY, Mode.FIXED, 1, "5")));
        Result grant = service.qualify(1, q("5")); assertEquals(grant, service.reprice(1, q("1")));
        service.refund(1, new Refund(d(".1"), d("90"), d("9")));
        assertThrows(IllegalStateException.class, () -> service.reprice(1, q("2")));
        var historical = new RewardServiceTest.Memory(); var old = new RewardService(historical, new Store());
        old.capture(snapshot(1, config(Type.FIRST_N_SUBSIDY, Mode.RATIO, 1, "1")));
        old.refund(1, new Refund(d(".1"), d("90"), d("9"))); old.qualify(1, q("5"));
        assertThrows(IllegalStateException.class, () -> old.reprice(1, q("1")));
    }
}
