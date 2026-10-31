package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.system.cps.mapper.QueryLogMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.*;

public class CouponConversionServiceTest {
    @Test public void parseFailureIsRecordedWithoutRetainingPastedText() {
        Fixture f = new Fixture(Platform.TBK);
        when(f.adapter.parse(anyString())).thenThrow(new UnionCouponException("PARSE_FAILED", "解析失败"));
        CouponResult result = f.service.parse(7L, "203.0.113.1", Platform.TBK, "private pasted url with secret");
        assertEquals("PARSE_FAILED", result.result());
        assertNull(result.value());
        CouponQueryLog log = f.onlyLog();
        assertEquals("PARSE_FAILED", log.result());
        assertEquals(Long.valueOf(7), log.userId());
        assertEquals("tbk", log.platform());
        assertEquals(64, log.rawHash().length());
        assertFalse(log.toString().contains("private pasted"));
        verify(f.adapter, never()).quote(anyString());
    }

    @Test public void noCommissionIsRecordedAndDoesNotReservePidOrConvert() {
        Fixture f = new Fixture(Platform.TBK);
        f.quoted(BigDecimal.ZERO);
        f.values.put("cps.coupon.message.no-commission", "配置的无佣金提示");
        CouponResult result = f.service.convert(7L, "203.0.113.1", Platform.TBK, "123");
        assertEquals("NO_COMMISSION", result.result());
        assertEquals("配置的无佣金提示", result.message());
        assertNull(result.value());
        assertNull(result.estimatedRebate());
        assertEquals("NO_COMMISSION", f.onlyLog().result());
        verifyNoInteractions(f.pids, f.tokens);
        verify(f.adapter, never()).convert(anyString(), any());
    }

    @Test public void lowCommissionUsesBackendThresholdAndHasItsOwnRecordedReason() {
        Fixture f = new Fixture(Platform.TBK);
        f.values.put("cps.coupon.minimum-commission", "1.00");
        f.values.put("cps.coupon.message.low-commission", "配置的低佣金提示");
        f.quoted(new BigDecimal("0.005"));
        CouponResult result = f.service.convert(7L, "203.0.113.1", Platform.TBK, "123");
        assertEquals("LOW_COMMISSION", result.result());
        assertEquals("配置的低佣金提示", result.message());
        assertNull(result.value());
        assertEquals("LOW_COMMISSION", f.onlyLog().result());
        assertEquals(new BigDecimal("0.005"), f.onlyLog().commissionRate());
        verifyNoInteractions(f.pids, f.tokens);
    }

    @Test public void sharedQuoteCacheNeverSharesJdAttributionBetweenUsers() {
        Fixture f = new Fixture(Platform.JD);
        f.quoted(new BigDecimal("0.20"));
        when(f.tokens.issue(Platform.JD, "123", 7L)).thenReturn("user7_opaque");
        when(f.tokens.issue(Platform.JD, "123", 8L)).thenReturn("user8_opaque");
        when(f.adapter.convert("123", "user7_opaque")).thenReturn(new ConvertedLink("https://u.jd.com/user7"));
        when(f.adapter.convert("123", "user8_opaque")).thenReturn(new ConvertedLink("https://u.jd.com/user8"));
        CouponResult first = f.service.convert(7L, "203.0.113.1", Platform.JD, "123");
        CouponResult second = f.service.convert(8L, "203.0.113.1", Platform.JD, "123");
        assertTrue(first.successful());
        assertTrue(second.successful());
        assertNotEquals(first.value(), second.value());
        assertEquals("ATTRIBUTION_TOKEN", first.bindingMode());
        assertNull(first.estimatedRebate());
        assertNull(second.estimatedRebate());
        assertEquals("PENDING_RULE_CONTRACT", first.rebateStatus());
        verify(f.adapter, times(1)).quote("123");
        verify(f.tokens).issue(Platform.JD, "123", 7L);
        verify(f.tokens).issue(Platform.JD, "123", 8L);
        verify(f.adapter).convert("123", "user7_opaque");
        verify(f.adapter).convert("123", "user8_opaque");
        verify(f.logs, times(2)).append(any(CouponQueryLog.class));
        verifyNoInteractions(f.pids);
    }

    @Test public void sameProductCacheDoesNotReuseAnotherUsersTaobaoLease() {
        Fixture f = new Fixture(Platform.TBK);
        f.quoted(new BigDecimal("0.20"));
        PidConversionReservation firstLease = new PidConversionReservation(new PidAllocation("mm_1_2_7", true, Instant.now().plusSeconds(60)),Platform.TBK,"123",7L,"attempt7");
        PidConversionReservation secondLease = new PidConversionReservation(new PidAllocation("mm_1_2_8", true, Instant.now().plusSeconds(60)),Platform.TBK,"123",8L,"attempt8");
        when(f.pids.reserveForConversion(Platform.TBK, "123", 7L)).thenReturn(firstLease);
        when(f.pids.reserveForConversion(Platform.TBK, "123", 8L)).thenReturn(secondLease);
        when(f.adapter.convert("123", "mm_1_2_7")).thenReturn(new ConvertedLink("￥七号口令￥"));
        when(f.adapter.convert("123", "mm_1_2_8")).thenReturn(new ConvertedLink("￥八号口令￥"));
        CouponResult first = f.service.convert(7L, "203.0.113.1", Platform.TBK, "123");
        CouponResult second = f.service.convert(8L, "203.0.113.1", Platform.TBK, "123");
        assertEquals("TRACKING_PID", first.bindingMode());
        assertEquals("TRACKING_PID", second.bindingMode());
        assertNotEquals(first.value(), second.value());
        verify(f.adapter, times(1)).quote("123");
        verify(f.adapter).convert("123", "mm_1_2_7");
        verify(f.adapter).convert("123", "mm_1_2_8");
        verify(f.pids).finishConversion(firstLease, true);
        verify(f.pids).finishConversion(secondLease, true);
        ArgumentCaptor<CouponQueryLog> logs = ArgumentCaptor.forClass(CouponQueryLog.class);
        verify(f.logs, times(2)).append(logs.capture());
        assertEquals("mm_1_2_7", logs.getAllValues().get(0).pid());
        assertEquals("mm_1_2_8", logs.getAllValues().get(1).pid());
    }

    @Test public void failedConversionReleasesItsReservation() {
        Fixture f = new Fixture(Platform.TBK);
        f.quoted(new BigDecimal("0.20"));
        PidConversionReservation lease = new PidConversionReservation(new PidAllocation("mm_1_2_7",true,Instant.now().plusSeconds(60)),Platform.TBK,"123",7L,"attempt7");
        when(f.pids.reserveForConversion(Platform.TBK,"123",7L)).thenReturn(lease);
        when(f.adapter.convert("123","mm_1_2_7")).thenThrow(new UnionCouponException("UPSTREAM_FAILURE","failed"));
        assertEquals("UPSTREAM_FAILURE",f.service.convert(7L,"address",Platform.TBK,"123").result());
        verify(f.pids).finishConversion(lease,false);
        verify(f.pids,never()).finishConversion(lease,true);
    }

    @Test public void parseThenConvertReusesOnlyProductFacts() {
        Fixture f = new Fixture(Platform.JD);
        f.quoted(new BigDecimal("0.20"));
        when(f.adapter.parse("https://u.jd.com/input")).thenReturn(new ParsedItem(Platform.JD, "123"));
        when(f.tokens.issue(Platform.JD, "123", 7L)).thenReturn("user7_token");
        when(f.adapter.convert("123", "user7_token")).thenReturn(new ConvertedLink("https://u.jd.com/output"));
        CouponResult parsed = f.service.parse(7L, "203.0.113.1", null, "https://u.jd.com/input");
        assertTrue(parsed.successful());
        assertNull(parsed.value());
        assertTrue(f.service.convert(7L, "203.0.113.1", Platform.JD, parsed.itemId()).successful());
        verify(f.adapter, times(1)).quote("123");
        verify(f.adapter, times(1)).convert(anyString(), any());
        verify(f.tokens, times(1)).issue(any(), anyString(), anyLong());
    }

    @Test public void anonymousJdAndPddConversionPassNoUserToken() {
        for (Platform platform : List.of(Platform.JD, Platform.PDD)) {
            Fixture f = new Fixture(platform);
            f.quoted(new BigDecimal("0.20"));
            String item = platform == Platform.JD ? "123" : "sign_test";
            when(f.adapter.convert(item, null)).thenReturn(new ConvertedLink("https://example.invalid/server-default"));
            CouponResult result = f.service.convert(null, "203.0.113.1", platform, item);
            assertTrue(result.successful());
            assertEquals("UNATTRIBUTED", result.bindingMode());
            assertTrue(result.message().contains("未登录"));
            verify(f.adapter).convert(item, null);
            verifyNoInteractions(f.tokens, f.pids);
        }
    }

    @Test public void comparisonOrderReturnsConfiguredReasonWithoutLinkOrRebate() {
        Fixture f = new Fixture(Platform.PDD);
        f.values.put("cps.coupon.message.pdd-comparison", "配置的比价订单提示");
        f.quoted(new BigDecimal("0.20"));
        when(f.tokens.issue(Platform.PDD, "sign_test", 7L)).thenReturn("user7_token");
        when(f.adapter.convert("sign_test", "user7_token"))
                .thenThrow(new UnionCouponException("PDD_PRICE_COMPARISON", "provider message"));
        CouponResult result = f.service.convert(7L, "203.0.113.1", Platform.PDD, "sign_test");
        assertEquals("NO_COMMISSION", result.result());
        assertEquals("配置的比价订单提示", result.message());
        assertNull(result.value());
        assertNull(result.estimatedRebate());
        assertEquals("UNATTRIBUTED", result.bindingMode());
        assertEquals("NO_COMMISSION", f.onlyLog().result());
    }

    @Test public void dependencyFailureAndStructuredProviderFailureCannotLeakSecrets() {
        Fixture generic = new Fixture(Platform.JD);
        when(generic.adapter.quote(anyString())).thenThrow(new IllegalStateException("SECRET_KEY=private"));
        CouponResult result = generic.service.convert(7L, "203.0.113.1", Platform.JD, "123");
        assertEquals("SERVICE_UNAVAILABLE", result.result());
        assertFalse(result.message().contains("SECRET"));
        assertNull(result.value());
        assertEquals("SERVICE_UNAVAILABLE", generic.onlyLog().result());
        Fixture structured = new Fixture(Platform.JD);
        when(structured.adapter.quote(anyString())).thenThrow(new UnionCouponException("UPSTREAM_FAILURE", "SECRET_KEY=private"));
        CouponResult sanitized = structured.service.convert(7L, "203.0.113.1", Platform.JD, "123");
        assertEquals("UPSTREAM_FAILURE", sanitized.result());
        assertFalse("Even a typed supplier exception is not trusted user-facing copy", sanitized.message().contains("SECRET"));
        assertNull(sanitized.value());
    }

    @Test public void rateLimitStopsAllUnionCallsWithoutRecordingTheAttempt() {
        Fixture f = new Fixture(Platform.JD);
        doThrow(new UnionCouponException("RATE_LIMITED", "查询频繁")).when(f.limiter).check(7L, "203.0.113.1", Platform.JD);
        CouponResult result = f.service.parse(7L, "203.0.113.1", Platform.JD, "https://u.jd.com/test");
        assertEquals("RATE_LIMITED", result.result());
        verify(f.adapter, never()).parse(anyString());
        verify(f.adapter, never()).quote(anyString());
        verify(f.adapter, never()).convert(anyString(), any());
        verify(f.limiter, never()).failed(any(), any());
        verifyNoInteractions(f.logs);
    }

    @Test public void everySuccessfulQueryLeavesUserRebateUnsetUntilRuleContractExists() {
        Fixture f = new Fixture(Platform.JD);
        f.quoted(new BigDecimal("0.99"));
        when(f.adapter.parse(anyString())).thenReturn(new ParsedItem(Platform.JD, "123"));
        CouponResult result = f.service.parse(null, "203.0.113.1", Platform.JD, "https://u.jd.com/test");
        assertTrue(result.successful());
        assertNull(result.estimatedRebate());
        assertEquals("PENDING_RULE_CONTRACT", result.rebateStatus());
        assertNull(f.onlyLog().shownRebate());
        assertEquals(new BigDecimal("0.99"), f.onlyLog().commissionRate());
    }

    @Test public void queryLoggerDoesNotSilentlyIgnorePersistenceFailure() {
        QueryLogMapper mapper = mock(QueryLogMapper.class);
        CouponQueryLog log = new CouponQueryLog(7L, "tbk", "hash", "123", "NO_COMMISSION", BigDecimal.ZERO, null, null, Instant.now());
        CouponQueryLogger logger = new CouponQueryLogger(mapper);
        when(mapper.append(log)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> logger.append(log));
        when(mapper.append(log)).thenReturn(1);
        logger.append(log);
        verify(mapper, times(2)).append(log);
    }

    @Test public void recommendationFailureCannotReplaceOriginalNoCommissionOutcome() {
        Fixture f = new Fixture(Platform.TBK, true);
        f.quoted(BigDecimal.ZERO);
        f.values.put("cps.coupon.message.no-commission", "配置的无佣金提示");
        CouponRecommendationProvider recommendations = (CouponRecommendationProvider) f.adapter;
        when(recommendations.supportsRecommendations()).thenReturn(true);
        when(recommendations.recommend("123")).thenThrow(new UnionCouponException("UPSTREAM_FAILURE", "SECRET_RECOMMENDATION_PROVIDER"));
        CouponResult result = f.service.convert(7L, "203.0.113.1", Platform.TBK, "123");
        assertEquals("NO_COMMISSION", result.result());
        assertEquals("配置的无佣金提示", result.message());
        assertNull(result.value());
        assertNull(result.estimatedRebate());
        assertEquals("NO_COMMISSION", f.onlyLog().result());
        assertFalse(result.toString().contains("SECRET"));
        assertEquals("UNAVAILABLE", result.recommendationStatus());
        assertTrue(result.recommendations().isEmpty());
        verify(recommendations).recommend("123");
        verifyNoInteractions(f.pids, f.tokens);
    }

    @Test public void relatedRecommendationsDoNotChangeTheOriginalQueryClassification() {
        Fixture f = new Fixture(Platform.TBK, true);
        f.quoted(BigDecimal.ZERO);
        CouponRecommendationProvider recommendations = (CouponRecommendationProvider) f.adapter;
        when(recommendations.supportsRecommendations()).thenReturn(true);
        when(recommendations.recommend("123")).thenReturn(List.of(new CouponRecommendation(Platform.TBK,
                "related_456", "相关商品", new BigDecimal("90.00"), new BigDecimal("10.00"))));
        CouponResult result = f.service.convert(7L, "203.0.113.1", Platform.TBK, "123");
        assertEquals("NO_COMMISSION", result.result());
        assertNull(result.value());
        assertNull(result.estimatedRebate());
        assertEquals("NO_COMMISSION", f.onlyLog().result());
        assertEquals("RELATED", result.recommendationStatus());
        assertEquals(1, result.recommendations().size());
        assertEquals("related_456", result.recommendations().get(0).itemId());
        assertEquals("RELATED", result.recommendations().get(0).matchType());
        verify(recommendations).recommend("123");
        verifyNoInteractions(f.pids, f.tokens);
    }

    private static final class Fixture {
        final UnionAdapter adapter;
        final CouponRateLimiter limiter = mock(CouponRateLimiter.class);
        final CouponQuoteCache cache = mock(CouponQuoteCache.class);
        final CouponQueryLogger logs = mock(CouponQueryLogger.class);
        final PidPoolService pids = mock(PidPoolService.class);
        final AttributionTokenService tokens = mock(AttributionTokenService.class);
        final Map<String, String> values = new HashMap<>();
        final Map<String, CommissionQuote> quotes = new HashMap<>();
        final CouponConversionService service;
        Fixture(Platform platform) {
            this(platform, false);
        }
        Fixture(Platform platform, boolean recommendations) {
            adapter = recommendations ? mock(UnionAdapter.class, withSettings().extraInterfaces(CouponRecommendationProvider.class)) : mock(UnionAdapter.class);
            MockEnvironment env = new MockEnvironment();
            for (Platform p : Platform.values()) env.setProperty("cps.union.adapter." + p.name().toLowerCase(), "dtk");
            ISysConfigService config = mock(ISysConfigService.class);
            when(config.selectConfigByKey(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
            CouponSettings settings = new CouponSettings(config, env);
            when(adapter.platform()).thenReturn(platform);
            when(adapter.kind()).thenReturn(AdapterKind.DTK);
            when(cache.get(any(), anyString())).thenAnswer(call -> quotes.get(call.getArgument(0) + ":" + call.getArgument(1)));
            doAnswer(call -> {
                quotes.put(call.getArgument(0) + ":" + call.getArgument(1), call.getArgument(2));
                return null;
            }).when(cache).put(any(), anyString(), any());
            service = new CouponConversionService(List.of(adapter), new UnionAdapterConfiguration(env),
                    settings, limiter, cache, logs, pids, tokens);
        }
        void quoted(BigDecimal rate) {
            when(adapter.quote(anyString())).thenReturn(new CommissionQuote(rate, new BigDecimal("10.00"), new BigDecimal("100.00")));
        }
        CouponQueryLog onlyLog() {
            ArgumentCaptor<CouponQueryLog> capture = ArgumentCaptor.forClass(CouponQueryLog.class);
            verify(logs).append(capture.capture());
            return capture.getValue();
        }
    }
}
