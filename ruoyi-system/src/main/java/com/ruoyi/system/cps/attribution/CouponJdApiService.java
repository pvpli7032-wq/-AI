package com.ruoyi.system.cps.attribution;

import java.util.Map;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.service.IJdSdkService;
import com.ruoyi.union.adapter.Platform;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class CouponJdApiService implements IJdSdkService {
    private final IJdSdkService catalogue;
    private final CouponConversionService conversion;
    private final CouponCatalogService search;
    public CouponJdApiService(@Qualifier("jdSdkService") IJdSdkService catalogue, CouponConversionService conversion, CouponCatalogService search) {
        this.catalogue = catalogue; this.conversion = conversion; this.search = search;
    }
    @Override public JSONObject queryGoodsRank(Map<String, String> params) {
        String keyword = params.get("keyword");
        return keyword == null || keyword.isBlank() ? catalogue.queryGoodsRank(params) : search.searchJd(keyword,
            Integer.parseInt(params.getOrDefault("rankGoodsReq.pageIndex", "1")),
            Integer.parseInt(params.getOrDefault("rankGoodsReq.pageSize", "20")));
    }
    @Override public JSONObject queryGoodsBigField(String skuIds) { return CouponPresentation.detail(catalogue.queryGoodsBigField(skuIds)); }
    @Override public JSONObject getPromotionLink(Map<String, String> params) {
        return CouponPresentation.link(conversion.convert(CouponRequestIdentity.optionalUserId(),
            CouponRequestIdentity.remoteAddress(), Platform.JD, params.get("materialId")));
    }
    @Override public JSONObject queryCoupon(String couponUrls) { return catalogue.queryCoupon(couponUrls); }
}
