package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;

public record ExposureDecision(boolean blocked, boolean manualReview, String reason,
    BigDecimal current, BigDecimal limit) {}
