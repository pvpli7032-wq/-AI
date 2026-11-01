package com.ruoyi.system.cps.settlement;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.ruoyi.union.adapter.*;
import com.ruoyi.system.cps.contract.*;
import com.ruoyi.system.cps.ledger.*;
import com.ruoyi.system.cps.rule.*;
import com.ruoyi.system.cps.settlement.SettlementRepository.*;
import com.ruoyi.system.cps.settlement.SettlementTermsPort.*;

/** Service boundary tests only: no claims about SQL, MySQL row locks or database rollback. */
public class DefaultSettlementServiceBoundaryTest {
    static final Instant START=Instant.parse("2026-01-01T00:00:00Z");
    Memory repository;
    List<PostingCommand> postings;
    SettlementExternalServices external;
    RewardService rewards;
    SettlementTermsPort terms;
    DefaultSettlementService service;
    @Before public void setup() {
        repository=new Memory(); postings=new ArrayList<>();
        external=mock(SettlementExternalServices.class);
        when(external.invitation(anyLong(),any(),any())).thenReturn(new SettlementExternalServices.Invitation(2L,false,"payment-evidence"));
        rewards=mock(RewardService.class);
        when(rewards.qualify(anyLong(),any())).thenReturn(new RewardApi.Result(false,List.of()));
        when(rewards.reprice(anyLong(),any())).thenReturn(new RewardApi.Result(false,List.of()));
        when(rewards.refund(anyLong(),any())).thenReturn(new RewardApi.Result(false,List.of()));
        terms=new SettlementTermsPort() {
            public Terms snapshot(long id,Long buyer,UnionOrderLine line,Long inviter) {
                List<Benefit> b=new ArrayList<>(); b.add(new Benefit("REBATE",buyer,money("5"),7L));
                if (inviter!=null) b.add(new Benefit("SUBSIDY",buyer,money("1"),9L));
                if (inviter!=null) b.add(new Benefit("REFERRAL",inviter,money("2"),8L));
                return new Terms(b,money("100"),"explicit-test-policy",money("2"),"{\"explicitTestSnapshot\":true}");
            }
            public Terms recalculate(Terms snapshot,UnionOrderLine line) {
                BigDecimal ratio=line.actualCommission().divide(money("10"));
                return new Terms(snapshot.benefits().stream().map(b -> new Benefit(b.type(),b.beneficiaryUserId(),b.amount().multiply(ratio),b.ruleVersionId())).toList(),
                    snapshot.maxCommission(),snapshot.freezePolicy(),snapshot.platformAmount().multiply(ratio),snapshot.ruleSnapshotJson());
            }
        };
        LedgerService ledger=c -> { postings.add(c); return new LedgerPosting((long)postings.size(),c.businessKey(),money("-3"),false); };
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        SettlementRewardTermsPort rewardTerms=(id,buyer,line,inviter,unknown) -> new RewardApi.Snapshot(id,
            new RewardApi.OrderKey("TBK","order","line"),buyer,inviter,START,unknown,BigDecimal.ZERO,BigDecimal.ZERO,money("100"),List.of());
        service=new DefaultSettlementService(repository,ledger,provider(terms),provider((SettlementFreezePort)(t,l,b) -> Duration.ofDays(2)),
            external,rewards,provider(rewardTerms),tx);
    }
    static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") ObjectProvider<T> result=mock(ObjectProvider.class);
        when(result.getIfAvailable()).thenReturn(value); return result;
    }
    OrderEvent event(String key,OrderStatus from,OrderStatus to,String recovered,Instant at) {
        return new OrderEvent(key,1L,line("10"),from,to,null,recovered==null ? null : money(recovered),"TEST",1L,at);
    }
    UnionOrderLine line(String actual) {
        return new UnionOrderLine(Platform.TBK,"order","line","item","pid",null,money("100"),money("10"),money(actual),"raw",START.minusSeconds(100000),START,START);
    }
    static BigDecimal money(String s) { return new BigDecimal(s).setScale(2); }
    @Test public void paidIsEstimateOnly() {
        service.onOrderEvent(event("paid",null,OrderStatus.PAID,null,START));
        assertTrue(postings.isEmpty()); assertEquals(3,repository.items.size());
        assertTrue(repository.items.stream().allMatch(i -> i.status==RebateStatus.ESTIMATED));
    }
    @Test public void firstObservedSettledFreezesWithoutFabricatingPaidEvent() {
        service.onOrderEvent(event("settled",null,OrderStatus.SETTLED,null,START));
        assertEquals(3,postings.size());
        assertTrue(postings.stream().allMatch(c -> c.creditAccount()==AccountType.USER_FROZEN));
        assertEquals(OrderStatus.SETTLED,repository.p.status);
        assertEquals(1,repository.receipts.size());
    }
    @Test public void duplicateIsNoOpAndDriftFails() {
        OrderEvent e=event("one",null,OrderStatus.RECEIVED,null,START);
        service.onOrderEvent(e); service.onOrderEvent(e); assertEquals(3,postings.size());
        assertThrows(IllegalArgumentException.class,() -> service.onOrderEvent(event("one",null,OrderStatus.RECEIVED,null,START.plusSeconds(1))));
    }
    @Test public void cumulativeRecoveryUsesOriginalCommissionNotMerchandisePaidAmount() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(event("half",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"5",START.plusSeconds(1)));
        assertEquals(money("4"),postings.stream().filter(c -> c.type().startsWith("refund")).map(PostingCommand::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
        service.onOrderEvent(event("more",OrderStatus.PARTIAL_REFUND,OrderStatus.PARTIAL_REFUND,"7",START.plusSeconds(2)));
        assertEquals(money("5.60"),repository.items.stream().map(i -> i.recovered).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertTrue(repository.items.stream().allMatch(i -> i.phase.equals("FROZEN")));
    }
    @Test public void frozenFullRefundVoidsAllBenefits() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(event("refund",OrderStatus.RECEIVED,OrderStatus.FULL_REFUND,"10",START.plusSeconds(1)));
        assertTrue(repository.items.stream().allMatch(i -> i.status==RebateStatus.VOID));
        assertEquals(money("8"),repository.items.stream().map(i -> i.recovered).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    @Test public void matureReleaseAndCreditedRefundPreserveNegativeBalanceBoundary() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.unfreezeDue(START.plus(Duration.ofDays(3)),10);
        service.onOrderEvent(event("refund",OrderStatus.RECEIVED,OrderStatus.FULL_REFUND,"10",START.plus(Duration.ofDays(4))));
        assertTrue(repository.items.stream().allMatch(i -> i.status==RebateStatus.FULLY_RECOVERED));
        assertTrue(postings.stream().filter(c -> c.type().startsWith("refund")).allMatch(c -> c.debitAccount()==AccountType.USER_AVAILABLE));
        verify(external,atLeastOnce()).notify(anyString(),anyLong(),anyLong(),eq("RECOVER"),any(),eq(money("-3")));
    }
    @Test public void disputePausesAndInvalidRestorationIsAudited() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(new OrderEvent("dispute",1L,line("10"),OrderStatus.RECEIVED,OrderStatus.DISPUTED,OrderStatus.RECEIVED,null,"TEST",1L,START.plusSeconds(100)));
        int count=postings.size(); service.unfreezeDue(START.plus(Duration.ofDays(10)),10); assertEquals(count,postings.size());
        service.onOrderEvent(new OrderEvent("badrestore",1L,line("10"),OrderStatus.DISPUTED,OrderStatus.SETTLED,OrderStatus.RECEIVED,null,"TEST",1L,START.plusSeconds(200)));
        assertEquals(OrderStatus.DISPUTED,repository.p.status); assertFalse(repository.manual.isEmpty());
        service.onOrderEvent(new OrderEvent("restore",1L,line("10"),OrderStatus.DISPUTED,OrderStatus.RECEIVED,OrderStatus.RECEIVED,null,"TEST",1L,START.plusSeconds(200)));
        assertEquals(START.plus(Duration.ofDays(2)).plusSeconds(100),repository.items.getFirst().due);
    }
    @Test public void legacyAccountedNeverEntersEngine() {
        repository.legacy=true; service.onOrderEvent(event("one",null,OrderStatus.SETTLED,null,START));
        assertTrue(postings.isEmpty()); assertNull(repository.p); assertTrue(repository.manual.getFirst().contains("LEGACY"));
    }
    @Test public void unownedIsRecordedAndCanReplayAfterHistoricalAttribution() {
        repository.order.remove("buyer_user_id"); OrderEvent e=event("one",null,OrderStatus.RECEIVED,null,START);
        service.onOrderEvent(e); assertTrue(postings.isEmpty()); assertTrue(repository.receipts.isEmpty());
        repository.order.put("buyer_user_id",1L); service.onOrderEvent(e); assertEquals(3,postings.size());
    }
    @Test public void unknownInvitationOnlyFundsBuyer() {
        when(external.invitation(anyLong(),any(),any())).thenReturn(new SettlementExternalServices.Invitation(null,true,"unknown"));
        service.onOrderEvent(event("one",null,OrderStatus.SETTLED,null,START));
        assertEquals(1,postings.size());
        assertTrue(postings.stream().allMatch(c -> c.userId()==1L)); assertTrue(repository.p.referralManual);
    }
    @Test public void partialFrozenRemainderReleasesOnce() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(event("half",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"5",START.plusSeconds(1)));
        service.unfreezeDue(START.plus(Duration.ofDays(3)),10); int count=postings.size();
        service.unfreezeDue(START.plus(Duration.ofDays(4)),10); assertEquals(count,postings.size());
        assertEquals(money("4"),postings.stream().filter(c -> c.type().equals("settle_release")).map(PostingCommand::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    @Test public void awardRecoveryLinksOriginalAwardAndGloballySortsAccounts() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START)); postings.clear();
        RewardApi.Instruction clawback=new RewardApi.Instruction("award:recovery",1,"award",3,99,RewardApi.Type.OWN_QUANTITY,true,money("9"),BigDecimal.ONE);
        when(rewards.refund(anyLong(),any())).thenReturn(new RewardApi.Result(false,List.of(clawback)));
        service.onOrderEvent(event("half",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"5",START.plusSeconds(1)));
        PostingCommand last=postings.getLast(); assertEquals(Long.valueOf(99),last.orderLineId()); assertEquals(Long.valueOf(3),last.userId());
        assertEquals(AccountType.USER_AVAILABLE,last.debitAccount());
        assertEquals(postings.stream().map(PostingCommand::userId).sorted().toList(),postings.stream().map(PostingCommand::userId).toList());
    }
    @Test public void notificationFailurePropagatesNotAcknowledged() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        doThrow(new IllegalStateException("outbox unavailable")).when(external).notify(anyString(),anyLong(),anyLong(),anyString(),any(),any());
        assertThrows(IllegalStateException.class,() -> service.onOrderEvent(event("refund",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"5",START.plusSeconds(1))));
        assertFalse(repository.receipts.containsKey("refund")); // SQL rollback must separately be verified on real MySQL.
    }
    @Test public void ceilingRejectsBeforeLedger() {
        OrderEvent e=event("one",null,OrderStatus.RECEIVED,null,START);
        assertThrows(IllegalArgumentException.class,() -> service.onOrderEvent(new OrderEvent(e.eventKey(),e.orderLineId(),line("101"),null,e.toStatus(),null,null,e.source(),e.rawId(),e.occurredAt())));
        assertTrue(postings.isEmpty());
    }
    @Test public void staleSamePhaseCannotRepriceFunds() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(new OrderEvent("stale",1L,line("20"),OrderStatus.RECEIVED,OrderStatus.RECEIVED,null,null,"TEST",1L,START.minusSeconds(1)));
        assertEquals(3,postings.size()); assertEquals(money("10"),repository.p.commission);
        assertTrue(repository.manual.getLast().contains("STALE"));
    }
    @Test public void partialCanProgressToFullWithFrozenPhase() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(event("half",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"5",START.plusSeconds(1)));
        service.onOrderEvent(event("full",OrderStatus.PARTIAL_REFUND,OrderStatus.FULL_REFUND,"10",START.plusSeconds(2)));
        assertEquals(OrderStatus.FULL_REFUND,repository.p.status);
        assertTrue(repository.items.stream().allMatch(i -> i.status==RebateStatus.VOID));
    }
    @Test public void repriceUsesImmutableOriginalRuleSnapshot() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(new OrderEvent("reprice",1L,line("20"),OrderStatus.RECEIVED,OrderStatus.SETTLED,null,null,"TEST",1L,START.plusSeconds(1)));
        assertEquals(money("5"),repository.p.terms.benefits().getFirst().amount());
        assertEquals(money("10"),repository.p.actualTerms.benefits().getFirst().amount());
        assertEquals(repository.p.terms.ruleSnapshotJson(),repository.p.actualTerms.ruleSnapshotJson());
        assertEquals(money("20"),repository.p.commission);
    }
    @Test public void decreasingCumulativeRecoveryIsRejectedBeforeFurtherLedgerWork() {
        service.onOrderEvent(event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(event("half",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"5",START.plusSeconds(1)));
        int count=postings.size();
        assertThrows(IllegalArgumentException.class,() -> service.onOrderEvent(event("less",OrderStatus.PARTIAL_REFUND,OrderStatus.PARTIAL_REFUND,"4",START.plusSeconds(2))));
        assertEquals(count,postings.size());
    }
    static class Memory extends SettlementRepository {
        Map<String,Object> order=new HashMap<>(); Position p; boolean legacy;
        List<Item> items=new ArrayList<>(); Map<String,String> receipts=new HashMap<>(); List<String> manual=new ArrayList<>();
        Memory() { super(null); order.put("order_id","order"); order.put("sub_order_no","line"); order.put("platform_type","tbk"); order.put("buyer_user_id",1L); }
        @Override Map<String,Object> lockOrder(long id) { return order; }
        @Override Position load(long id) { return p; }
        @Override void save(Position p) { this.p=p; }
        @Override void order(Position p) { order.put("line_status",p.status.name()); }
        @Override boolean legacy(long id,Map<String,Object> order) { return legacy; }
        @Override String receipt(String key) { return receipts.get(key); }
        @Override void receipt(String key,long id,String fingerprint) { receipts.put(key,fingerprint); }
        @Override void manual(long id,String key,String reason) { manual.add(reason); }
        @Override void insert(long id,Benefit b) {
            Item i=new Item(); i.id=items.size()+1; i.user=b.beneficiaryUserId(); i.type=b.type(); i.version=b.ruleVersionId();
            i.original=b.amount(); i.recovered=BigDecimal.ZERO.setScale(2); i.status=RebateStatus.ESTIMATED; i.phase="NONE"; items.add(i);
        }
        @Override List<Item> items(long id) {
            items.forEach(i -> { i.adjustment=BigDecimal.ZERO; i.newlyFrozen=false; });
            items.sort(Comparator.comparingLong((Item i) -> i.user).thenComparingLong(i -> i.id)); return items;
        }
        @Override void save(Item i) {}
        @Override List<Long> due(Instant now,int limit) { return items.stream().anyMatch(i -> i.phase.equals("FROZEN") && i.paused==null && i.due!=null && !i.due.isAfter(now) && i.status!=RebateStatus.VOID) ? List.of(1L) : List.of(); }
        @Override BigDecimal paidAmount(long id) { return money("100"); }
    }
}
