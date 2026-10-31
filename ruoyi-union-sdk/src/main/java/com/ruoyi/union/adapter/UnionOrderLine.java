package com.ruoyi.union.adapter;

import java.math.BigDecimal;
import java.time.Instant;
/** Raw commission values retain four decimal places; no inferred status mapping. */
public record UnionOrderLine(Platform platform, String orderNo, String subOrderNo,
    String itemId, String pid, String attributionToken, BigDecimal paidAmount,
    BigDecimal estimatedCommission, BigDecimal actualCommission, String rawStatus,
    Instant paidAt, Instant receivedAt, Instant settledAt) {}
