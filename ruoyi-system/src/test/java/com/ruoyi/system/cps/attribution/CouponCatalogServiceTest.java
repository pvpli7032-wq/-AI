package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.union.adapter.DtkCouponGateway;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.dtk.api.request.search.DtkJdUnionQueryRequest;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.search.DtkJdUnionQueryResponse;

public class CouponCatalogServiceTest {
    @Before @After public void resetIdentity() {
        SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes();
    }

    @Test public void keywordUsesSearchEndpointAndOmitsOptionalCompositePid() throws Exception {
        DtkCouponGateway gateway = mock(DtkCouponGateway.class);
        CouponRateLimiter limiter = mock(CouponRateLimiter.class);
        DtkJdUnionQueryResponse.JdCommodityInfo row = new DtkJdUnionQueryResponse.JdCommodityInfo();
        row.setSkuId("123"); row.setSkuName("手机"); row.setPrice("99.00");
        row.setCommission("20.00"); row.setCommissionShare("20");
        row.setMaterialUrl("https://item.jd.com/123.html");
        row.setImageUrlList(List.of("https://img.example.invalid/123.png"));
        DtkJdUnionQueryResponse data = new DtkJdUnionQueryResponse();
        data.setList(List.of(row)); data.setTotalNum("1");
        when(gateway.execute(eq(Platform.JD), any(DtkJdUnionQueryRequest.class)))
                .thenReturn(DtkApiResponse.buildSuccess(data));

        JSONObject result = new CouponCatalogService(gateway, limiter).searchJd(" 手机 ", 2, 20);

        ArgumentCaptor<DtkJdUnionQueryRequest> captured = ArgumentCaptor.forClass(DtkJdUnionQueryRequest.class);
        verify(gateway).execute(eq(Platform.JD), captured.capture());
        DtkJdUnionQueryRequest request = captured.getValue();
        assertEquals("手机", request.getKeyword());
        assertEquals("2", request.getPageId());
        assertEquals(Integer.valueOf(20), request.getPageSize());
        assertEquals("/dels/jd/goods/search", request.requestUrl());
        assertNull("Search PID expects unionId_appId_positionId and is optional", request.getPid());
        assertFalse(request.getTextParams().containsKey("pid"));
        verify(gateway, never()).defaultPid(any());
        verify(limiter).check(null, "local", Platform.JD);
        JSONObject product = result.getJSONArray("resultList").getJSONObject(0);
        assertEquals("123", product.getString("skuId"));
        assertEquals("99.00", product.getString("price"));
        assertNull(product.get("estimatedRebate"));
        assertEquals("PENDING_RULE_CONTRACT", product.getString("rebateStatus"));
        assertFalse(product.containsKey("commission"));
        assertFalse(product.containsKey("commissionShare"));
        assertFalse(product.containsKey("materialUrl"));
    }
}
