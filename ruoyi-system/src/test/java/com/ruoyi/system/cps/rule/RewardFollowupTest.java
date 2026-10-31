package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/** Pure tests only. Shared in-memory fake has NO SQL, isolation, locking, or rollback guarantees. */
public class RewardFollowupTest {
    private static final Instant T=Instant.parse("2026-01-01T00:00:00Z");
    private static BigDecimal d(String s){return new BigDecimal(s);}
    private static Config config(Type type, String value, int n, long version) {
        return new Config(type,version,Mode.FIXED,n,d(value),Set.of("A","B"),T.minusSeconds(60),T.plusSeconds(60));
    }
    private static Snapshot snap(long id,long buyer,String platform,Instant paid,Config... configs) {
        return new Snapshot(id,new OrderKey(platform,"order"+id,"child"),buyer,null,paid,false,d("1"),d("1"),d("100"),List.of(configs));
    }
    private static Qualification q(String rebate) {
        return new Qualification(T.plusSeconds(60),T.plusSeconds(30),true,false,d("20"),d("20"),d(rebate));
    }
    private static BigDecimal total(Result result,boolean recovery) {
        return result.instructions().stream().filter(i->i.recovery()==recovery).map(Instruction::amount).reduce(d("0.00"),BigDecimal::add);
    }
    private static Refund refund(String ratio,String paid,String commission) {return new Refund(d(ratio),d(paid),d(commission));}

    @Test public void guaranteeIsOnlyShortfallNotAnExtraBonusAndStacksWithSubsidy() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        Config forced=config(Type.FORCED_FIRST,"10",1,1);
        svc.capture(snap(1,20,"A",T,forced,config(Type.FIRST_N_SUBSIDY,"4",1,1)));
        Result result=svc.qualify(1,q("8"));
        assertEquals(d("6.00"),total(result,false));
        assertEquals(d("2.00"),result.instructions().stream().filter(i->i.type()==Type.FORCED_FIRST).findFirst().orElseThrow().amount());
        assertEquals(result,svc.qualify(1,q("8")));
        assertEquals(2,store.awards.size());
    }
    @Test public void zeroTopupStillConsumesFirstPerPlatformAcrossVersions() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        svc.capture(snap(1,20,"A",T,config(Type.FORCED_FIRST,"10",1,1)));
        assertTrue(svc.qualify(1,q("12")).instructions().isEmpty());
        svc.capture(snap(2,20,"A",T.plusSeconds(1),config(Type.FORCED_FIRST,"50",1,2)));
        assertTrue(svc.qualify(2,q("0")).instructions().isEmpty());
        svc.capture(snap(3,20,"B",T.plusSeconds(2),config(Type.FORCED_FIRST,"10",1,3)));
        assertEquals(d("10.00"),total(svc.qualify(3,q("0")),false));
        svc.capture(snap(4,21,"A",T,config(Type.FORCED_FIRST,"10",1,4)));
        assertEquals(d("2.00"),total(svc.qualify(4,q("8")),false));
        assertEquals(2,store.awards.size());
    }
    @Test public void fullRefundClawsBackGuaranteeButNeverRecyclesEntitlement() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        svc.capture(snap(1,20,"A",T,config(Type.FORCED_FIRST,"10",1,1)));
        String identity=svc.qualify(1,q("8")).instructions().getFirst().awardId();
        Result half=svc.refund(1,refund(".5","10","10")); assertEquals(d("1.00"),total(half,true));
        assertEquals(half,svc.refund(1,refund(".5","10","10")));
        assertEquals(d("2.00"),total(svc.refund(1,refund("1","0","0")),true));
        svc.capture(snap(2,20,"A",T.plusSeconds(1),config(Type.FORCED_FIRST,"20",1,2)));
        assertTrue(svc.qualify(2,q("0")).instructions().isEmpty());
        assertEquals(identity,store.awards.values().iterator().next().id());
    }
    @Test public void priorRefundUsesCurrentOrdinaryRebateAndMinimumWithoutDoubleDiscount() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        svc.capture(snap(1,20,"A",T,config(Type.FORCED_FIRST,"10",1,1)));
        svc.refund(1,refund(".25","15","15"));
        assertEquals(d("4.00"),total(svc.qualify(1,q("6")),false));
        assertEquals(d("1.33"),total(svc.refund(1,refund(".5","10","10")),true));
        assertEquals(d("4.00"),total(svc.refund(1,refund("1","0","0")),true));
    }
    @Test public void paymentOrderingDefersLaterReleaseAndPreservesAllFirstNPolicies() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        List<Config> configs=List.of(config(Type.INVITEE_FIRST_FIXED,"1",1,1),
            config(Type.INVITEE_FIRST_N_FIXED,"2",1,1),config(Type.FIRST_N_SUBSIDY,"3",1,1),config(Type.FORCED_FIRST,"10",1,1));
        Snapshot later=withInviter(snap(2,20,"A",T.plusSeconds(1),configs.toArray(Config[]::new)),10L);
        Snapshot earlier=withInviter(snap(1,20,"A",T,configs.toArray(Config[]::new)),10L);
        svc.capture(later);svc.capture(earlier);
        assertTrue(svc.qualify(2,q("8")).instructions().isEmpty());assertEquals(0,store.validCount(20));
        assertEquals(d("8.00"),total(svc.qualify(1,q("8")),false));
        assertTrue(svc.qualify(2,q("8")).instructions().isEmpty());assertEquals(2,store.validCount(20));
        svc.capture(earlier); // Exact snapshot retry remains idempotent after a later order qualifies.
    }
    @Test public void finalizedInvalidEarlierCandidateUnblocksFirstValidOrder() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        Config forced=config(Type.FORCED_FIRST,"10",1,1);
        svc.capture(snap(1,20,"A",T,forced));svc.capture(snap(2,20,"A",T.plusSeconds(1),forced));
        assertTrue(svc.qualify(2,q("8")).instructions().isEmpty());
        assertThrows(IllegalArgumentException.class,()->svc.excludeInvalid(1,q("8")));
        svc.excludeInvalid(1,new Qualification(T.plusSeconds(60),T.plusSeconds(30),true,false,d("20"),d("0"),d("0")));
        assertEquals(d("2.00"),total(svc.qualify(2,q("8")),false));
        assertTrue(svc.qualify(1,q("8")).instructions().isEmpty());
        assertThrows(IllegalStateException.class,()->svc.excludeInvalid(2,new Qualification(T.plusSeconds(60),T,true,false,d("0"),d("0"),d("0"))));
    }
    @Test public void lateBackfillCannotSilentlyReassignAlreadyConsumedPaymentSlots() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        svc.capture(snap(2,20,"A",T.plusSeconds(1),config(Type.FORCED_FIRST,"10",1,1)));
        svc.qualify(2,q("8"));
        assertThrows(IllegalStateException.class,()->svc.capture(snap(1,20,"A",T,config(Type.FORCED_FIRST,"10",1,2))));
        assertNull(store.snapshot(1));
    }
    @Test public void paymentTiesUseStableNaturalOrderNotReleaseOrCaptureOrder() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        Config forced=config(Type.FORCED_FIRST,"10",1,1);
        svc.capture(snap(2,20,"A",T,forced));svc.capture(snap(1,20,"A",T,forced));
        assertTrue(svc.qualify(2,q("8")).instructions().isEmpty());
        assertEquals(d("2.00"),total(svc.qualify(1,q("8")),false));
        assertTrue(svc.qualify(2,q("8")).instructions().isEmpty());
    }
    @Test public void commissionThresholdLossFullyRecoversGuaranteeEvenAtPartialRatio() {
        var store=new RewardServiceTest.Memory(); var svc=new RewardService(store);
        svc.capture(snap(1,20,"A",T,config(Type.FORCED_FIRST,"10",1,1)));svc.qualify(1,q("8"));
        assertEquals(d("2.00"),total(svc.refund(1,refund(".1","18","1")),true));
        assertEquals(0,store.validCount(20));
    }
    @Test public void bridgeCopiesImmutableValuesAndEnforcesSingleTypeAndFixedGuarantee() {
        var incentives=new ArrayList<RewardPolicyBridge.Incentive>();
        incentives.add(new RewardPolicyBridge.Incentive(Type.FORCED_FIRST,Mode.FIXED,1,d("10"),Set.of("A"),T.minusSeconds(1),T.plusSeconds(1)));
        var policy=new RewardPolicyBridge.Policy(7,d("1"),d("2"),d("9"),incentives);
        incentives.clear();
        Snapshot snapshot=RewardPolicyBridge.snapshot(1,new OrderKey("A","o","s"),20,null,T,false,policy);
        assertEquals(7,snapshot.configs().getFirst().version());assertEquals(d("10.00"),snapshot.configs().getFirst().value());
        assertEquals(d("9.00"),snapshot.maximumReward());assertEquals(d("2"),snapshot.minimumCommission());
        assertThrows(UnsupportedOperationException.class,()->snapshot.configs().clear());
        assertThrows(IllegalArgumentException.class,()->new Config(Type.FORCED_FIRST,1,Mode.RATIO,1,d(".5"),Set.of("A"),T,T.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->config(Type.FORCED_FIRST,"10",2,1));
        var duplicate=new RewardPolicyBridge.Policy(7,d("1"),d("2"),d("9"),List.of(policy.incentives().getFirst(),policy.incentives().getFirst()));
        assertThrows(IllegalArgumentException.class,()->RewardPolicyBridge.configs(duplicate));
    }
    @Test public void optionalGuaranteeCannotRetroactivelyReplaceUnconfiguredOrOutOfWindowFirstOrder() {
        var store=new RewardServiceTest.Memory();var svc=new RewardService(store);
        svc.capture(snap(1,20,"A",T));svc.qualify(1,q("0"));
        svc.capture(snap(2,20,"A",T.plusSeconds(1),config(Type.FORCED_FIRST,"10",1,2)));
        assertTrue(svc.qualify(2,q("0")).instructions().isEmpty());
        Config expired=new Config(Type.FORCED_FIRST,1,Mode.FIXED,1,d("10"),Set.of("A"),T.minusSeconds(2),T);
        svc.capture(snap(3,21,"A",T,expired));assertTrue(svc.qualify(3,q("0")).instructions().isEmpty());
        svc.capture(snap(4,22,"A",T,config(Type.FORCED_FIRST,"10",1,2)));
        assertTrue(svc.qualify(4,q("10")).instructions().isEmpty());
    }
    @Test public void forcedPolicyIsImmutableAndHasNoGrantBeforeFreezeReleaseOrDuringHold() {
        var store=new RewardServiceTest.Memory();var svc=new RewardService(store);
        Snapshot s=snap(1,20,"A",T,config(Type.FORCED_FIRST,"10",1,1));svc.capture(s);
        assertThrows(IllegalStateException.class,()->svc.capture(snap(1,20,"A",T,config(Type.FORCED_FIRST,"20",1,2))));
        assertThrows(IllegalArgumentException.class,()->RewardCalculator.awards(s,q("8"),1,1,d("0")));
        for (Qualification blocked : List.of(
                new Qualification(T.plusSeconds(60),T.plusSeconds(30),false,false,d("20"),d("20"),d("8")),
                new Qualification(T,T.plusSeconds(30),true,false,d("20"),d("20"),d("8")),
                new Qualification(T.plusSeconds(60),T.plusSeconds(30),true,true,d("20"),d("20"),d("8"))))
            assertTrue(svc.qualify(1,blocked).instructions().isEmpty());
        assertEquals(0,store.validCount(20));assertEquals(d("2.00"),total(svc.qualify(1,q("8")),false));
    }
    @Test public void historicalReferralUnknownMeansManualRewardsNotOrdinaryRebateCancellation() {
        var store=new RewardServiceTest.Memory();var svc=new RewardService(store);
        Snapshot base=snap(1,20,"A",T,config(Type.FORCED_FIRST,"10",1,1),config(Type.FIRST_N_SUBSIDY,"4",1,1));
        svc.capture(new Snapshot(1,base.order(),20,null,T,true,base.minimumPaid(),base.minimumCommission(),base.maximumReward(),base.configs()));
        Qualification ordinary=q("8");Result result=svc.qualify(1,ordinary);
        assertTrue(result.manualReview());assertTrue(result.instructions().isEmpty());assertEquals(d("8.00"),ordinary.userRebate());
        assertEquals(0,store.validCount(20)); // Caller separately posts this ordinary rebate; no ledger is called by this engine.
    }
    private static Snapshot withInviter(Snapshot s,Long inviter) {
        return new Snapshot(s.orderId(),s.order(),s.buyerId(),inviter,s.paidAt(),s.historicalUnknown(),s.minimumPaid(),s.minimumCommission(),s.maximumReward(),s.configs());
    }
}
