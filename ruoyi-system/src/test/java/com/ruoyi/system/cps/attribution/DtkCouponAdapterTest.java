package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.union.adapter.*;
import com.ruoyi.union.dtk.api.client.DtkApiRequest;
import com.ruoyi.union.dtk.api.request.mastertool.*;
import com.ruoyi.union.dtk.api.request.putstorage.*;
import com.ruoyi.union.dtk.api.request.special.DtkListSimilerGoodsByOpenRequest;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.mastertool.*;
import com.ruoyi.union.dtk.api.response.putstorage.*;
import com.ruoyi.union.dtk.api.response.special.DtkListSimilerGoodsByOpenResponse;

public class DtkCouponAdapterTest {
    @Test public void taobaoParserPreservesEncryptedIdentityAndUsesTokenFallback() {
        FakeGateway gateway = new FakeGateway();
        gateway.respond(DtkParseContentRequest.class, DtkApiResponse.buildFail(1, "unavailable"));
        DtkParseContentResponse data = new DtkParseContentResponse();
        data.setGoodsId("encrypted_Item_123-abc");
        gateway.respond(DtkParseTaokoulingRequest.class, DtkApiResponse.buildSuccess(data));
        ParsedItem parsed = adapter(Platform.TBK, gateway).parse("￥测试口令￥");
        assertEquals(Platform.TBK, parsed.platform());
        assertEquals("encrypted_Item_123-abc", parsed.itemId());
        assertEquals(2, gateway.requests.size());
        assertEquals("￥测试口令￥", ((DtkParseTaokoulingRequest) gateway.requests.get(1)).getContent());
    }

    @Test public void taobaoQuoteUsesFractionalRateAndRmbWithoutRoundingUp() {
        FakeGateway gateway = new FakeGateway();
        DtkGoodsDetailsResponse data = new DtkGoodsDetailsResponse();
        data.setGoodsId("123");
        data.setCommissionRate(new BigDecimal("12.3456"));
        data.setCouponPrice(new BigDecimal("10.00"));
        data.setActualPrice(new BigDecimal("89.99"));
        gateway.respond(DtkGoodsDetailsRequest.class, DtkApiResponse.buildSuccess(data));
        CommissionQuote quote = adapter(Platform.TBK, gateway).quote("123");
        assertEquals(new BigDecimal("0.123456"), quote.rate());
        assertEquals(new BigDecimal("10.00"), quote.couponAmount());
        assertEquals(new BigDecimal("89.99"), quote.finalPrice());
    }

    @Test public void nonCatalogueTaobaoQuoteUsesDefaultPidAndExplicitCouponAmount() {
        FakeGateway gateway = new FakeGateway();
        gateway.respond(DtkGoodsDetailsRequest.class, DtkApiResponse.buildFail(1, "not in catalogue"));
        DtkGetPrivilegeLinkResponse privilege = new DtkGetPrivilegeLinkResponse();
        privilege.setMaxCommissionRate("20");
        privilege.setActualPrice("80");
        privilege.setOriginalPrice("120");
        privilege.setCouponClickUrl("https://uland.taobao.com/coupon/edetail?e=test");
        gateway.respond(DtkGetPrivilegeLinkRequest.class, DtkApiResponse.buildSuccess(privilege));
        DtkCouponQueryResponse coupon = new DtkCouponQueryResponse();
        coupon.setCouponAmount(new BigDecimal("10"));
        gateway.respond(DtkCouponQueryRequest.class, DtkApiResponse.buildSuccess(coupon));
        CommissionQuote quote = adapter(Platform.TBK, gateway).quote("123");
        assertEquals(new BigDecimal("0.20"), quote.rate());
        // The original/actual difference may include other discounts and is not the coupon.
        assertEquals(new BigDecimal("10"), quote.couponAmount());
        assertEquals("mm_111_222_333", ((DtkGetPrivilegeLinkRequest) gateway.requests.get(1)).getPid());
    }

    @Test public void taobaoConversionUsesOnlyServerPidAndNeverRelationId() {
        FakeGateway gateway = new FakeGateway();
        DtkGetPrivilegeLinkResponse data = new DtkGetPrivilegeLinkResponse();
        data.setCouponClickUrl("https://uland.taobao.com/coupon/edetail?e=server-generated");
        gateway.respond(DtkGetPrivilegeLinkRequest.class, DtkApiResponse.buildSuccess(data));
        ConvertedLink result = adapter(Platform.TBK, gateway).convert("123", "mm_444_555_666");
        DtkGetPrivilegeLinkRequest sent = (DtkGetPrivilegeLinkRequest) gateway.requests.get(0);
        assertEquals("mm_444_555_666", sent.getPid());
        assertNull(sent.getChannelId());
        assertNull(sent.getExternalId());
        assertEquals(data.getCouponClickUrl(), result.value());
        assertThrows(UnionCouponException.class, () -> adapter(Platform.TBK, gateway).convert("123", "pid=someone_else"));
        assertEquals(1, gateway.requests.size());
    }

    @Test public void taobaoConversionPrefersProviderPasswordBeforeUrlFallback() {
        FakeGateway gateway = new FakeGateway();
        DtkGetPrivilegeLinkResponse data = new DtkGetPrivilegeLinkResponse();
        data.setLongTpwd("￥测试长口令￥");
        data.setTpwd("￥测试短口令￥");
        data.setCouponClickUrl("https://uland.taobao.com/coupon/edetail?e=server-generated");
        gateway.respond(DtkGetPrivilegeLinkRequest.class, DtkApiResponse.buildSuccess(data));
        UnionAdapter adapter = adapter(Platform.TBK, gateway);
        assertEquals(data.getLongTpwd(), adapter.convert("123", "mm_444_555_666").value());
        data.setLongTpwd(null);
        assertEquals(data.getTpwd(), adapter.convert("123", "mm_444_555_666").value());
        data.setTpwd(null);
        assertEquals(data.getCouponClickUrl(), adapter.convert("123", "mm_444_555_666").value());
    }

    @Test public void jdQuoteSelectsRequestedSkuAndConversionGeneratesMaterialServerSide() {
        FakeGateway gateway = new FakeGateway();
        DtkJdCommodityDetailsResponse item = new DtkJdCommodityDetailsResponse();
        item.setSkuId("123456789");
        item.setActualPrice("100.00");
        item.setCommissionShare("10");
        item.setCouponAmount("20.00");
        gateway.respond(DtkJdCommodityDetailsRequest.class, DtkApiResponse.buildSuccess(List.of(item)));
        assertEquals(new BigDecimal("0.10"), adapter(Platform.JD, gateway).quote(item.getSkuId()).rate());
        DtkJdCommodityTransformLinkResponse link = new DtkJdCommodityTransformLinkResponse();
        link.setShortUrl("https://u.jd.com/test");
        gateway.respond(DtkJdCommodityTransformLinkRequest.class, DtkApiResponse.buildSuccess(link));
        adapter(Platform.JD, gateway).convert("123456789", "opaque_user_token");
        DtkJdCommodityTransformLinkRequest sent = (DtkJdCommodityTransformLinkRequest) gateway.requests.get(1);
        assertEquals("https://item.jd.com/123456789.html", sent.getMaterialId());
        assertEquals("opaque_user_token", sent.getSubUnionId());
        assertEquals(Long.valueOf(100), sent.getPositionId());
        assertNull(sent.getChildPid());
        assertThrows(UnionCouponException.class, () -> adapter(Platform.JD, gateway).convert("123?pid=evil", "token"));
        assertEquals(2, gateway.requests.size());
    }

    @Test public void jdParserRejectsOtherHostsAndDoesNotDoubleEncodeUrl() {
        FakeGateway gateway = new FakeGateway();
        DtkJdLinkAnalysisResponse data = new DtkJdLinkAnalysisResponse();
        data.setSkuId("123");
        gateway.respond(DtkJdLinkAnalysisRequest.class, DtkApiResponse.buildSuccess(data));
        assertEquals("123", adapter(Platform.JD, gateway).parse("https://u.jd.com/test?a=1&b=2").itemId());
        assertEquals("https://u.jd.com/test?a=1&b=2", ((DtkJdLinkAnalysisRequest) gateway.requests.get(0)).getUrl());
        assertThrows(UnionCouponException.class, () -> adapter(Platform.JD, gateway).parse("https://jd.com.example.invalid/test"));
        assertEquals(1, gateway.requests.size());
    }

    @Test public void pddAcceptsVerifiedGoodsSignUrlButDoesNotInventShortLinkOrNumericParsing() {
        FakeGateway gateway = new FakeGateway();
        UnionAdapter adapter = adapter(Platform.PDD, gateway);
        assertEquals("sign_abc-123", adapter.parse("https://mobile.yangkeduo.com/goods.html?goods_sign=sign_abc-123").itemId());
        assertEquals("PDD_PARSE_UNSUPPORTED", assertThrows(UnionCouponException.class,
                () -> adapter.parse("https://p.pinduoduo.com/short")).code());
        assertEquals("PDD_PARSE_UNSUPPORTED", assertThrows(UnionCouponException.class,
                () -> adapter.parse("https://mobile.yangkeduo.com/goods.html?goods_id=123")).code());
        assertThrows(UnionCouponException.class,
                () -> adapter.parse("https://pinduoduo.com.example.invalid/goods?goods_sign=sign_abc"));
        assertThrows(UnionCouponException.class,
                () -> adapter.parse("https://mobile.yangkeduo.com/goods?goods_sign=a&goods_sign=b"));
        assertTrue(gateway.requests.isEmpty());
    }

    @Test public void pddConvertsCentsAndPermilleWithoutCachingPersonalizedPrediction() {
        FakeGateway gateway = new FakeGateway();
        DtkPddGoodsDetailsResponse details = pddDetails();
        gateway.respond(DtkPddGoodsDetailsRequest.class, DtkApiResponse.buildSuccess(details));
        CommissionQuote quote = adapter(Platform.PDD, gateway).quote("sign_test");
        assertEquals(0, new BigDecimal("0.125").compareTo(quote.rate()));
        assertEquals(0, new BigDecimal("10.00").compareTo(quote.couponAmount()));
        assertEquals(0, new BigDecimal("90.00").compareTo(quote.finalPrice()));
        details.setPredictPromotionRate(BigDecimal.ZERO);
        CommissionQuote comparison = adapter(Platform.PDD, gateway).quote("sign_test");
        assertEquals(0, new BigDecimal("0.125").compareTo(comparison.rate()));
        assertEquals(0, gateway.fallbackCalls);
    }

    @Test public void pddCouponThresholdMustBeMet() {
        FakeGateway gateway = new FakeGateway();
        DtkPddGoodsDetailsResponse details = pddDetails();
        details.setCouponMinOrderAmount(new BigDecimal("20000"));
        gateway.respond(DtkPddGoodsDetailsRequest.class, DtkApiResponse.buildSuccess(details));
        CommissionQuote quote = adapter(Platform.PDD, gateway).quote("sign_test");
        assertEquals(0, quote.couponAmount().signum());
        assertEquals(0, new BigDecimal("100.00").compareTo(quote.finalPrice()));
    }

    @Test public void pddConversionWritesOpaqueUidAndRejectsParameterInjection() {
        FakeGateway gateway = new FakeGateway();
        DtkPddTransformLinkResponse link = new DtkPddTransformLinkResponse();
        link.setMobileShortUrl("https://p.pinduoduo.com/test");
        gateway.respond(DtkPddTransformLinkRequest.class, DtkApiResponse.buildSuccess(link));
        adapter(Platform.PDD, gateway).convert("sign_test", "opaque_123");
        DtkPddTransformLinkRequest sent = (DtkPddTransformLinkRequest) gateway.requests.get(0);
        assertEquals("{\"uid\":\"opaque_123\"}", sent.getCustomParameters());
        assertEquals("111_222", sent.getPid());
        assertEquals("sign_test", sent.getGoodsSign());
        assertThrows(UnionCouponException.class,
                () -> adapter(Platform.PDD, gateway).convert("sign_test", "\"},\"pid\":\"evil"));
        assertEquals(1, gateway.requests.size());
        link.setPredictPromotionRate(BigDecimal.ZERO);
        assertEquals("PDD_PRICE_COMPARISON", assertThrows(UnionCouponException.class,
                () -> adapter(Platform.PDD, gateway).convert("sign_test", "opaque_123")).code());
    }

    @Test public void anonymousJdAndPddConversionsUseServerDefaultPositionsWithoutIdentity() {
        FakeGateway gateway = new FakeGateway();
        DtkJdCommodityTransformLinkResponse jd = new DtkJdCommodityTransformLinkResponse();
        jd.setShortUrl("https://u.jd.com/default");
        gateway.respond(DtkJdCommodityTransformLinkRequest.class, DtkApiResponse.buildSuccess(jd));
        adapter(Platform.JD, gateway).convert("123", null);
        DtkJdCommodityTransformLinkRequest jdRequest = (DtkJdCommodityTransformLinkRequest) gateway.requests.get(0);
        assertEquals(Long.valueOf(100), jdRequest.getPositionId());
        assertNull(jdRequest.getSubUnionId());
        DtkPddTransformLinkResponse pdd = new DtkPddTransformLinkResponse();
        pdd.setMobileShortUrl("https://p.pinduoduo.com/default");
        gateway.respond(DtkPddTransformLinkRequest.class, DtkApiResponse.buildSuccess(pdd));
        adapter(Platform.PDD, gateway).convert("sign_test", null);
        DtkPddTransformLinkRequest pddRequest = (DtkPddTransformLinkRequest) gateway.requests.get(1);
        assertEquals("111_222", pddRequest.getPid());
        assertNull(pddRequest.getCustomParameters());
    }

    @Test public void failedOrEmptyConversionNeverReturnsInputAndDoesNotLeakProviderMessage() {
        FakeGateway gateway = new FakeGateway();
        gateway.respond(DtkJdCommodityTransformLinkRequest.class, DtkApiResponse.buildFail(1, "SECRET_API_KEY"));
        UnionCouponException failed = assertThrows(UnionCouponException.class,
                () -> adapter(Platform.JD, gateway).convert("123", null));
        assertEquals("UPSTREAM_FAILURE", failed.code());
        assertFalse(failed.getMessage().contains("SECRET"));
        gateway.respond(DtkJdCommodityTransformLinkRequest.class, DtkApiResponse.buildSuccess(new DtkJdCommodityTransformLinkResponse()));
        assertEquals("UPSTREAM_INVALID_RESPONSE", assertThrows(UnionCouponException.class,
                () -> adapter(Platform.JD, gateway).convert("123", null)).code());
    }

    @Test public void secondaryQuoteIsCheckedAndDoesNotTurnZeroCommissionIntoPositive() {
        FakeGateway gateway = new FakeGateway();
        gateway.respond(DtkJdCommodityDetailsRequest.class, DtkApiResponse.buildFail(1, "unavailable"));
        gateway.fallback = new CommissionQuote(new BigDecimal("0.10"), BigDecimal.ZERO, new BigDecimal("99.00"));
        assertEquals(gateway.fallback, adapter(Platform.JD, gateway).quote("123"));
        gateway.fallback = new CommissionQuote(new BigDecimal("1.01"), BigDecimal.ZERO, BigDecimal.ONE);
        assertThrows(UnionCouponException.class, () -> adapter(Platform.JD, gateway).quote("123"));
    }

    @Test public void productionDtkSelectionHasExactlyOneImplementationPerPlatform() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("prod");
            for (Platform platform : Platform.values()) environment.setProperty("cps.union.adapter." + platform.name().toLowerCase(), "dtk");
            context.setEnvironment(environment);
            context.registerBean(DtkCouponGateway.class, FakeGateway::new);
            context.register(DtkCouponAdapterConfiguration.class, UnionAdapterConfiguration.class);
            context.refresh();
            assertEquals(3, context.getBeansOfType(UnionAdapter.class).size());
            assertTrue(context.getBeansOfType(UnionAdapter.class).values().stream().allMatch(a -> a.kind() == AdapterKind.DTK));
        }
    }

    @Test public void dtkBeansAreCreatedOnlyForExplicitlySelectedPlatforms() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("cps.union.adapter.tbk", "dtk"));
            context.registerBean(DtkCouponGateway.class, FakeGateway::new);
            context.register(DtkCouponAdapterConfiguration.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(UnionAdapter.class).size());
            assertEquals(Platform.TBK, context.getBean(UnionAdapter.class).platform());
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DtkCouponAdapterConfiguration.class);
            context.refresh();
            assertTrue(context.getBeansOfType(UnionAdapter.class).isEmpty());
        }
    }

    @Test public void relatedRecommendationsUseVerifiedCatalogueIdAndExposeOnlyFiveProductFacts() {
        FakeGateway gateway = recommendationGateway();
        List<DtkListSimilerGoodsByOpenResponse> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) candidates.add(candidate("candidate_" + i));
        gateway.respond(DtkListSimilerGoodsByOpenRequest.class, DtkApiResponse.buildSuccess(candidates));
        DtkCouponAdapter adapter = new DtkCouponAdapter(Platform.TBK, gateway);
        assertTrue(adapter.supportsRecommendations());
        List<CouponRecommendation> result = adapter.recommend("original_123");
        assertEquals(5, result.size());
        assertEquals("candidate_0", result.get(0).itemId());
        assertEquals("RELATED", result.get(0).matchType());
        assertEquals(new BigDecimal("90.00"), result.get(0).finalPrice());
        DtkListSimilerGoodsByOpenRequest request = (DtkListSimilerGoodsByOpenRequest) gateway.requests.get(1);
        assertEquals(Integer.valueOf(456), request.getId());
        assertEquals(Integer.valueOf(5), request.getSize());
        assertEquals(List.of("platform", "itemId", "title", "finalPrice", "couponAmount", "matchType"),
                java.util.Arrays.stream(CouponRecommendation.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test public void missingOriginalOrCatalogueIdDoesNotGuessRecommendationInput() {
        FakeGateway gateway = new FakeGateway();
        DtkCouponAdapter adapter = new DtkCouponAdapter(Platform.TBK, gateway);
        assertTrue(adapter.recommend(null).isEmpty());
        assertTrue(adapter.recommend("").isEmpty());
        assertTrue(adapter.recommend("https://item.taobao.com/invalid").isEmpty());
        assertTrue(gateway.requests.isEmpty());
        DtkGoodsDetailsResponse details = new DtkGoodsDetailsResponse();
        details.setGoodsId("original_123");
        gateway.respond(DtkGoodsDetailsRequest.class, DtkApiResponse.buildSuccess(details));
        assertTrue(adapter.recommend("original_123").isEmpty());
        details.setId(456);
        details.setGoodsId(null);
        assertTrue(adapter.recommend("original_123").isEmpty());
        details.setGoodsId("wrong_original");
        assertTrue(adapter.recommend("original_123").isEmpty());
        assertTrue(gateway.requests.stream().allMatch(request -> request instanceof DtkGoodsDetailsRequest));
    }

    @Test public void invalidUnprofitableDuplicateAndOriginalCandidatesAreOmitted() {
        FakeGateway gateway = recommendationGateway();
        DtkListSimilerGoodsByOpenResponse noId = candidate(null);
        DtkListSimilerGoodsByOpenResponse invalidId = candidate("https://evil.invalid/?pid=forged");
        DtkListSimilerGoodsByOpenResponse negative = candidate("negative"); negative.setActualPrice(new BigDecimal("-1"));
        DtkListSimilerGoodsByOpenResponse noPrice = candidate("no_price"); noPrice.setActualPrice(null);
        DtkListSimilerGoodsByOpenResponse overPrecise = candidate("precision"); overPrecise.setActualPrice(new BigDecimal("1.001"));
        DtkListSimilerGoodsByOpenResponse negativeCoupon = candidate("negative_coupon"); negativeCoupon.setCouponPrice(new BigDecimal("-1"));
        DtkListSimilerGoodsByOpenResponse noCoupon = candidate("no_coupon"); noCoupon.setCouponPrice(null);
        DtkListSimilerGoodsByOpenResponse noTitle = candidate("no_title"); noTitle.setTitle(null);
        DtkListSimilerGoodsByOpenResponse noCommission = candidate("no_commission"); noCommission.setCommissionRate(BigDecimal.ZERO);
        List<DtkListSimilerGoodsByOpenResponse> candidates = new ArrayList<>(List.of(noId, invalidId, negative, noPrice,
                overPrecise, negativeCoupon, noCoupon, noTitle, noCommission, candidate("original_123"), candidate("valid"), candidate("valid")));
        candidates.add(null);
        gateway.respond(DtkListSimilerGoodsByOpenRequest.class, DtkApiResponse.buildSuccess(candidates));
        List<CouponRecommendation> result = new DtkCouponAdapter(Platform.TBK, gateway).recommend("original_123");
        assertEquals(1, result.size());
        assertEquals("valid", result.get(0).itemId());
    }

    @Test public void unverifiedJdAndPddRecommendationCapabilitiesAreExplicitlyUnsupported() {
        FakeGateway gateway = new FakeGateway();
        for (Platform platform : List.of(Platform.JD, Platform.PDD)) {
            DtkCouponAdapter adapter = new DtkCouponAdapter(platform, gateway);
            assertFalse(adapter.supportsRecommendations());
            assertTrue(adapter.recommend("123").isEmpty());
        }
        assertTrue(gateway.requests.isEmpty());
    }

    @Test public void recommendationProviderFailuresRemainSanitized() {
        FakeGateway gateway = recommendationGateway();
        gateway.respond(DtkListSimilerGoodsByOpenRequest.class, DtkApiResponse.buildFail(1, "SECRET_KEY=private"));
        UnionCouponException error = assertThrows(UnionCouponException.class,
                () -> new DtkCouponAdapter(Platform.TBK, gateway).recommend("original_123"));
        assertEquals("UPSTREAM_FAILURE", error.code());
        assertFalse(error.getMessage().contains("SECRET"));
    }

    private static FakeGateway recommendationGateway() {
        FakeGateway gateway = new FakeGateway();
        DtkGoodsDetailsResponse original = new DtkGoodsDetailsResponse();
        original.setGoodsId("original_123");
        original.setId(456);
        gateway.respond(DtkGoodsDetailsRequest.class, DtkApiResponse.buildSuccess(original));
        return gateway;
    }

    private static DtkListSimilerGoodsByOpenResponse candidate(String itemId) {
        DtkListSimilerGoodsByOpenResponse candidate = new DtkListSimilerGoodsByOpenResponse();
        candidate.setGoodsId(itemId); candidate.setTitle("测试相关商品");
        candidate.setActualPrice(new BigDecimal("90.00")); candidate.setCouponPrice(new BigDecimal("10.00"));
        candidate.setCommissionRate(new BigDecimal("20"));
        candidate.setCouponLink("https://example.invalid/private?pid=other_user");
        candidate.setItemLink("https://example.invalid/private?userId=other_user");
        return candidate;
    }

    private static DtkPddGoodsDetailsResponse pddDetails() {
        DtkPddGoodsDetailsResponse details = new DtkPddGoodsDetailsResponse();
        details.setGoodsSign("sign_test");
        details.setMinGroupPrice(new BigDecimal("10000"));
        details.setHasCoupon(true);
        details.setCouponDiscount(new BigDecimal("1000"));
        details.setCouponMinOrderAmount(new BigDecimal("5000"));
        details.setPromotionRate(new BigDecimal("125"));
        return details;
    }

    private static UnionAdapter adapter(Platform platform, FakeGateway gateway) {
        return new DtkCouponAdapter(platform, gateway);
    }

    private static final class FakeGateway implements DtkCouponGateway {
        final Map<Class<?>, Object> responses = new HashMap<>();
        final List<DtkApiRequest<?>> requests = new ArrayList<>();
        CommissionQuote fallback;
        int fallbackCalls;
        void respond(Class<?> requestType, Object response) { responses.put(requestType, response); }
        @Override @SuppressWarnings("unchecked") public <T> T execute(Platform platform, DtkApiRequest<T> request) {
            requests.add(request);
            if (!responses.containsKey(request.getClass())) throw new AssertionError("Unexpected request: " + request.getClass().getName());
            return (T) responses.get(request.getClass());
        }
        @Override public String defaultPid(Platform platform) {
            return switch (platform) { case TBK -> "mm_111_222_333"; case JD -> "100"; case PDD -> "111_222"; };
        }
        @Override public String jdUnionId() { return "12345"; }
        @Override public Optional<CommissionQuote> quoteFallback(Platform platform, String itemId) {
            fallbackCalls++;
            return Optional.ofNullable(fallback);
        }
    }
}
