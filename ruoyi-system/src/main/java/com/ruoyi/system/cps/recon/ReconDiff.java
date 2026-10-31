package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;

public record ReconDiff(Long id, Long batchId, String type, String refId, BigDecimal amount,
    String status, Long resolvedBy, String detail, String resolveNote) {}
