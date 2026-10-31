package com.ruoyi.system.cps.order;

import java.time.Instant;
import com.ruoyi.union.adapter.UnionOrderLine;
public record OrderEvent(Long orderLineId, UnionOrderLine line, OrderStatus fromStatus,
    OrderStatus toStatus, String source, Long rawId, Instant occurredAt) {}
