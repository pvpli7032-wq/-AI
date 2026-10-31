package com.ruoyi.system.cps.settlement;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.contract.RebateStatus;
import com.ruoyi.system.cps.contract.StatusTransitions;
import com.ruoyi.system.cps.ledger.LedgerService;
import com.ruoyi.system.cps.rule.RewardApi;
import com.ruoyi.system.cps.rule.RewardService;
import com.ruoyi.system.cps.settlement.SettlementRepository.Item;
import com.ruoyi.system.cps.settlement.SettlementRepository.Position;
import com.ruoyi.system.cps.settlement.SettlementTermsPort.Benefit;
import com.ruoyi.system.cps.settlement.SettlementTermsPort.Terms;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Unit boundary regressions only; mocks do not prove SQL locking, persistence or database rollback. */
public class SettlementSafetyHoldTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private SettlementRepository repository;
    private LedgerService ledger;
    private SettlementExternalServices external;
    private RewardService rewards;
    private PlatformTransactionManager transactions;
    private DefaultSettlementService service;
    private Position position;
    private List<Item> items;

    @Before public void setup() {
        repository = mock(SettlementRepository.class);
        ledger = mock(LedgerService.class);
        external = mock(SettlementExternalServices.class);
        rewards = mock(RewardService.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());

        Terms original = new Terms(List.of(
            new Benefit("REBATE", 1L, money("5"), 7L),
            new Benefit("REFERRAL", 2L, money("2"), 8L)),
            money("100"), "unit-freeze-policy", money("3"), "{\"unitSnapshot\":true}");
        position = new Position();
        position.id = 1L;
        position.buyer = 1L;
        position.status = OrderStatus.RECEIVED;
        position.commission = money("10");
        position.reportedCommission = money("10");
        position.recovered = money("0");
        position.lastEventAt = START;
        position.terms = original;
        position.actualTerms = original;
        items = List.of(item(1L, original.benefits().get(0)), item(2L, original.benefits().get(1)));
        when(repository.lockOrder(1L)).thenReturn(Map.of(
            "order_id", "order", "sub_order_no", "line", "platform_type", "tbk", "buyer_user_id", 1L));
        when(repository.load(1L)).thenReturn(position);
        when(repository.items(1L)).thenReturn(items);

        SettlementTermsPort terms = mock(SettlementTermsPort.class);
        when(terms.recalculate(eq(original), any())).thenAnswer(call -> {
            UnionOrderLine line = call.getArgument(1);
            BigDecimal ratio = line.actualCommission().divide(money("10"));
            return new Terms(original.benefits().stream().map(b -> new Benefit(
                b.type(), b.beneficiaryUserId(), b.amount().multiply(ratio), b.ruleVersionId())).toList(),
                original.maxCommission(), original.freezePolicy(), original.platformAmount().multiply(ratio),
                original.ruleSnapshotJson());
        });
        service = new DefaultSettlementService(repository, ledger, provider(terms),
            provider(mock(SettlementFreezePort.class)), external, rewards,
            provider(mock(SettlementRewardTermsPort.class)), transactions);
    }

    @Test public void illegalDisputeAfterPartialRefundHoldsFrozenRemainderWithoutOrderEdge() {
        assertFalse(StatusTransitions.ORDER.getOrDefault(OrderStatus.PARTIAL_REFUND, Set.of())
            .contains(OrderStatus.DISPUTED));
        position.status = OrderStatus.PARTIAL_REFUND;
        position.recovered = money("5");
        for (Item item : items) {
            item.recovered = item.original.divide(money("2"));
            item.status = RebateStatus.PARTIALLY_RECOVERED;
        }
        Instant disputedAt = START.plusSeconds(60);
        service.onOrderEvent(event("illegal-dispute", OrderStatus.PARTIAL_REFUND,
            OrderStatus.DISPUTED, "10", disputedAt));

        assertEquals(OrderStatus.PARTIAL_REFUND, position.status);
        assertNull(position.previous);
        assertEquals(START, position.lastEventAt);
        assertEquals(money("5"), position.recovered);
        for (Item item : items) {
            assertEquals("FROZEN", item.phase);
            assertEquals(RebateStatus.PAUSED, item.status);
            assertEquals(disputedAt, item.paused);
            assertTrue(item.clock.paused());
            assertEquals(Duration.ofDays(2).minusSeconds(60), item.clock.remaining());
            assertEquals(START.plus(Duration.ofDays(2)), item.due);
            assertEquals(item.original.divide(money("2")), item.recovered);
            verify(repository).save(item);
        }
        verify(repository).manual(1L, "illegal-dispute", "ILLEGAL_ORDER_TRANSITION_OR_DISPUTE_RESTORATION");
        verify(repository, never()).save(any(Position.class));
        verify(repository, never()).order(any(Position.class));
        verify(repository, never()).receipt(anyString(), anyLong(), anyString());

        // Force discovery despite the hold (e.g. another due work source). Release must still refuse it.
        Instant mature = START.plus(Duration.ofDays(10));
        when(repository.due(mature, 10)).thenReturn(List.of(1L));
        assertEquals(1, service.unfreezeDue(mature, 10));
        assertEquals(OrderStatus.PARTIAL_REFUND, position.status);
        for (Item item : items) {
            assertEquals("FROZEN", item.phase);
            assertEquals(RebateStatus.PAUSED, item.status);
            assertEquals(disputedAt, item.paused);
        }
        verifyNoInteractions(ledger, external, rewards);
    }

    @Test public void frozenIncreasePropagatesRewardGuardBeforeOrdinaryMutations() {
        assertRepricingHeld("20", false);
    }

    @Test public void frozenDecreasePropagatesRewardGuardBeforeOrdinaryMutations() {
        assertRepricingHeld("6", false);
    }

    @Test public void creditedIncreasePropagatesRewardGuardBeforeOrdinaryMutations() {
        assertRepricingHeld("20", true);
    }

    @Test public void creditedDecreasePropagatesRewardGuardBeforeOrdinaryMutations() {
        assertRepricingHeld("6", true);
    }

    private void assertRepricingHeld(String actual, boolean released) {
        if (released) for (Item item : items) {
            item.phase = "AVAILABLE";
            item.status = RebateStatus.CREDITED;
        }
        Terms priorActualTerms = position.actualTerms;
        Instant observedAt = START.plus(Duration.ofDays(3));
        IllegalStateException guard = new IllegalStateException(
            "reward repricing requires append-only basis reconciliation; manual review required");
        when(rewards.reprice(eq(1L), any())).thenThrow(guard);

        assertSame(guard, assertThrows(IllegalStateException.class, () -> service.onOrderEvent(
            event("reprice-guard", OrderStatus.RECEIVED, OrderStatus.SETTLED, actual, observedAt))));

        ArgumentCaptor<RewardApi.Qualification> evidence = ArgumentCaptor.forClass(RewardApi.Qualification.class);
        verify(rewards).reprice(eq(1L), evidence.capture());
        assertEquals(observedAt, evidence.getValue().now());
        assertEquals(START.plus(Duration.ofDays(2)), evidence.getValue().freezeUntil());
        assertEquals(released, evidence.getValue().freezeReleased());
        assertFalse(evidence.getValue().pausedOrRiskHeld());
        assertEquals(money("100"), evidence.getValue().paid());
        assertEquals(money(actual), evidence.getValue().commission());
        assertEquals(0, money(actual).divide(money("2")).compareTo(evidence.getValue().userRebate()));
        assertEquals(OrderStatus.RECEIVED, position.status);
        assertEquals(START, position.lastEventAt);
        assertEquals(money("10"), position.commission);
        assertEquals(money("10"), position.reportedCommission);
        assertSame(priorActualTerms, position.actualTerms);
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            assertEquals(position.terms.benefits().get(index).amount(), item.original);
            assertEquals(money("0"), item.recovered);
            assertEquals(0, item.adjustment.signum());
            assertEquals(released ? "AVAILABLE" : "FROZEN", item.phase);
            assertEquals(released ? RebateStatus.CREDITED : RebateStatus.FROZEN, item.status);
            assertNull(item.paused);
        }
        verify(repository, never()).save(any(Item.class));
        verify(repository, never()).save(any(Position.class));
        verify(repository, never()).order(any(Position.class));
        verify(repository, never()).receipt(anyString(), anyLong(), anyString());
        verify(repository, never()).manual(anyLong(), anyString(), anyString());
        verifyNoInteractions(ledger, external);
        verifyNoMoreInteractions(rewards);
        // TransactionTemplate requests rollback; this is not a claim about real database rollback.
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    private static Item item(long id, Benefit benefit) {
        Item item = new Item();
        item.id = id;
        item.user = benefit.beneficiaryUserId();
        item.type = benefit.type();
        item.version = benefit.ruleVersionId();
        item.original = benefit.amount();
        item.recovered = money("0");
        item.phase = "FROZEN";
        item.status = RebateStatus.FROZEN;
        item.clock = FreezeClock.start(START, Duration.ofDays(2));
        item.due = item.clock.dueAt().orElseThrow();
        return item;
    }

    private static OrderEvent event(String key, OrderStatus from, OrderStatus to, String actual, Instant at) {
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, "order", "line", "item", "pid", null,
            money("100"), money("10"), money(actual), "unit-raw", START.minusSeconds(100000), START, at);
        return new OrderEvent(key, 1L, line, from, to, null, null, "UNIT", 1L, at);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
