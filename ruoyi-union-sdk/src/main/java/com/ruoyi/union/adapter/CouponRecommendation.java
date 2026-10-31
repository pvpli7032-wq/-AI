package com.ruoyi.union.adapter;

import java.math.BigDecimal;

/** Product facts only. RELATED is not a claim that the item is an identical SKU. */
public record CouponRecommendation(Platform platform, String itemId, String title,
        BigDecimal finalPrice, BigDecimal couponAmount, String matchType) {
    public CouponRecommendation(Platform platform, String itemId, String title,
            BigDecimal finalPrice, BigDecimal couponAmount) {
        this(platform, itemId, title, finalPrice, couponAmount, "RELATED");
    }

    public CouponRecommendation {
        if (!"RELATED".equals(matchType)) throw new IllegalArgumentException("Only related-product recommendations are supported");
    }
}
