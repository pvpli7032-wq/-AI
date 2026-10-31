package com.ruoyi.system.cps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.contract.RebateStatus;
import com.ruoyi.system.cps.contract.StatusTransitions;
import org.junit.Test;

/** Independent contract expectations, including every forbidden pair and terminal state. */
public class StatusTransitionsMatrixTest {
    @Test
    public void completeOrderMatrixPreservesAllRetainedEdgesAndTerminalStates() {
        Map<OrderStatus, Set<OrderStatus>> expected = Map.of(
            OrderStatus.PAID, Set.of(OrderStatus.RECEIVED, OrderStatus.SETTLED, OrderStatus.INVALID),
            OrderStatus.RECEIVED, Set.of(OrderStatus.SETTLED, OrderStatus.DISPUTED,
                OrderStatus.PARTIAL_REFUND, OrderStatus.FULL_REFUND, OrderStatus.PENALIZED),
            OrderStatus.SETTLED, Set.of(OrderStatus.DISPUTED, OrderStatus.PARTIAL_REFUND,
                OrderStatus.FULL_REFUND, OrderStatus.PENALIZED),
            OrderStatus.DISPUTED, Set.of(OrderStatus.RECEIVED, OrderStatus.SETTLED,
                OrderStatus.PARTIAL_REFUND, OrderStatus.FULL_REFUND),
            OrderStatus.INVALID, Set.of(),
            OrderStatus.PARTIAL_REFUND, Set.of(),
            OrderStatus.FULL_REFUND, Set.of(),
            OrderStatus.PENALIZED, Set.of());
        assertMatrix(OrderStatus.values(), expected, StatusTransitions.ORDER);
    }

    @Test
    public void completeRebateMatrixPreservesAllRetainedEdgesAndTerminalStates() {
        Map<RebateStatus, Set<RebateStatus>> expected = Map.of(
            RebateStatus.ESTIMATED, Set.of(RebateStatus.FROZEN, RebateStatus.VOID),
            RebateStatus.FROZEN, Set.of(RebateStatus.CREDITED, RebateStatus.PAUSED,
                RebateStatus.VOID, RebateStatus.PARTIALLY_RECOVERED),
            RebateStatus.PAUSED, Set.of(RebateStatus.FROZEN, RebateStatus.VOID,
                RebateStatus.PARTIALLY_RECOVERED),
            RebateStatus.CREDITED, Set.of(RebateStatus.PARTIALLY_RECOVERED, RebateStatus.FULLY_RECOVERED),
            RebateStatus.PARTIALLY_RECOVERED, Set.of(RebateStatus.PAUSED, RebateStatus.CREDITED,
                RebateStatus.VOID, RebateStatus.PARTIALLY_RECOVERED, RebateStatus.FULLY_RECOVERED),
            RebateStatus.VOID, Set.of(),
            RebateStatus.FULLY_RECOVERED, Set.of());
        assertMatrix(RebateStatus.values(), expected, StatusTransitions.REBATE);
    }

    @Test
    public void directSettlementCanReachRefundButPaidCannotSkipToRefund() {
        for (OrderStatus refund : new OrderStatus[] {OrderStatus.PARTIAL_REFUND, OrderStatus.FULL_REFUND}) {
            assertPath(StatusTransitions.ORDER, OrderStatus.PAID, OrderStatus.SETTLED, refund);
            assertPath(StatusTransitions.ORDER, OrderStatus.PAID, OrderStatus.RECEIVED, refund);
            assertFalse(StatusTransitions.ORDER.get(OrderStatus.PAID).contains(refund));
            // Both refund outcomes are terminal, not merely forbidden to restore PAID.
            for (OrderStatus next : OrderStatus.values()) {
                assertFalse(StatusTransitions.ORDER.getOrDefault(refund, Set.of()).contains(next));
            }
        }
    }

    @Test
    public void frozenAndPausedFullRefundsVoidInsteadOfRecoveringCreditedFunds() {
        assertPath(StatusTransitions.REBATE, RebateStatus.ESTIMATED, RebateStatus.FROZEN, RebateStatus.VOID);
        assertPath(StatusTransitions.REBATE, RebateStatus.ESTIMATED, RebateStatus.FROZEN,
            RebateStatus.PAUSED, RebateStatus.VOID);
        for (RebateStatus current : new RebateStatus[] {RebateStatus.FROZEN, RebateStatus.PAUSED}) {
            assertFalse(StatusTransitions.REBATE.get(current).contains(RebateStatus.FULLY_RECOVERED));
        }
    }

    @Test
    public void partialRecoveryRetainsFrozenRemainderAndCreditedRecoveryPaths() {
        assertPath(StatusTransitions.REBATE, RebateStatus.FROZEN, RebateStatus.PARTIALLY_RECOVERED,
            RebateStatus.PAUSED, RebateStatus.PARTIALLY_RECOVERED, RebateStatus.CREDITED);
        assertPath(StatusTransitions.REBATE, RebateStatus.PAUSED, RebateStatus.PARTIALLY_RECOVERED,
            RebateStatus.VOID);
        assertPath(StatusTransitions.REBATE, RebateStatus.CREDITED, RebateStatus.PARTIALLY_RECOVERED,
            RebateStatus.PARTIALLY_RECOVERED, RebateStatus.FULLY_RECOVERED);
        assertPath(StatusTransitions.REBATE, RebateStatus.CREDITED, RebateStatus.FULLY_RECOVERED);
        assertFalse(StatusTransitions.REBATE.get(RebateStatus.PARTIALLY_RECOVERED).contains(RebateStatus.FROZEN));
        assertFalse(StatusTransitions.REBATE.get(RebateStatus.CREDITED).contains(RebateStatus.PAUSED));
        // The status table alone cannot establish which monetary stage the remainder is in.
    }

    private static <E extends Enum<E>> void assertMatrix(E[] states, Map<E, Set<E>> expected,
                                                       Map<E, Set<E>> actual) {
        assertEquals("Expectations cover every enum constant", Set.of(states), expected.keySet());
        for (E from : states) {
            Set<E> targets = expected.get(from);
            assertEquals("Only nonterminal states have outgoing entries: " + from,
                !targets.isEmpty(), actual.containsKey(from));
            for (E to : states) {
                assertEquals(from + " -> " + to, targets.contains(to),
                    actual.getOrDefault(from, Set.of()).contains(to));
            }
        }
    }

    @SafeVarargs
    private static <E extends Enum<E>> void assertPath(Map<E, Set<E>> transitions, E... states) {
        for (int i = 1; i < states.length; i++) {
            assertTrue(states[i - 1] + " -> " + states[i],
                transitions.getOrDefault(states[i - 1], Set.of()).contains(states[i]));
        }
    }
}
