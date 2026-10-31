package com.ruoyi.system.cps.contract;

import java.util.Map;
import java.util.Set;
/** The allowed transition table; DISPUTED may restore only its recorded previous state. */
public final class StatusTransitions {
    private StatusTransitions() {}
    public static final Map<OrderStatus, Set<OrderStatus>> ORDER = Map.of(
        OrderStatus.PAID, Set.of(OrderStatus.RECEIVED, OrderStatus.SETTLED, OrderStatus.INVALID),
        OrderStatus.RECEIVED, Set.of(OrderStatus.SETTLED, OrderStatus.DISPUTED, OrderStatus.PENALIZED, OrderStatus.PARTIAL_REFUND, OrderStatus.FULL_REFUND),
        OrderStatus.SETTLED, Set.of(OrderStatus.DISPUTED, OrderStatus.PENALIZED, OrderStatus.PARTIAL_REFUND, OrderStatus.FULL_REFUND),
        OrderStatus.DISPUTED, Set.of(OrderStatus.RECEIVED, OrderStatus.SETTLED, OrderStatus.PARTIAL_REFUND, OrderStatus.FULL_REFUND));
    public static final Map<RebateStatus, Set<RebateStatus>> REBATE = Map.of(
        RebateStatus.ESTIMATED, Set.of(RebateStatus.FROZEN, RebateStatus.VOID),
        RebateStatus.FROZEN, Set.of(RebateStatus.CREDITED, RebateStatus.PAUSED, RebateStatus.PARTIALLY_RECOVERED, RebateStatus.VOID),
        RebateStatus.PAUSED, Set.of(RebateStatus.FROZEN, RebateStatus.VOID, RebateStatus.PARTIALLY_RECOVERED),
        RebateStatus.CREDITED, Set.of(RebateStatus.PARTIALLY_RECOVERED, RebateStatus.FULLY_RECOVERED),
        RebateStatus.PARTIALLY_RECOVERED, Set.of(RebateStatus.PARTIALLY_RECOVERED, RebateStatus.FULLY_RECOVERED, RebateStatus.PAUSED, RebateStatus.CREDITED, RebateStatus.VOID));
}
