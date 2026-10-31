package com.ruoyi.system.cps.attribution;

import java.math.BigDecimal;
import java.time.Instant;

/** No raw pasted text, promotion token, credentials or full URLs are retained. */
public record CouponQueryLog(Long userId, String platform, String rawHash, String itemId,
    String result, BigDecimal commissionRate, BigDecimal shownRebate, String pid, Instant createdAt) {}
