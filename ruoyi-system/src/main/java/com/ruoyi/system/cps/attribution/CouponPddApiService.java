package com.ruoyi.system.cps.attribution;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.service.IPddApiService;
import com.ruoyi.union.adapter.Platform;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class CouponPddApiService implements IPddApiService {
    private final IPddApiService catalogue;
    private final CouponConversionService conversion;
    public CouponPddApiService(@Qualifier("pddApiServiceImpl") IPddApiService catalogue, CouponConversionService conversion) {
        this.catalogue = catalogue; this.conversion = conversion;
    }
    @Override public JSONObject searchGoods(String keyword, Integer page, Integer pageSize, Long userId, String appKey, String appSecret) {
        return catalogue.searchGoods(keyword, page, pageSize, CouponRequestIdentity.optionalUserId(), appKey, appSecret);
    }
    @Override public JSONObject getGoodsDetail(String goodsIdList, String appKey, String appSecret) {
        return CouponPresentation.detail(catalogue.getGoodsDetail(goodsIdList, appKey, appSecret));
    }
    @Override public JSONObject getRecommendGoods(Integer page, Integer pageSize, Long userId, String appKey, String appSecret) {
        return catalogue.getRecommendGoods(page, pageSize, CouponRequestIdentity.optionalUserId(), appKey, appSecret);
    }
    @Override public JSONObject generatePromotionUrl(String goodsSign, Long userId, String appKey, String appSecret) {
        JSONObject result = CouponPresentation.link(conversion.convert(CouponRequestIdentity.optionalUserId(),
            CouponRequestIdentity.remoteAddress(), Platform.PDD, goodsSign));
        JSONObject entry = new JSONObject();
        entry.put("mobileUrl", result.getString("value")); entry.put("mobileShortUrl", result.getString("value"));
        entry.put("url", result.getString("value")); entry.put("shortUrl", result.getString("value"));
        entry.put("goodsSign", goodsSign);
        JSONObject generated = new JSONObject();
        generated.put("goodsPromotionUrlList", JSONArray.of(entry));
        JSONObject response = new JSONObject();
        response.put("goodsPromotionUrlGenerateResponse", generated);
        result.put("response", response);
        return result;
    }
    @Override public JSONObject generateRpPromUrl(Integer resourceType, String url, String appKey, String appSecret) {
        return catalogue.generateRpPromUrl(resourceType, url, appKey, appSecret);
    }
    @Override public JSONObject generateCmsPromUrl(Integer channelType, String appKey, String appSecret) {
        return catalogue.generateCmsPromUrl(channelType, appKey, appSecret);
    }
}
