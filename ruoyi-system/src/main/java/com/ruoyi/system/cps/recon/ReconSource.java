package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReconSource(Long id, String platform, String sourceType, String externalId,
    String orderNo, String subOrderNo, String withdrawNo, BigDecimal amount,
    BigDecimal expectedReceiptAmount, LocalDate occurredOn, Long operatorId) {}
