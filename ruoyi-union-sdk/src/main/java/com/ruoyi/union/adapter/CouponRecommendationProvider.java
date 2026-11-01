package com.ruoyi.union.adapter;

import java.util.List;

/** Optional coupon enrichment; separate from the shared order/attribution adapter contract. */
public interface CouponRecommendationProvider {
    boolean supportsRecommendations();
    List<CouponRecommendation> recommend(String itemId);
}
