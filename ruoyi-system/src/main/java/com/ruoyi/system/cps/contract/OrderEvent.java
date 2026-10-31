package com.ruoyi.system.cps.contract;

import java.math.BigDecimal;
import java.time.Instant;
import com.ruoyi.union.adapter.UnionOrderLine;

/**
 * eventKey is stable across redelivery. recoveredAmount is null when no amount is
 * recovered; for a penalty it records the actual partial or full recovery.
 * When entering DISPUTED, previousStatus records the pre-dispute state. On
 * resolution back from DISPUTED it must match the persisted pre-dispute state
 * and toStatus must equal it; refund outcomes need not restore that state.
 */
public record OrderEvent(String eventKey, Long orderLineId, UnionOrderLine line,
    OrderStatus fromStatus, OrderStatus toStatus, OrderStatus previousStatus,
    BigDecimal recoveredAmount, String source, Long rawId, Instant occurredAt) {}
