package com.ruoyi.system.cps.attribution;

import java.util.List;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.union.adapter.DtkCouponGateway;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.dtk.api.request.search.DtkJdUnionQueryRequest;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.search.DtkJdUnionQueryResponse;
import org.springframework.stereotype.Service;

/** DTK has a real keyword search endpoint; a keyword is never silently replaced by a hot list. */
@Service
public class CouponCatalogService {
    private final DtkCouponGateway gateway;
    private final CouponRateLimiter limiter;
    public CouponCatalogService(DtkCouponGateway gateway, CouponRateLimiter limiter) { this.gateway = gateway; this.limiter = limiter; }
    public JSONObject searchJd(String keyword, int page, int pageSize) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 200 || page < 1 || pageSize < 1 || pageSize > 200) {
            throw new ServiceException("搜索参数无效");
        }
        limiter.check(CouponRequestIdentity.optionalUserId(), CouponRequestIdentity.remoteAddress(), Platform.JD);
        DtkJdUnionQueryRequest request = new DtkJdUnionQueryRequest();
        request.setKeyword(keyword.trim()); request.setPageId(Integer.toString(page)); request.setPageSize(pageSize);
        // Search pid is optional and expects unionId_appId_positionId, whereas the
        // promotion pool stores a numeric positionId. Search never assigns attribution.
        DtkApiResponse<DtkJdUnionQueryResponse> response = gateway.execute(Platform.JD, request);
        if (response == null || !response.isSuccess() || response.getData() == null) throw new ServiceException("商品搜索暂不可用");
        JSONArray items = new JSONArray();
        for (var row : response.getData().getList() == null ? List.<DtkJdUnionQueryResponse.JdCommodityInfo>of() : response.getData().getList()) {
            JSONObject item = new JSONObject();
            item.put("skuId", row.getSkuId()); item.put("skuName", row.getSkuName()); item.put("price", row.getPrice());
            item.put("shopName", row.getShopName()); item.put("owner", row.getOwner()); item.put("inOrderCount30Days", row.getInOrderCount30Days());
            if (row.getImageUrlList() != null && !row.getImageUrlList().isEmpty()) item.put("imageUrl", row.getImageUrlList().get(0));
            item.put("estimatedRebate", null); item.put("rebateStatus", "PENDING_RULE_CONTRACT"); items.add(item);
        }
        JSONObject result = new JSONObject(); result.put("resultList", items); result.put("totalNum", response.getData().getTotalNum());
        return result;
    }
}
