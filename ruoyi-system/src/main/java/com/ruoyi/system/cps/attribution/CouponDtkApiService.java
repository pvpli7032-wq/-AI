package com.ruoyi.system.cps.attribution;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.service.IDtkApiService;
import com.ruoyi.union.adapter.Platform;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Keeps the existing service boundary; every promotion now uses the common attribution path. */
@Primary
@Service
public class CouponDtkApiService implements IDtkApiService {
    private final IDtkApiService catalogue;
    private final CouponConversionService conversion;
    public CouponDtkApiService(@Qualifier("dtkApiServiceImpl") IDtkApiService catalogue, CouponConversionService conversion) {
        this.catalogue = catalogue; this.conversion = conversion;
    }
    @Override public JSONObject getBanners() { return catalogue.getBanners(); }
    @Override public JSONObject getCategories() { return catalogue.getCategories(); }
    @Override public JSONObject searchGoods(String keyword, Long categoryId, Integer page, Integer pageSize, String sort, String appKey, String appSecret) {
        return CouponPresentation.catalogue(catalogue.searchGoods(keyword, categoryId, page, pageSize, sort, appKey, appSecret));
    }
    @Override public JSONObject getGoodsDetail(String goodsId, String appKey, String appSecret) {
        return CouponPresentation.detail(catalogue.getGoodsDetail(goodsId, appKey, appSecret));
    }
    @Override public JSONObject getHotGoods(Integer page, Integer pageSize, String keyword, String appKey, String appSecret) {
        return CouponPresentation.catalogue(catalogue.getHotGoods(page, pageSize, keyword, appKey, appSecret));
    }
    @Override public JSONObject getPrivilegeLink(String goodsId, String couponId, String pid, String goodsName, String appKey, String appSecret) {
        JSONObject link = CouponPresentation.link(conversion.convert(CouponRequestIdentity.optionalUserId(),
            CouponRequestIdentity.remoteAddress(), Platform.TBK, goodsId));
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("data", link);
        return response;
    }
}
