package com.ruoyi.system.cps.rule;

import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/** In-memory behavioral repros only; no database, ledger, isolation, or transaction evidence. */
public class SystemRewardKeyAndExactRecoveryTest {
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private static Config c(String key, Type type, long version, String value) {
        return new Config(key, type, version, type == Type.INVITATION_RATIO ? Mode.RATIO : Mode.FIXED,
            1, d(value), Set.of("A"), T.minusSeconds(1), T.plusSeconds(100));
    }
    private static Snapshot s(long id, Config... configs) {
        return new Snapshot(id, new OrderKey("A", "order" + id, "child"), 20, 10L, T.plusSeconds(id),
            false, d("0"), d("0"), d("100"), List.of(configs));
    }
    private static Qualification q() {
        return new Qualification(T.plusSeconds(100), T, true, false, d("10"), d("6"), d("8"));
    }
    private static BigDecimal total(Result r, boolean recovery) {
        return r.instructions().stream().filter(i -> i.recovery() == recovery).map(Instruction::amount)
            .reduce(d("0.00"), BigDecimal::add);
    }
    @Test public void everyKindAllowsIndependentKeysWithReplayAndRefund() {
        var store = new RewardServiceTest.Memory(); var svc = new RewardService(store);
        List<Config> configs = List.of(c("ratio1", Type.INVITATION_RATIO, 1, ".25"), c("ratio2", Type.INVITATION_RATIO, 1, ".5"),
            c("first1", Type.INVITEE_FIRST_FIXED, 1, "3"), c("first2", Type.INVITEE_FIRST_FIXED, 1, "4"),
            c("early1", Type.INVITEE_FIRST_N_FIXED, 1, "5"), c("early2", Type.INVITEE_FIRST_N_FIXED, 1, "6"),
            c("own1", Type.OWN_QUANTITY, 1, "7"), c("own2", Type.OWN_QUANTITY, 1, "8"),
            c("sub1", Type.FIRST_N_SUBSIDY, 1, "9"), c("sub2", Type.FIRST_N_SUBSIDY, 1, "10"),
            c("force1", Type.FORCED_FIRST, 1, "10"), c("force2", Type.FORCED_FIRST, 1, "12"));
        Snapshot snap = s(1, configs.toArray(Config[]::new)); svc.capture(snap);
        assertEquals(snap, svc.snapshot(1));
        assertTrue(store.snapshot(1).contains("\"key\":\"force2\""));
        Result grant = svc.qualify(1, q());
        assertEquals(12, grant.instructions().size()); assertEquals(d("64.00"), total(grant, false));
        assertEquals(12, grant.instructions().stream().map(Instruction::awardId).distinct().count());
        assertEquals(grant, svc.qualify(1, q()));
        Refund half = Refund.fromCommissionRecovery(d("3"), d("6"), d("5"), d("3"));
        Result recovered = svc.refund(1, half);
        assertEquals(d("32.00"), total(recovered, true)); assertEquals(recovered, svc.refund(1, half));
        assertEquals(d("64.00"), total(svc.refund(1, new Refund(d("1"), d("0"), d("0"))), true));
        svc.capture(s(2, c("force1", Type.FORCED_FIRST, 2, "90"), c("force-new", Type.FORCED_FIRST, 2, "90"),
            c("own1", Type.OWN_QUANTITY, 2, "90"), c("first1", Type.INVITEE_FIRST_FIXED, 2, "90")));
        assertTrue(svc.qualify(2, q()).instructions().isEmpty());
    }
    @Test public void budgetIncludesBothForcedFirstShortfallsNotJustLargestGuarantee() {
        Snapshot base = s(1, c("a", Type.FORCED_FIRST, 1, "10"), c("b", Type.FORCED_FIRST, 1, "12"));
        Snapshot limited = new Snapshot(1, base.order(), 20, 10L, base.paidAt(), false, d("0"), d("0"), d("5"), base.configs());
        assertThrows(IllegalArgumentException.class, () -> RewardCalculator.awards(limited, q(), 1, 1, 1, d("0")));
    }
    @Test public void exactFiveSixthsOfThreeCentsRoundsUpAndReplays() {
        var store = new RewardServiceTest.Memory(); var svc = new RewardService(store);
        svc.capture(s(1, c("three-cents", Type.FIRST_N_SUBSIDY, 1, ".03"))); svc.qualify(1, q());
        Refund exact = Refund.fromCommissionRecovery(d("5"), d("6"), d("10"), d("1"));
        assertEquals(d("0.833333333333333333"), exact.cumulativeRatio());
        Result result = svc.refund(1, exact);
        assertEquals(d("0.03"), total(result, true)); assertEquals(result, svc.refund(1, exact));
        // Compatibility intentionally retains the meaning of a caller-supplied finite decimal ratio.
        Award a = store.awards.values().iterator().next();
        assertEquals(d("0.02"), RewardCalculator.recoveryTarget(a, new Refund(exact.cumulativeRatio(), d("10"), d("1")), d("0")));
        assertEquals(d("0.03"), RewardCalculator.recoveryTarget(a,
            Refund.fromCommissionRecovery(d("7"), d("8"), d("10"), d("1")), d(".25")));
    }
    @Test public void keyedIdentitiesFitStorageAndIgnorePublicationVersion() {
        String platform = "测试平台测试平台测试平台测试平台测试平台";
        Config first = new Config("long-key-".repeat(100), Type.FORCED_FIRST, 1, Mode.FIXED, 1, d("10"),
            Set.of(platform), T, T.plusSeconds(100));
        Config later = new Config(first.key(), first.type(), 99, first.mode(), first.n(), first.value(),
            first.platforms(), first.from(), first.until());
        Snapshot a = new Snapshot(Long.MAX_VALUE, new OrderKey(platform, "o", "s"), Long.MAX_VALUE, null,
            T, false, d("0"), d("0"), d("100"), List.of(first));
        Snapshot b = new Snapshot(a.orderId(), a.order(), a.buyerId(), null, T, false, d("0"), d("0"), d("100"), List.of(later));
        String id = RewardCalculator.awards(a, q(), 1, 1, 1, d("0")).get(0).id();
        assertTrue(id.length() <= 160); assertTrue(id.chars().allMatch(ch -> ch < 128));
        assertEquals(id, RewardCalculator.awards(b, q(), 1, 1, 1, d("0")).get(0).id());
    }
    @Test public void keyedBridgeAndLegacySnapshotReadAreImmutable() {
        var incentives = List.of(new RewardPolicyBridge.Incentive("alpha", Type.FIRST_N_SUBSIDY, Mode.FIXED, 1,
            d("2"), Set.of("A"), T, T.plusSeconds(100)),
            new RewardPolicyBridge.Incentive("beta", Type.FIRST_N_SUBSIDY, Mode.FIXED, 1,
            d("3"), Set.of("A"), T, T.plusSeconds(100)));
        var configs = RewardPolicyBridge.configs(new RewardPolicyBridge.Policy(9, d("0"), d("0"), d("10"), incentives));
        assertEquals(List.of("alpha", "beta"), configs.stream().map(Config::key).toList());
        assertThrows(IllegalArgumentException.class, () -> s(1, configs.get(0), configs.get(0)));
        assertThrows(NullPointerException.class, () -> c(null, Type.FIRST_N_SUBSIDY, 1, "1"));
        Config old = new Config(Type.FIRST_N_SUBSIDY, 1, Mode.FIXED, 1, d("2"), Set.of("A"), T, T.plusSeconds(100));
        var store = new RewardServiceTest.Memory();
        // The shared baseline fake parses JSON itself; bypass that implementation for this old-format read test.
        var mapper = (com.ruoyi.system.cps.mapper.RewardProgressMapper) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{com.ruoyi.system.cps.mapper.RewardProgressMapper.class},
            (proxy, method, args) -> method.getName().equals("buyerSnapshots") ?
                new ArrayList<>(store.snapshots.values()) : method.invoke(store, args));
        var svc = new RewardService(mapper); svc.capture(s(1, old));
        var json = JSON.parseObject(store.snapshot(1)); json.getJSONArray("configs").getJSONObject(0).remove("key");
        String historicalPayload = json.toJSONString(); store.snapshots.put(1L, historicalPayload);
        assertEquals(s(1, old), svc.snapshot(1)); svc.capture(s(1, old));
        assertEquals(historicalPayload, store.snapshot(1));
        assertEquals("FIRST_N_SUBSIDY:20:1", svc.qualify(1, q()).instructions().get(0).awardId());
        assertEquals(old.key(), new Config(Type.FIRST_N_SUBSIDY, 2, Mode.FIXED, 1, d("9"), Set.of("A"), T, T.plusSeconds(100)).key());
    }
}
