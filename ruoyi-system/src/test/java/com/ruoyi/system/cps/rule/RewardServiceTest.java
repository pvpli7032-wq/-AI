package com.ruoyi.system.cps.rule;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.mapper.RewardProgressMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/** Pure behavioral tests with in-memory storage. NOT SQL, transaction, or concurrency evidence. */
public class RewardServiceTest {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static Config c(Type t, Mode m, int n, String value, long version) {
        return new Config(t, version, m, n, d(value), Set.of("TEST"), TIME.minusSeconds(1), TIME.plusSeconds(1));
    }
    private static Snapshot s(long id, Long inviter, Config... configs) {
        return new Snapshot(id, new OrderKey("TEST", "order"+id, "child"), 20, inviter, TIME, false,
            d("1"), d("1"), d("100"), List.of(configs));
    }
    private static Qualification q() { return new Qualification(TIME, TIME, true, false, d("10"), d("10"), d("8")); }
    private static Refund r(String ratio, String paid, String commission) { return new Refund(d(ratio), d(paid), d(commission)); }
    private static BigDecimal sum(List<Instruction> list, boolean recovery) {
        return list.stream().filter(i -> i.recovery()==recovery).map(Instruction::amount).reduce(d("0.00"), BigDecimal::add);
    }
    @Test public void fiveTypesStackAndRatioUsesRebate() {
        Memory m = new Memory(); RewardService svc = new RewardService(m);
        svc.capture(s(1, 10L, c(Type.INVITATION_RATIO, Mode.RATIO, 1, ".25", 1),
            c(Type.INVITEE_FIRST_FIXED, Mode.FIXED, 1, "3", 1),
            c(Type.INVITEE_FIRST_N_FIXED, Mode.FIXED, 2, "4", 1),
            c(Type.OWN_QUANTITY, Mode.FIXED, 1, "5", 1),
            c(Type.FIRST_N_SUBSIDY, Mode.RATIO, 2, ".5", 1)));
        Result result = svc.qualify(1,q());
        assertEquals(5,result.instructions().size()); assertEquals(d("18.00"),sum(result.instructions(),false));
        assertEquals(result,svc.qualify(1,q())); assertEquals(1,svc.validInvitees(10));
        assertEquals(2,m.awards.values().stream().filter(a -> a.type()==Type.INVITATION_RATIO).findFirst().orElseThrow().amount().intValue());
    }
    @Test public void snapshotRoundTripAndImmutableDriftRejected() {
        Memory m = new Memory(); RewardService svc = new RewardService(m);
        Snapshot snapshot=s(1,10L,c(Type.INVITEE_FIRST_FIXED,Mode.FIXED,1,"2",1));
        assertEquals(snapshot,JSON.parseObject(JSON.toJSONString(snapshot),Snapshot.class));
        svc.capture(snapshot); svc.capture(snapshot);
        assertThrows(IllegalStateException.class,()->svc.capture(s(1,11L)));
        assertThrows(IllegalStateException.class,()->svc.qualify(99,q()));
    }
    @Test public void noAutomaticReferralAndUnknownNeedsManualReview() {
        Memory m=new Memory(); RewardService svc=new RewardService(m);
        svc.capture(s(1,null,c(Type.INVITEE_FIRST_FIXED,Mode.FIXED,1,"2",1)));
        assertTrue(svc.qualify(1,q()).instructions().isEmpty()); assertEquals(1,m.validCount(20));
        Snapshot source=s(2,null); svc.capture(new Snapshot(2,source.order(),20,null,TIME,true,d("1"),d("1"),d("100"),List.of()));
        assertTrue(svc.qualify(2,q()).manualReview()); assertEquals(1,m.validCount(20));
    }
    @Test public void freezePauseRiskAndZeroCommissionDoNotContribute() {
        Memory m=new Memory(); RewardService svc=new RewardService(m); svc.capture(s(1,10L));
        for (Qualification blocked : List.of(
                new Qualification(TIME,TIME.plusSeconds(1),true,false,d("10"),d("10"),d("8")),
                new Qualification(TIME,TIME,false,false,d("10"),d("10"),d("8")),
                new Qualification(TIME,TIME,true,true,d("10"),d("10"),d("8")),
                new Qualification(TIME,TIME,true,false,d("10"),d("0"),d("0")),
                new Qualification(TIME,TIME,true,false,d("1"),d("10"),d("8")))) svc.qualify(1,blocked);
        assertEquals(0,m.validCount(20)); svc.qualify(1,q()); assertEquals(1,m.validCount(20));
    }
    @Test public void refundBeforeFreezeVoidAndPartialReduction() {
        Memory m=new Memory(); RewardService svc=new RewardService(m);
        svc.capture(s(1,10L,c(Type.INVITEE_FIRST_FIXED,Mode.FIXED,1,"4",1)));
        assertTrue(svc.refund(1,r("1","0","0")).instructions().isEmpty());
        assertTrue(svc.qualify(1,q()).instructions().isEmpty());
        svc.capture(s(2,10L,c(Type.INVITEE_FIRST_FIXED,Mode.FIXED,1,"4",1)));
        svc.refund(2,r(".25","7.5","7.5"));
        assertEquals(d("3.00"),sum(svc.qualify(2,q()).instructions(),false));
        assertEquals(d("1.00"),sum(svc.refund(2,r(".5","5","5")).instructions(),true));
        assertEquals(d("3.00"),sum(svc.refund(2,r("1","0","0")).instructions(),true));
    }
    @Test public void cumulativeRefundIdempotentAndNoNegativeDelta() {
        Memory m=new Memory(); RewardService svc=new RewardService(m);
        svc.capture(s(1,10L,c(Type.INVITEE_FIRST_FIXED,Mode.FIXED,1,"4",1)));
        svc.qualify(1,q()); Result half=svc.refund(1,r(".5","5","5"));
        assertEquals(d("2.00"),sum(half.instructions(),true));
        assertEquals(half,svc.refund(1,r(".5","5","5")));
        assertThrows(IllegalArgumentException.class,()->svc.refund(1,r(".4","6","6")));
        assertEquals(d("4.00"),sum(svc.refund(1,r("1","0","0")).instructions(),true));
        assertEquals(0,svc.validInvitees(10));
    }
    @Test public void quantityRecoveryFindsDifferentTriggerAndNeverReawardsAfterVersionChange() {
        Memory m=new Memory(); RewardService svc=new RewardService(m);
        Config quantity=c(Type.OWN_QUANTITY,Mode.FIXED,2,"10",1);
        svc.capture(s(1,10L)); svc.qualify(1,q());
        svc.capture(s(2,10L,quantity)); assertEquals(d("10.00"),sum(svc.qualify(2,q()).instructions(),false));
        Result result=svc.refund(1,r("1","0","0"));
        assertEquals(d("10.00"),sum(result.instructions(),true));
        assertEquals(2,result.instructions().getFirst().awardOrderId());
        svc.capture(s(3,10L,c(Type.OWN_QUANTITY,Mode.FIXED,2,"20",2)));
        assertTrue(svc.qualify(3,q()).instructions().isEmpty()); assertEquals(2,m.validCount(20));
    }
    @Test public void lifetimeFirstNSlotsDoNotRecycleAfterRefundOrVersionChange() {
        Memory m=new Memory(); RewardService svc=new RewardService(m);
        Config first=c(Type.INVITEE_FIRST_FIXED,Mode.FIXED,1,"2",1);
        Config early=c(Type.INVITEE_FIRST_N_FIXED,Mode.FIXED,2,"3",1);
        Config subsidy=c(Type.FIRST_N_SUBSIDY,Mode.FIXED,2,"1",1);
        svc.capture(s(1,10L,first,early,subsidy)); svc.qualify(1,q()); svc.refund(1,r("1","0","0"));
        svc.capture(s(2,11L,c(Type.INVITEE_FIRST_FIXED,Mode.FIXED,1,"9",2),early,subsidy));
        assertEquals(d("4.00"),sum(svc.qualify(2,q()).instructions(),false));
        svc.capture(s(3,11L,first,early,subsidy)); assertTrue(svc.qualify(3,q()).instructions().isEmpty());
    }
    @Test public void inviteeCountDeduplicatesAndOnlyDropsAfterLastValidOrder() {
        Memory m=new Memory(); RewardService svc=new RewardService(m);
        svc.capture(s(1,10L)); svc.capture(s(2,10L)); svc.qualify(1,q()); svc.qualify(2,q());
        assertEquals(1,svc.validInvitees(10)); svc.refund(1,r("1","0","0")); assertEquals(1,svc.validInvitees(10));
        svc.refund(2,r("1","0","0")); assertEquals(0,svc.validInvitees(10));
    }
    @Test public void sortedLocksAndPaymentTimeApplicability() {
        assertEquals(List.of(10L,20L),RewardCalculator.lockUsers(s(1,10L)));
        assertEquals(List.of(20L,30L),RewardCalculator.lockUsers(s(1,30L)));
        Config expired=new Config(Type.FIRST_N_SUBSIDY,1,Mode.FIXED,1,d("2"),Set.of("TEST"),TIME.minusSeconds(2),TIME);
        assertTrue(RewardCalculator.awards(s(1,null,expired),q(),1,1,d("0")).isEmpty());
        Config other=new Config(Type.FIRST_N_SUBSIDY,1,Mode.FIXED,1,d("2"),Set.of("OTHER"),TIME.minusSeconds(1),TIME.plusSeconds(1));
        assertTrue(RewardCalculator.awards(s(1,null,other),q(),1,1,d("0")).isEmpty());
    }
    @Test public void mapperMetadataAndMandatoryTransactionContract() {
        // Metadata parsing only: does not execute or validate any SQL against MySQL.
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();
        config.addMapper(RewardProgressMapper.class);
        String prefix=RewardProgressMapper.class.getName()+".";
        assertEquals(RewardProgressMapper.State.class,config.getMappedStatement(prefix+"state").getResultMaps().getFirst().getType());
        assertEquals(Award.class,config.getMappedStatement(prefix+"awards").getResultMaps().getFirst().getType());
        var tx=RewardService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Propagation.MANDATORY,tx.propagation());
        assertArrayEquals(new Class<?>[]{Exception.class},tx.rollbackFor());
    }
    @Test public void recoveryRoundsOnlyAtFinalCentForLargeAmounts() {
        Award award=new Award("TEST",20,1,20,Type.FIRST_N_SUBSIDY,0,d("9999999999999999.98"),d("0.00"));
        assertEquals(d("3333333333333333.33"),RewardCalculator.recoveryTarget(award,d(".5"),d(".25")));
    }
    @Test public void inputValidationBudgetAndHalfUp() {
        assertEquals(d("0.01"),money(d("0.005")));
        assertThrows(IllegalArgumentException.class,()->c(Type.INVITATION_RATIO,Mode.RATIO,1,"1.1",1));
        assertThrows(IllegalArgumentException.class,()->r(".1234567890123456789","1","1"));
        assertThrows(IllegalArgumentException.class,()->s(1,20L));
        assertThrows(IllegalArgumentException.class,()->s(1,null,c(Type.FIRST_N_SUBSIDY,Mode.FIXED,1,"1",1),c(Type.FIRST_N_SUBSIDY,Mode.FIXED,2,"2",2)));
        Snapshot base=s(1,null,c(Type.FIRST_N_SUBSIDY,Mode.FIXED,1,"2",1));
        Snapshot budget=new Snapshot(1,base.order(),20,null,TIME,false,d("1"),d("1"),d("1"),base.configs());
        assertThrows(IllegalArgumentException.class,()->RewardCalculator.awards(budget,q(),1,1,d("0")));
    }

    /** In-memory contract fake only. Intentionally supplies NO SQL/locking/rollback guarantees. */
    static final class Memory implements RewardProgressMapper {
        final Map<Long,String> snapshots=new HashMap<>(); final Map<Long,State> states=new HashMap<>();
        final Map<Long,Long> buyers=new HashMap<>(); final Map<String,Award> awards=new LinkedHashMap<>();
        final Map<String,String> instructions=new LinkedHashMap<>(); final Map<String,Long> causes=new HashMap<>();
        public int ensureUser(long id){return 1;} public long lockUser(long id){return id;}
        public String snapshot(long id){return snapshots.get(id);}
        public List<String> buyerSnapshots(long buyer){return snapshots.values().stream().filter(p->JSON.parseObject(p,Snapshot.class).buyerId()==buyer).toList();}
        public List<Long> validInviteeBuyers(long inviter){return snapshots.entrySet().stream().filter(e->states.get(e.getKey()).valid()).map(e->JSON.parseObject(e.getValue(),Snapshot.class)).filter(s->Objects.equals(s.inviterId(),inviter)).map(Snapshot::buyerId).toList();}
        public int insertSnapshot(long id,String p,String o,String sub,long b,Long i,String payload){snapshots.put(id,payload);return 1;}
        public int insertState(long id,long buyer){states.put(id,new State(false,false,false,d("0"),d("0")));buyers.put(id,buyer);return 1;}
        public State state(long id){return states.get(id);}
        public long lifetimeCount(long buyer){return states.entrySet().stream().filter(e->buyers.get(e.getKey())==buyer&&e.getValue().qualified()).count();}
        public long validCount(long buyer){return states.entrySet().stream().filter(e->buyers.get(e.getKey())==buyer&&e.getValue().valid()).count();}
        public long validInvitees(long inviter){return snapshots.entrySet().stream().filter(e->states.get(e.getKey()).valid()).map(e->JSON.parseObject(e.getValue(),Snapshot.class)).filter(s->Objects.equals(s.inviterId(),inviter)).map(Snapshot::buyerId).distinct().count();}
        public int qualify(long id){State s=state(id);states.put(id,new State(true,true,s.closed(),s.refundRatio(),s.refundRatio()));return 1;}
        public int refund(long id,BigDecimal ratio,boolean valid){State s=state(id);states.put(id,new State(s.qualified(),s.qualified()&&valid,s.closed()||!valid,ratio,s.baselineRatio()));return 1;}
        public List<Award> awards(long buyer){return awards.values().stream().filter(a->a.buyerId()==buyer).toList();}
        public long awardExists(String id){return awards.containsKey(id)?1:0;}
        public int insertAward(Award a){if(awards.putIfAbsent(a.id(),a)!=null)throw new AssertionError("duplicate award");return 1;}
        public int recover(String id,BigDecimal amount){Award a=awards.get(id);awards.put(id,new Award(id,a.userId(),a.orderId(),a.buyerId(),a.type(),a.threshold(),a.amount(),amount));return 1;}
        public int instruction(String key,long cause,String payload){if(instructions.putIfAbsent(key,payload)!=null)throw new AssertionError("duplicate instruction");causes.put(key,cause);return 1;}
        public List<String> instructions(long id){return instructions.entrySet().stream().filter(e->causes.get(e.getKey())==id).map(Map.Entry::getValue).toList();}
    }
}
