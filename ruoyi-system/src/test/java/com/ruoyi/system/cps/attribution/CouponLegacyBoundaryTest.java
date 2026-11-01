package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.IDtkApiService;
import com.ruoyi.system.service.IJdSdkService;
import com.ruoyi.system.service.IPddApiService;
import com.ruoyi.union.adapter.Platform;

public class CouponLegacyBoundaryTest {
    private final CouponConversionService conversion = mock(CouponConversionService.class);

    @Before public void requestContext() {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addParameter("userId", "999");
        request.addParameter("pid", "mm_9_9_9");
        request.addParameter("invitationCode", "forged");
        request.addHeader("X-Forwarded-For", "forged-address");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @After public void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test public void legacyTaobaoIgnoresCallerPidAndCredentialsAndUsesLoginIdentity() {
        login(7L);
        IDtkApiService catalogue = mock(IDtkApiService.class);
        when(conversion.convert(7L, "203.0.113.7", Platform.TBK, "123")).thenReturn(success(Platform.TBK, "123", "￥服务端口令￥"));
        JSONObject result = new CouponDtkApiService(catalogue, conversion).getPrivilegeLink("123", "forged-coupon",
                "mm_9_9_9", "forged-name", "forged-key", "forged-secret");
        assertEquals(0, result.getIntValue("code"));
        assertEquals("success", result.getString("msg"));
        assertEquals("￥服务端口令￥", result.getJSONObject("data").getString("tpwd"));
        assertNull(result.getJSONObject("data").get("estimatedRebate"));
        verify(conversion).convert(7L, "203.0.113.7", Platform.TBK, "123");
        verifyNoInteractions(catalogue);
    }

    @Test public void legacyJdIgnoresCallerAttributionAndGeneratesThroughCommonService() {
        login(7L);
        IJdSdkService catalogue = mock(IJdSdkService.class);
        CouponCatalogService search = mock(CouponCatalogService.class);
        when(conversion.convert(7L, "203.0.113.7", Platform.JD, "123")).thenReturn(success(Platform.JD, "123", "https://u.jd.com/server"));
        JSONObject result = new CouponJdApiService(catalogue, conversion, search).getPromotionLink(Map.of(
                "materialId", "123", "userId", "999", "pid", "forged", "subUnionId", "forged", "positionId", "999"));
        assertEquals("https://u.jd.com/server", result.getString("shortURL"));
        verify(conversion).convert(7L, "203.0.113.7", Platform.JD, "123");
        verifyNoInteractions(catalogue, search);
    }

    @Test public void legacyPddIgnoresUserIdArgumentForLinksSearchAndRecommendations() {
        login(7L);
        IPddApiService catalogue = mock(IPddApiService.class);
        when(conversion.convert(7L, "203.0.113.7", Platform.PDD, "sign_test"))
                .thenReturn(success(Platform.PDD, "sign_test", "https://p.pinduoduo.com/server"));
        CouponPddApiService service = new CouponPddApiService(catalogue, conversion);
        JSONObject result = service.generatePromotionUrl("sign_test", 999L, "forged-key", "forged-secret");
        JSONObject generated = result.getJSONObject("response").getJSONObject("goodsPromotionUrlGenerateResponse");
        assertEquals("https://p.pinduoduo.com/server", generated.getJSONArray("goodsPromotionUrlList").getJSONObject(0).getString("mobileShortUrl"));
        assertEquals("sign_test", generated.getJSONArray("goodsPromotionUrlList").getJSONObject(0).getString("goodsSign"));
        service.searchGoods("keyword", 1, 20, 999L, null, null);
        service.getRecommendGoods(1, 20, 999L, null, null);
        verify(conversion).convert(7L, "203.0.113.7", Platform.PDD, "sign_test");
        verify(catalogue).searchGoods("keyword", 1, 20, 7L, null, null);
        verify(catalogue).getRecommendGoods(1, 20, 7L, null, null);
        verify(catalogue, never()).generatePromotionUrl(anyString(), any(), any(), any());
    }

    @Test public void anonymousBrowseDoesNotAdoptIdentityFromRequestArguments() {
        IJdSdkService jd = mock(IJdSdkService.class);
        IPddApiService pdd = mock(IPddApiService.class);
        when(conversion.convert(null, "203.0.113.7", Platform.JD, "123")).thenReturn(success(Platform.JD, "123", "https://u.jd.com/default"));
        when(conversion.convert(null, "203.0.113.7", Platform.PDD, "sign_test"))
                .thenReturn(success(Platform.PDD, "sign_test", "https://p.pinduoduo.com/default"));
        new CouponJdApiService(jd, conversion, mock(CouponCatalogService.class)).getPromotionLink(Map.of("materialId", "123", "userId", "999"));
        new CouponPddApiService(pdd, conversion).generatePromotionUrl("sign_test", 999L, null, null);
        verify(conversion).convert(null, "203.0.113.7", Platform.JD, "123");
        verify(conversion).convert(null, "203.0.113.7", Platform.PDD, "sign_test");
        assertNull(CouponRequestIdentity.optionalUserId());
        assertEquals("203.0.113.7", CouponRequestIdentity.remoteAddress());
    }

    @Test public void keywordSearchIsNotSilentlyChangedToRanking() {
        IJdSdkService catalogue = mock(IJdSdkService.class);
        CouponCatalogService search = mock(CouponCatalogService.class);
        CouponJdApiService service = new CouponJdApiService(catalogue, conversion, search);
        JSONObject expected = new JSONObject();
        expected.put("resultList", new JSONArray());
        when(search.searchJd("手机", 2, 20)).thenReturn(expected);
        JSONObject result = service.queryGoodsRank(Map.of("keyword", "手机", "rankGoodsReq.pageIndex", "2", "rankGoodsReq.pageSize", "20"));
        assertSame(expected, result);
        verify(search).searchJd("手机", 2, 20);
        verify(catalogue, never()).queryGoodsRank(anyMap());
    }

    @Test public void detailRemovesKnownRawAndDisguisedCommissionsRecursivelyWithoutChangingSource() {
        JSONObject item = new JSONObject();
        item.put("goodsId", "123"); item.put("finalPrice", new BigDecimal("90.00"));
        item.put("commission", new BigDecimal("18.00")); item.put("commissionRate", new BigDecimal("20"));
        item.put("promotionRate", 200); item.put("predictPromotionRate", 0);
        item.put("zkFinalPriceWap", new BigDecimal("18.00"));
        item.put("commission_rate", "20"); item.put("promotion_rate", 200);
        JSONObject source = new JSONObject(); source.put("goodsDetails", JSONArray.of(item));
        JSONObject sanitized = CouponPresentation.detail(source);
        JSONObject visible = sanitized.getJSONArray("goodsDetails").getJSONObject(0);
        for (String field : List.of("commission", "commissionRate", "promotionRate", "predictPromotionRate", "zkFinalPriceWap", "commission_rate", "promotion_rate")) {
            assertFalse("Internal commission field remains: " + field, visible.containsKey(field));
            assertTrue("Do not mutate the shared catalogue response", item.containsKey(field));
        }
        assertEquals("123", visible.getString("goodsId"));
        assertEquals(new BigDecimal("90.00"), visible.getBigDecimal("finalPrice"));
        assertNull(sanitized.get("estimatedRebate"));
        assertEquals("PENDING_RULE_CONTRACT", sanitized.getString("rebateStatus"));
    }

    @Test public void successfulLegacyLinkDoesNotRelabelRawCommissionAndFailureHasNoLink() {
        JSONObject result = CouponPresentation.link(success(Platform.TBK, "123", "https://uland.taobao.com/server"));
        assertNull(result.get("estimatedRebate"));
        assertFalse(result.containsKey("commission"));
        assertFalse(result.containsKey("tpwd"));
        assertEquals("https://uland.taobao.com/server", result.getString("couponClickUrl"));
        CouponResult failed = new CouponResult("NO_COMMISSION", Platform.PDD, "sign_test", BigDecimal.ZERO,
                BigDecimal.TEN, null, "PENDING_RULE_CONTRACT", null, "UNATTRIBUTED", "配置的无佣金提示", List.of());
        assertEquals("配置的无佣金提示", assertThrows(ServiceException.class, () -> CouponPresentation.link(failed)).getMessage());
    }

    private static void login(Long id) {
        LoginUser login = new LoginUser(); login.setUserId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, "unused", List.of()));
    }

    private static CouponResult success(Platform platform, String item, String value) {
        return new CouponResult("SUCCESS", platform, item, BigDecimal.TEN, new BigDecimal("90.00"), null,
                "PENDING_RULE_CONTRACT", value, "ATTRIBUTION_TOKEN", "查询成功", List.of());
    }
}
