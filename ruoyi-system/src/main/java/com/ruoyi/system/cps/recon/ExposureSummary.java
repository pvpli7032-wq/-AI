package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** User debt is conservatively reserved separately until finance can allocate it to funded orders. */
public record ExposureSummary(BigDecimal current, BigDecimal peak, BigDecimal knownAmount, BigDecimal debtAmount,
    List<ExposurePlatform> platforms, Instant observedAt) {}
