package com.ruoyi.system.cps.settlement;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.ruoyi.system.cps.contract.*;
import com.ruoyi.system.cps.ledger.*;
import com.ruoyi.system.cps.rule.*;
import static com.ruoyi.system.cps.settlement.DefaultSettlementServiceBoundaryTest.*;

/** Boundary regressions; does not claim SQL execution, row-lock or rollback coverage. */
public class RewardQualificationRetryTest {
    DefaultSettlementServiceBoundaryTest fixture;
    RetryMemory repository;
    DefaultSettlementService service;
    RewardApi.Snapshot snapshot;

    @Before public void setup() {
        fixture=new DefaultSettlementServiceBoundaryTest(); fixture.setup();
        repository=new RetryMemory();
        snapshot=new RewardApi.Snapshot(1,new RewardApi.OrderKey("TBK","order","line"),1,2L,START,false,
            BigDecimal.ZERO,BigDecimal.ZERO,money("100"),List.of());
        when(fixture.rewards.snapshot(1)).thenReturn(snapshot);
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        LedgerService ledger=c -> {
            fixture.postings.add(c);
            return new LedgerPosting((long)fixture.postings.size(),c.businessKey(),money("0"),false);
        };
        SettlementRewardTermsPort rewardTerms=(id,buyer,line,inviter,unknown) -> snapshot;
        service=new DefaultSettlementService(repository,ledger,provider(fixture.terms),
            provider((SettlementFreezePort)(t,l,b) -> Duration.ofDays(2)),fixture.external,fixture.rewards,
            provider(rewardTerms),tx);
    }
    @Test public void deferredRewardRetriesAfterEveryItemIsAvailableWithoutReleasingTwice() {
        service.onOrderEvent(fixture.event("received",null,OrderStatus.RECEIVED,null,START));
        Instant first=START.plus(Duration.ofDays(3));
        service.unfreezeDue(first,1); // earlier captured payment is still pending
        assertTrue(repository.items.stream().allMatch(i -> "AVAILABLE".equals(i.phase)));
        assertTrue(repository.p.rewardPending);
        assertEquals(first.plusSeconds(60),repository.p.rewardRetryAt);
        int posted=fixture.postings.size();
        service.unfreezeDue(first.plusSeconds(59),1);
        verify(fixture.rewards,times(1)).qualify(eq(1L),any());
        // Predecessor is now complete: qualification can award on the independent retry.
        RewardApi.Instruction award=new RewardApi.Instruction("retry-award:A",1,"retry-award",1,1,
            RewardApi.Type.OWN_QUANTITY,false,money("2"),BigDecimal.ZERO);
        when(fixture.rewards.qualify(eq(1L),any())).thenAnswer(i -> {
            repository.rewardComplete=true;
            return new RewardApi.Result(false,List.of(award));
        });
        service.unfreezeDue(first.plusSeconds(60),1);
        assertEquals(posted+2,fixture.postings.size()); // reward funding plus reward credit only
        assertEquals("retry-award:A",fixture.postings.getLast().businessKey());
        service.unfreezeDue(first.plusSeconds(120),1);
        assertEquals(posted+2,fixture.postings.size());
        verify(fixture.rewards,times(2)).qualify(eq(1L),any());
    }
    @Test public void invalidPaymentClosesCandidateWithoutQualificationOrSlotConsumption() {
        service.onOrderEvent(fixture.event("paid",null,OrderStatus.PAID,null,START));
        service.onOrderEvent(fixture.event("invalid",OrderStatus.PAID,OrderStatus.INVALID,null,START.plusSeconds(1)));
        verify(fixture.rewards).excludeInvalid(eq(1L),argThat(q -> q.commission().signum()==0 && q.freezeReleased()));
        verify(fixture.rewards,never()).qualify(anyLong(),any());
        assertFalse(repository.p.rewardPending);
        assertTrue(fixture.postings.isEmpty());
    }
    @Test public void paidAndReceivedZeroRemainProvisionalButSettledZeroIsExcluded() {
        zeroEvent("paid",null,OrderStatus.PAID,START);
        zeroEvent("received",OrderStatus.PAID,OrderStatus.RECEIVED,START.plusSeconds(1));
        Instant mature=START.plus(Duration.ofDays(3));
        service.unfreezeDue(mature,1);
        verify(fixture.rewards,never()).excludeInvalid(anyLong(),any());
        assertTrue(repository.p.rewardPending);
        zeroEvent("settled",OrderStatus.RECEIVED,OrderStatus.SETTLED,mature.plusSeconds(1));
        service.unfreezeDue(mature.plusSeconds(60),1);
        verify(fixture.rewards).excludeInvalid(eq(1L),any());
        verify(fixture.rewards,never()).qualify(anyLong(),any());
        assertFalse(repository.p.rewardPending);
    }
    @Test public void belowMinimumNeedsSettledProofAndDoesNotChangeSnapshot() {
        snapshot=new RewardApi.Snapshot(1,snapshot.order(),1,2L,START,false,money("200"),BigDecimal.ZERO,
            money("100"),List.of());
        when(fixture.rewards.snapshot(1)).thenReturn(snapshot);
        service.onOrderEvent(fixture.event("received",null,OrderStatus.RECEIVED,null,START));
        Instant mature=START.plus(Duration.ofDays(3));
        service.unfreezeDue(mature,1);
        verify(fixture.rewards,never()).excludeInvalid(anyLong(),any());
        var original=repository.p.terms;
        service.onOrderEvent(fixture.event("settled",OrderStatus.RECEIVED,OrderStatus.SETTLED,null,mature.plusSeconds(1)));
        service.unfreezeDue(mature.plusSeconds(60),1);
        verify(fixture.rewards).excludeInvalid(eq(1L),argThat(q -> q.paid().equals(money("100"))));
        assertSame(original,repository.p.terms);
        assertFalse(repository.p.rewardPending);
    }
    @Test public void zeroCommissionFullRefundClosesWithoutInventingPositiveRecovery() {
        zeroEvent("paid",null,OrderStatus.PAID,START);
        service.onOrderEvent(new OrderEvent("full",1L,fixture.line("0"),OrderStatus.PAID,
            OrderStatus.FULL_REFUND,null,BigDecimal.ZERO,"TEST",1L,START.plusSeconds(1)));
        // PAID -> FULL_REFUND is not a legal edge; it must NOT exclude anything.
        verify(fixture.rewards,never()).excludeInvalid(anyLong(),any());
        zeroEvent("received",OrderStatus.PAID,OrderStatus.RECEIVED,START.plusSeconds(2));
        service.onOrderEvent(new OrderEvent("full-legal",1L,fixture.line("0"),OrderStatus.RECEIVED,
            OrderStatus.FULL_REFUND,null,BigDecimal.ZERO,"TEST",1L,START.plusSeconds(3)));
        verify(fixture.rewards).excludeInvalid(eq(1L),any());
        verify(fixture.rewards,never()).refund(anyLong(),any());
        assertFalse(repository.p.rewardPending);
    }
    @Test public void discoveryBatchIsBoundedAndDisputeDoesNotQualify() {
        service.onOrderEvent(fixture.event("received",null,OrderStatus.RECEIVED,null,START));
        service.onOrderEvent(new OrderEvent("disputed",1L,fixture.line("10"),OrderStatus.RECEIVED,
            OrderStatus.DISPUTED,OrderStatus.RECEIVED,null,"TEST",1L,START.plusSeconds(1)));
        service.unfreezeDue(START.plus(Duration.ofDays(3)),1);
        verify(fixture.rewards,never()).qualify(anyLong(),any());
        assertEquals(1,repository.lastLimit);
        assertThrows(IllegalArgumentException.class,() -> service.unfreezeDue(START,1001));
    }
    @Test public void actualRewardExclusionClosesWithoutQualifyingOrAllocatingAwards() {
        com.ruoyi.system.cps.mapper.RewardProgressMapper mapper=mock(com.ruoyi.system.cps.mapper.RewardProgressMapper.class);
        when(mapper.snapshot(1)).thenReturn(com.alibaba.fastjson2.JSON.toJSONString(snapshot));
        when(mapper.state(1)).thenReturn(new com.ruoyi.system.cps.mapper.RewardProgressMapper.State(
            false,false,false,BigDecimal.ZERO,BigDecimal.ZERO));
        RewardService actual=new RewardService(mapper);
        actual.excludeInvalid(1,new RewardApi.Qualification(START,START,true,false,money("100"),BigDecimal.ZERO,BigDecimal.ZERO));
        verify(mapper).refund(1,BigDecimal.ZERO,false);
        verify(mapper,never()).qualify(anyLong());
        verify(mapper,never()).insertAward(any());
        verify(mapper,never()).instruction(anyString(),anyLong(),anyString());
    }
    private void zeroEvent(String key,OrderStatus from,OrderStatus to,Instant at) {
        service.onOrderEvent(new OrderEvent(key,1L,fixture.line("0"),from,to,null,null,"TEST",1L,at));
    }
    static class RetryMemory extends DefaultSettlementServiceBoundaryTest.Memory {
        boolean rewardComplete;
        int lastLimit;
        @Override List<Long> due(Instant now,int limit) {
            lastLimit=limit;
            List<Long> frozen=super.due(now,limit);
            if (!frozen.isEmpty()) return frozen;
            return p!=null && p.rewardPending && !rewardComplete && p.status!=OrderStatus.DISPUTED
                && p.rewardRetryAt!=null && !p.rewardRetryAt.isAfter(now) ? List.of(1L) : List.of();
        }
    }
}
