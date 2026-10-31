package com.ruoyi.union.adapter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Normalized order data and optional upstream evidence.
 * rawResponseJson is the exact received response text, not a reserialized DTO.
 * allianceStatusTime is the upstream status-change time, never a fetch-time fallback.
 * Null evidence means unavailable; downstream ingestion must not invent it.
 */
public record UnionOrderLine(Platform platform, String orderNo, String subOrderNo,
    String itemId, String pid, String attributionToken, BigDecimal paidAmount,
    BigDecimal estimatedCommission, BigDecimal actualCommission, String rawStatus,
    Instant paidAt, Instant receivedAt, Instant settledAt,
    String rawResponseJson, Instant allianceStatusTime)
{
    /** Retains the original constructor descriptor for already compiled callers. */
    public UnionOrderLine(Platform platform, String orderNo, String subOrderNo,
        String itemId, String pid, String attributionToken, BigDecimal paidAmount,
        BigDecimal estimatedCommission, BigDecimal actualCommission, String rawStatus,
        Instant paidAt, Instant receivedAt, Instant settledAt)
    {
        this(platform, orderNo, subOrderNo, itemId, pid, attributionToken, paidAmount,
            estimatedCommission, actualCommission, rawStatus, paidAt, receivedAt, settledAt, null, null);
    }

    /** Raw responses and attribution tokens must not appear in ordinary logs. */
    @Override
    public String toString()
    {
        return "UnionOrderLine[platform=" + platform + ", orderNo=" + orderNo
            + ", subOrderNo=" + subOrderNo + ", rawStatus=" + rawStatus
            + ", rawResponseJson=<redacted>, attributionToken=<redacted>, allianceStatusTime="
            + allianceStatusTime + "]";
    }
}
