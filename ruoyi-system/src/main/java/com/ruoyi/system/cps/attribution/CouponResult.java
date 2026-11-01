package com.ruoyi.system.cps.attribution;

import java.math.BigDecimal;
import java.util.List;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.CouponRecommendation;

/** Alliance commission is intentionally not a public field or mislabeled as a rebate. */
public record CouponResult(String result, Platform platform, String itemId, BigDecimal couponAmount,
    BigDecimal finalPrice, BigDecimal estimatedRebate, String rebateStatus, String value,
    String bindingMode, String message, List<String> notices,
    List<CouponRecommendation> recommendations, String recommendationStatus) {
    public CouponResult(String result, Platform platform, String itemId, BigDecimal couponAmount,
            BigDecimal finalPrice, BigDecimal estimatedRebate, String rebateStatus, String value,
            String bindingMode, String message, List<String> notices) {
        this(result, platform, itemId, couponAmount, finalPrice, estimatedRebate, rebateStatus, value,
            bindingMode, message, notices, List.of(), "NOT_REQUESTED");
    }
    public boolean successful() { return "SUCCESS".equals(result); }
}
