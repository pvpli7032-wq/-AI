package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;

/** Withdrawals move existing liabilities and must never count the same order twice. */
public record ExposurePosition(String platform, Long orderLineId, String orderNo, String subOrderNo,
    BigDecimal credited, BigDecimal covered, BigDecimal unallocatedCash, BigDecimal exposure) {}
