package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;

public record ReconCoverage(Long id, String allocationNo, Long receiptId, BigDecimal amount,
    String reason, Long operatorId) {}
