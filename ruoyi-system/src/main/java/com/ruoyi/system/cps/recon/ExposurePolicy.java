package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;

/** A null limit is returned only while finance has not configured the ceiling. */
public record ExposurePolicy(BigDecimal limit, ExposureLimitAction action, int intervalMinutes) { }
