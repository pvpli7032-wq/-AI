package com.ruoyi.system.cps;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import java.util.Set;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.contract.RebateStatus;
import com.ruoyi.system.cps.contract.StatusTransitions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Each row exercises one newly allowed, removed, or explicitly forbidden path. */
@RunWith(Parameterized.class)
public class StatusTransitionsTest {
    @Parameterized.Parameters(name = "{index}: {0} -> {1}, allowed={2}")
    public static Object[][] transitions() {
        return new Object[][] {
            {OrderStatus.PAID, OrderStatus.SETTLED, true},
            {OrderStatus.RECEIVED, OrderStatus.PARTIAL_REFUND, true},
            {OrderStatus.RECEIVED, OrderStatus.FULL_REFUND, true},
            {OrderStatus.SETTLED, OrderStatus.PARTIAL_REFUND, true},
            {OrderStatus.SETTLED, OrderStatus.FULL_REFUND, true},
            {RebateStatus.FROZEN, RebateStatus.VOID, true},
            {RebateStatus.PAUSED, RebateStatus.VOID, true},
            {RebateStatus.PAUSED, RebateStatus.PARTIALLY_RECOVERED, true},
            {RebateStatus.PARTIALLY_RECOVERED, RebateStatus.PAUSED, true},
            {RebateStatus.PARTIALLY_RECOVERED, RebateStatus.CREDITED, true},
            {RebateStatus.PARTIALLY_RECOVERED, RebateStatus.VOID, true},
            {RebateStatus.FROZEN, RebateStatus.FULLY_RECOVERED, false},
            {RebateStatus.PAUSED, RebateStatus.FULLY_RECOVERED, false},
            {RebateStatus.PARTIALLY_RECOVERED, RebateStatus.FROZEN, false},
            {RebateStatus.CREDITED, RebateStatus.PAUSED, false},
            {OrderStatus.PAID, OrderStatus.DISPUTED, false}
        };
    }

    private final Enum<?> from;
    private final Enum<?> to;
    private final boolean allowed;

    public StatusTransitionsTest(Enum<?> from, Enum<?> to, boolean allowed) {
        this.from = from;
        this.to = to;
        this.allowed = allowed;
    }

    @Test
    public void transitionMatchesAuthorizedContract() {
        Map<?, ? extends Set<?>> transitions = from instanceof OrderStatus
            ? StatusTransitions.ORDER : StatusTransitions.REBATE;
        Set<?> targets = transitions.get(from);
        assertEquals(from + " -> " + to, allowed, targets != null && targets.contains(to));
    }
}
