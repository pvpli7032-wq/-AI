package com.ruoyi.system.cps.attribution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.ruoyi.union.adapter.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One path for new and legacy conversion endpoints. Identity is supplied by the login boundary. */
@Service
public class CouponConversionService {
    private static final Logger log = LoggerFactory.getLogger(CouponConversionService.class);
    private final List<UnionAdapter> adapters;
    private final UnionAdapterConfiguration selection;
    private final CouponSettings settings;
    private final CouponRateLimiter limiter;
    private final CouponQuoteCache cache;
    private final CouponQueryLogger queryLog;
    private final PidPoolService pids;
    private final AttributionTokenService tokens;
    public CouponConversionService(List<UnionAdapter> adapters, UnionAdapterConfiguration selection,
            CouponSettings settings, CouponRateLimiter limiter, CouponQuoteCache cache,
            CouponQueryLogger queryLog, PidPoolService pids, AttributionTokenService tokens) {
        this.adapters = adapters; this.selection = selection; this.settings = settings;
        this.limiter = limiter; this.cache = cache; this.queryLog = queryLog; this.pids = pids; this.tokens = tokens;
    }
    public CouponResult parse(Long userId, String remoteAddress, Platform hint, String input) {
        return run(userId, remoteAddress, hint == null ? detect(input) : hint, input, false);
    }
    public CouponResult convert(Long userId, String remoteAddress, Platform platform, String itemId) {
        return run(userId, remoteAddress, platform, itemId, true);
    }
    private CouponResult run(Long userId, String address, Platform platform, String input, boolean link) {
        String result = link ? "CONVERT_FAILED" : "PARSE_FAILED";
        String item = null, pid = null, value = null, mode = "UNATTRIBUTED";
        CommissionQuote quote = null;
        PidConversionReservation reservation = null;
        String rawHash = CouponRateLimiter.hash(input == null ? "" : input);
        try {
            if (platform == null || input == null || input.isBlank() || input.length() > 4096) {
                throw new UnionCouponException(result, "请提供有效的商品链接或商品标识");
            }
            if (userId != null && userId <= 0) throw new UnionCouponException("INVALID_USER", "登录状态无效");
            limiter.check(userId, address, platform);
            UnionAdapter adapter = selection.select(platform, adapters);
            if (link) {
                if (!input.matches("[A-Za-z0-9_\\-]{1,128}")) throw new UnionCouponException("INVALID_ITEM", "商品标识无效");
                item = input;
            } else {
                ParsedItem parsed = adapter.parse(input);
                if (parsed == null || parsed.platform() != platform || parsed.itemId() == null || parsed.itemId().isBlank()) {
                    throw new UnionCouponException("PARSE_FAILED", "商品解析失败");
                }
                item = parsed.itemId();
            }
            quote = cache.get(platform, item);
            if (quote == null) {
                quote = adapter.quote(item);
                validate(quote);
                cache.put(platform, item, quote);
            }
            validate(quote);
            if (quote.rate().signum() == 0 || quote.finalPrice().signum() == 0) {
                result = "NO_COMMISSION";
                return response(result, platform, item, quote, null, mode, noCommissionMessage());
            }
            BigDecimal minimum = settings.nonNegative("cps.coupon.minimum-commission", BigDecimal.ZERO);
            if (quote.finalPrice().multiply(quote.rate()).compareTo(minimum) < 0) {
                result = "LOW_COMMISSION";
                return response(result, platform, item, quote, null, mode,
                    settings.text("cps.coupon.message.low-commission", "该商品佣金低于当前参与门槛。"));
            }
            if (link) {
                String attribution = null;
                if (platform == Platform.TBK) {
                    reservation = pids.reserveForConversion(platform, item, userId);
                    PidAllocation allocation = reservation.allocation();
                    pid = allocation.pid(); attribution = pid;
                    mode = allocation.tracking() ? "TRACKING_PID" : "UNATTRIBUTED";
                } else if (userId != null) {
                    attribution = tokens.issue(platform, item, userId);
                    mode = "ATTRIBUTION_TOKEN";
                }
                ConvertedLink converted = adapter.convert(item, attribution);
                if (converted == null || converted.value() == null || converted.value().isBlank()) {
                    throw new UnionCouponException("CONVERT_FAILED", "转链失败，请稍后重试");
                }
                value = converted.value();
                if (reservation != null) {
                    pids.finishConversion(reservation, true);
                    reservation = null;
                }
            }
            result = "SUCCESS";
            limiter.succeeded(userId, address);
            String message = userId == null && link ? "未登录，订单暂不能自动绑定本人。"
                : link && platform == Platform.TBK && "UNATTRIBUTED".equals(mode)
                    ? "订单不能自动绑定，请提交订单号核对。" : "查询成功";
            return response(result, platform, item, quote, value, mode, message);
        } catch (UnionCouponException ex) {
            result = normalizedCode(ex.code());
            if (!"RATE_LIMITED".equals(result)) recordFailure(userId, address);
            String message = "PDD_PRICE_COMPARISON".equals(ex.code())
                ? (settings.enabled("cps.coupon.show-pdd-comparison", true)
                    ? settings.text("cps.coupon.message.pdd-comparison", "比价订单无法获得佣金。") : "")
                : safeMessage(ex.code());
            return response(result, platform, item, quote, null, "UNATTRIBUTED", message);
        } catch (RuntimeException ex) {
            // Never return provider errors, credentials, request parameters or raw response text.
            result = "SERVICE_UNAVAILABLE";
            return response(result, platform, item, quote, null, "UNATTRIBUTED", "服务暂不可用，请稍后重试。");
        } finally {
            if (reservation != null) {
                try { pids.finishConversion(reservation, false); }
                catch (RuntimeException failure) { log.error("无法回收失败的转链租约", failure); }
            }
            if (!"RATE_LIMITED".equals(result)) {
                queryLog.append(new CouponQueryLog(userId, platform == null ? null : platform.name().toLowerCase(Locale.ROOT),
                    rawHash, item, result, quote == null ? null : quote.rate(), null, pid, Instant.now()));
            }
        }
    }
    private void recordFailure(Long userId, String address) {
        try { limiter.failed(userId, address); } catch (RuntimeException ignored) { /* Query failure is still persisted. */ }
    }
    private String noCommissionMessage() {
        return settings.enabled("cps.coupon.show-no-commission", true)
            ? settings.text("cps.coupon.message.no-commission", "该商品暂无佣金，请选择其他商品或稍后重试。") : "";
    }
    private CouponResult response(String result, Platform platform, String item, CommissionQuote quote,
            String value, String mode, String message) {
        List<String> notices = new ArrayList<>();
        if ("SUCCESS".equals(result)) {
            notices.add(settings.text("cps.coupon.message.redpacket", "请勿使用其他渠道的购物红包或礼金。"));
            notices.add(settings.text("cps.coupon.message.old-cart", "购物车和收藏中的旧商品请先删除，再通过本次链接下单。"));
            notices.add(settings.text("cps.coupon.message.refund", "订单取消、退款或维权可能导致返利失效。"));
            String minutes = settings.text("cps.coupon.binding-notice-minutes", "");
            if (minutes.matches("[1-9][0-9]{0,3}")) notices.add("下单后" + minutes + "分钟内未收到绑定提醒，请提交订单号。");
            if (platform == Platform.PDD && settings.enabled("cps.coupon.show-pdd-comparison", true)) {
                notices.add(settings.text("cps.coupon.message.pdd-comparison", "比价订单可能无法获得佣金。"));
            }
        }
        // Line 8 has no rule/quote contract yet. Do not invent a user ratio or relabel commission.
        List<CouponRecommendation> recommendations = List.of();
        String recommendationStatus = "NOT_REQUESTED";
        if ("NO_COMMISSION".equals(result) && platform != null && item != null) {
            recommendationStatus = "UNSUPPORTED";
            try {
                UnionAdapter selected = selection.select(platform, adapters);
                if (selected instanceof CouponRecommendationProvider provider && provider.supportsRecommendations()) {
                    recommendations = List.copyOf(provider.recommend(item));
                    recommendationStatus = recommendations.isEmpty() ? "EMPTY" : "RELATED";
                }
            } catch (RuntimeException unavailable) {
                // Recommendation failure must not change the reason the original item is ineligible.
                recommendationStatus = "UNAVAILABLE";
            }
        }
        return new CouponResult(result, platform, item, quote == null ? null : quote.couponAmount(),
            quote == null ? null : quote.finalPrice(), null, "PENDING_RULE_CONTRACT", value, mode, message,
            List.copyOf(notices), recommendations, recommendationStatus);
    }
    private static String normalizedCode(String code) {
        if ("PDD_PRICE_COMPARISON".equals(code)) return "NO_COMMISSION";
        return code != null && code.matches("[A-Z_]{1,32}") ? code : "UPSTREAM_FAILED";
    }
    private static String safeMessage(String code) {
        if (code == null) return "服务暂不可用，请稍后重试。";
        return switch (code) {
            case "RATE_LIMITED" -> "查询频繁，请稍后再试。";
            case "PARSE_FAILED", "INVALID_ITEM", "INVALID_REQUEST" -> "商品链接或商品标识无效，请重新检查。";
            case "PDD_PARSE_UNSUPPORTED" -> "暂不支持此拼多多链接，请使用含商品标识的商品链接。";
            case "INVALID_USER" -> "登录状态无效，请重新登录。";
            case "UPSTREAM_NOT_CONFIGURED" -> "联盟服务尚未配置，请稍后再试。";
            case "UPSTREAM_TIMEOUT" -> "联盟响应超时，请稍后重试。";
            case "QUEUE_FULL", "UPSTREAM_BUSY" -> "查询人数较多，请稍后重试。";
            default -> "服务暂不可用，请稍后重试。";
        };
    }
    private static void validate(CommissionQuote q) {
        if (q == null || q.rate() == null || q.couponAmount() == null || q.finalPrice() == null
            || q.rate().signum() < 0 || q.rate().compareTo(BigDecimal.ONE) > 0
            || q.couponAmount().signum() < 0 || q.finalPrice().signum() < 0) {
            throw new UnionCouponException("INVALID_QUOTE", "商品报价暂不可用");
        }
    }
    private static Platform detect(String input) {
        String value = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (value.contains("jd.com") || value.contains("3.cn")) return Platform.JD;
        if (value.contains("pinduoduo.com") || value.contains("yangkeduo.com")) return Platform.PDD;
        return Platform.TBK;
    }
}
