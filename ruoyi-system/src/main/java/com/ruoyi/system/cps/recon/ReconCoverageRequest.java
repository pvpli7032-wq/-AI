package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;

/** Finance confirms the part of one actual receipt that covers an order's user liability. */
public record ReconCoverageRequest(String allocationNo, Long receiptId, BigDecimal amount, String reason) {}
