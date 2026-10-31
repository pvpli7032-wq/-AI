package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable source delta. Commission and net cash due are deliberately distinct.
 * externalId is a source-account-namespaced evidence line identifier. Cash identifiers
 * are unique across platforms within UNION_RECEIPT or PAYOUT; a correction needs a new id.
 */
public record ReconImportRequest(String platform, String sourceType, String externalId,
    String orderNo, String subOrderNo, String withdrawNo, BigDecimal amount,
    BigDecimal expectedReceiptAmount, LocalDate occurredOn) {}
