package com.ruoyi.union.adapter;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import com.ruoyi.union.dtk.api.client.DtkApiRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkCouponQueryRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkGetPrivilegeLinkRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkJdCommodityTransformLinkRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkJdLinkAnalysisRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkParseContentRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkParseTaokoulingRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkPddTransformLinkRequest;
import com.ruoyi.union.dtk.api.request.putstorage.DtkGoodsDetailsRequest;
import com.ruoyi.union.dtk.api.request.putstorage.DtkJdCommodityDetailsRequest;
import com.ruoyi.union.dtk.api.request.putstorage.DtkPddGoodsDetailsRequest;
import com.ruoyi.union.dtk.api.request.special.DtkListSimilerGoodsByOpenRequest;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkGetPrivilegeLinkResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkParseContentResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkPddTransformLinkResponse;
import com.ruoyi.union.dtk.api.response.putstorage.DtkGoodsDetailsResponse;
import com.ruoyi.union.dtk.api.response.putstorage.DtkJdCommodityDetailsResponse;
import com.ruoyi.union.dtk.api.response.putstorage.DtkPddGoodsDetailsResponse;

/**
 * DTK coupon operations. Rates in CommissionQuote are fractions (20% = 0.20),
 * and all prices/coupons are RMB. Conversion receives server-owned attribution only.
 * Order ingestion is deliberately a separate implementation owned by line 10.
 */
public final class DtkCouponAdapter implements UnionAdapter, CouponRecommendationProvider {
    private final Platform platform;
    private final DtkCouponGateway gateway;

    public DtkCouponAdapter(Platform platform, DtkCouponGateway gateway) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override public Platform platform() { return platform; }
    @Override public AdapterKind kind() { return AdapterKind.DTK; }

    @Override public boolean supportsRecommendations() { return platform == Platform.TBK; }

    @Override
    public List<CouponRecommendation> recommend(String itemId) {
        if (!supportsRecommendations() || itemId == null || !itemId.matches("[A-Za-z0-9_-]{1,128}")) return List.of();
        DtkGoodsDetailsRequest originalRequest = new DtkGoodsDetailsRequest();
        originalRequest.setGoodsId(itemId);
        DtkGoodsDetailsResponse original = data(originalRequest);
        // This endpoint needs DTK's catalogue ID, not the Taobao ID. A guessed numeric
        // conversion could recommend an entirely different item, so require exact evidence.
        if (original.getId() == null || original.getId() <= 0
                || !itemId.equals(original.getGoodsId()) && !itemId.equals(original.getGoodsSign())) return List.of();
        DtkListSimilerGoodsByOpenRequest request = new DtkListSimilerGoodsByOpenRequest();
        request.setId(original.getId());
        request.setSize(5);
        var candidates = data(request);
        List<CouponRecommendation> result = new ArrayList<>();
        var seen = new HashSet<String>();
        for (var candidate : candidates) {
            if (candidate == null) continue;
            String id = candidate.getGoodsId();
            String title = first(candidate.getDtitle(), candidate.getTitle());
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || id.equals(itemId)
                    || id.equals(original.getGoodsId()) || id.equals(original.getGoodsSign())
                    || title == null || title.length() > 300
                    || !validRecommendationAmount(candidate.getActualPrice(), false)
                    || !validRecommendationAmount(candidate.getCouponPrice(), true)
                    || candidate.getCommissionRate() == null || candidate.getCommissionRate().signum() <= 0
                    || candidate.getCommissionRate().compareTo(new BigDecimal("100")) > 0 || !seen.add(id)) continue;
            result.add(new CouponRecommendation(Platform.TBK, id, title, candidate.getActualPrice(), candidate.getCouponPrice()));
            if (result.size() == 5) break;
        }
        return List.copyOf(result);
    }

    private boolean validRecommendationAmount(BigDecimal amount, boolean allowZero) {
        return amount != null && (allowZero ? amount.signum() >= 0 : amount.signum() > 0)
                && amount.stripTrailingZeros().scale() <= 2 && amount.compareTo(new BigDecimal("999999999999.99")) <= 0;
    }

    @Override
    public ParsedItem parse(String input) {
        if (input == null || input.isBlank() || input.length() > 4096) {
            throw failure("PARSE_FAILED", "请粘贴有效的商品链接或口令");
        }
        String itemId = switch (platform) {
            case TBK -> parseTaobao(input.trim());
            case JD -> parseJd(input.trim());
            case PDD -> parsePdd(input.trim());
        };
        return new ParsedItem(platform, validateItem(itemId));
    }

    private String parseTaobao(String input) {
        DtkParseContentRequest request = new DtkParseContentRequest();
        request.setContent(input);
        try {
            return parsedTaobaoId(data(request));
        } catch (UnionCouponException first) {
            if (!retryableResponse(first)) throw first;
            DtkParseTaokoulingRequest tokenRequest = new DtkParseTaokoulingRequest();
            tokenRequest.setContent(input);
            try {
                return parsedTaobaoId(data(tokenRequest));
            } catch (UnionCouponException second) {
                if (!retryableResponse(second)) throw second;
                throw failure("PARSE_FAILED", "商品链接或淘口令解析失败");
            }
        }
    }

    private String parsedTaobaoId(DtkParseContentResponse response) {
        String item = first(response.getGoodsId(), response.getItemId());
        if (item == null) throw invalidResponse();
        // Keep encrypted IDs as strings. Never coerce them or numeric IDs through floating point.
        return item;
    }

    private String parseJd(String input) {
        URI uri = uri(input, "PARSE_FAILED");
        if (!hostMatches(uri, "jd.com") && !hostMatches(uri, "3.cn")) {
            throw failure("PARSE_FAILED", "请使用京东商品链接");
        }
        DtkJdLinkAnalysisRequest request = new DtkJdLinkAnalysisRequest();
        // WebUtils.buildQuery performs the one required URL encoding.
        request.setUrl(uri.toASCIIString());
        String sku = data(request).getSkuId();
        if (sku == null || !sku.matches("[1-9][0-9]{0,19}")) throw invalidResponse();
        return sku;
    }

    private String parsePdd(String input) {
        URI uri = uri(input, "PDD_PARSE_UNSUPPORTED");
        if (!hostMatches(uri, "pinduoduo.com") && !hostMatches(uri, "yangkeduo.com")) {
            throw failure("PARSE_FAILED", "请使用拼多多商品链接");
        }
        String sign = null;
        if (uri.getRawQuery() != null) {
            for (String parameter : uri.getRawQuery().split("&")) {
                String[] pair = parameter.split("=", 2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if (!name.equals("goods_sign") && !name.equals("goodsSign")) continue;
                if (pair.length != 2 || sign != null) throw failure("PARSE_FAILED", "商品标识无效");
                sign = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        if (sign == null || sign.isBlank() || sign.matches("[0-9]+")) {
            // Neither the repository nor verified DTK documentation establishes a short-link
            // / numeric goodsId -> goodsSign endpoint. Do not scrape redirects or guess IDs.
            throw failure("PDD_PARSE_UNSUPPORTED", "暂不支持此拼多多链接，请使用含商品标识的商品链接");
        }
        return sign;
    }

    @Override
    public CommissionQuote quote(String itemId) {
        String item = validateItem(itemId);
        try {
            return checked(switch (platform) {
                case TBK -> quoteTaobao(item);
                case JD -> quoteJd(item);
                case PDD -> quotePdd(item);
            });
        } catch (UnionCouponException unavailable) {
            if (!retryableResponse(unavailable)) throw unavailable;
            try {
                Optional<CommissionQuote> fallback = gateway.quoteFallback(platform, item);
                if (fallback != null && fallback.isPresent()) return checked(fallback.get());
            } catch (RuntimeException fallbackFailure) {
                // Keep the stable, sanitized error even if a secondary supplier is unavailable.
                throw unavailable;
            }
            throw unavailable;
        }
    }

    private CommissionQuote quoteTaobao(String item) {
        DtkGoodsDetailsRequest request = new DtkGoodsDetailsRequest();
        request.setGoodsId(item);
        try {
            DtkGoodsDetailsResponse details = data(request);
            if (details.getGoodsId() != null && !item.equals(details.getGoodsId())
                    && !item.equals(details.getGoodsSign())) throw invalidResponse();
            return checked(new CommissionQuote(percent(details.getCommissionRate()),
                    details.getCouponPrice(), details.getActualPrice()));
        } catch (UnionCouponException unavailable) {
            if (!retryableResponse(unavailable)) throw unavailable;
            // The catalogue is incomplete; the existing privilege endpoint also covers
            // non-catalogue items. Quote using the server's default promotion position.
            DtkGetPrivilegeLinkResponse details = privilege(item, required(gateway.defaultPid(Platform.TBK)));
            BigDecimal coupon = BigDecimal.ZERO;
            if (first(details.getCouponClickUrl()) != null) {
                DtkCouponQueryRequest couponRequest = new DtkCouponQueryRequest();
                couponRequest.setContent(checkedUrl(details.getCouponClickUrl()));
                coupon = data(couponRequest).getCouponAmount();
            }
            return checked(new CommissionQuote(percent(decimal(details.getMaxCommissionRate())),
                    coupon, decimal(details.getActualPrice())));
        }
    }

    private CommissionQuote quoteJd(String item) {
        DtkJdCommodityDetailsRequest request = new DtkJdCommodityDetailsRequest();
        request.setSkuIds(item);
        List<DtkJdCommodityDetailsResponse> list = data(request);
        DtkJdCommodityDetailsResponse details = list.stream().filter(Objects::nonNull)
                .filter(row -> item.equals(row.getSkuId())).findFirst().orElseThrow(this::invalidResponse);
        return new CommissionQuote(percent(decimal(details.getCommissionShare())),
                optionalDecimal(details.getCouponAmount()), decimal(details.getActualPrice()));
    }

    private CommissionQuote quotePdd(String item) {
        DtkPddGoodsDetailsRequest request = new DtkPddGoodsDetailsRequest();
        request.setGoodsSign(item);
        DtkPddGoodsDetailsResponse details = data(request);
        if (details.getGoodsSign() != null && !item.equals(details.getGoodsSign())) throw invalidResponse();
        BigDecimal groupPrice = requiredAmount(details.getMinGroupPrice());
        BigDecimal coupon = Boolean.TRUE.equals(details.getHasCoupon())
                ? requiredAmount(details.getCouponDiscount()) : BigDecimal.ZERO;
        if (details.getCouponMinOrderAmount() != null
                && groupPrice.compareTo(details.getCouponMinOrderAmount()) < 0) coupon = BigDecimal.ZERO;
        // quote has no user attribution in its contract and is cached across users.
        // Personalized prediction belongs to the subsequent actual conversion only.
        return new CommissionQuote(requiredAmount(details.getPromotionRate()).movePointLeft(3), coupon.movePointLeft(2),
                groupPrice.subtract(coupon).movePointLeft(2));
    }

    @Override
    public ConvertedLink convert(String itemId, String attributionToken) {
        String item = validateItem(itemId);
        return new ConvertedLink(switch (platform) {
            case TBK -> convertTaobao(item, attributionToken);
            case JD -> convertJd(item, attributionToken);
            case PDD -> convertPdd(item, attributionToken);
        });
    }

    private String convertTaobao(String item, String pid) {
        String serverPid = required(pid);
        if (!serverPid.matches("mm_[0-9]+_[0-9]+_[0-9]+")) {
            throw failure("PROMOTION_NOT_CONFIGURED", "推广位配置无效");
        }
        DtkGetPrivilegeLinkResponse result = privilege(item, serverPid);
        // Preserve the legacy copy-password flow; the longer token supports newer clients.
        String token = first(result.getLongTpwd(), result.getTpwd());
        if (token != null) {
            if (token.length() > 4096) throw invalidResponse();
            return token;
        }
        return checkedUrl(first(result.getCouponClickUrl(), result.getShortUrl(), result.getItemUrl()));
    }

    private DtkGetPrivilegeLinkResponse privilege(String item, String pid) {
        DtkGetPrivilegeLinkRequest request = new DtkGetPrivilegeLinkRequest();
        request.setGoodsId(item);
        request.setPid(pid);
        // relation_id/channelId and client-supplied URLs are intentionally absent.
        return data(request);
    }

    private String convertJd(String item, String token) {
        DtkJdCommodityTransformLinkRequest request = new DtkJdCommodityTransformLinkRequest();
        request.setUnionId(required(gateway.jdUnionId()));
        request.setMaterialId("https://item.jd.com/" + item + ".html");
        String position = gateway.defaultPid(Platform.JD);
        if (position != null && !position.isBlank()) {
            try {
                long id = Long.parseLong(position);
                if (id <= 0) throw new NumberFormatException();
                request.setPositionId(id);
            } catch (NumberFormatException invalid) {
                throw failure("PROMOTION_NOT_CONFIGURED", "京东推广位配置无效");
            }
        }
        if (token != null && !token.isBlank()) request.setSubUnionId(attribution(token, 80));
        request.setChainType(3);
        var result = data(request);
        return checkedUrl(first(result.getShortUrl(), result.getLongUrl()));
    }

    private String convertPdd(String item, String token) {
        DtkPddTransformLinkRequest request = new DtkPddTransformLinkRequest();
        request.setGoodsSign(item);
        request.setPid(required(gateway.defaultPid(Platform.PDD)));
        if (token != null && !token.isBlank()) {
            // 10 JSON framing bytes plus a <=54-byte ASCII opaque identifier.
            request.setCustomParameters("{\"uid\":\"" + attribution(token, 54) + "\"}");
        }
        DtkPddTransformLinkResponse result = data(request);
        // This response is for the actual user-specific conversion. Do not promise a
        // rebate if the provider explicitly predicted zero price-comparison commission.
        if (result.getPredictPromotionRate() != null && result.getPredictPromotionRate().signum() == 0) {
            throw failure("PDD_PRICE_COMPARISON", "该商品当前比价订单无佣金，请查看页面提示");
        }
        return checkedUrl(first(result.getMobileShortUrl(), result.getMobileUrl(),
                result.getShortUrl(), result.getUrl()));
    }

    private <T> T data(DtkApiRequest<DtkApiResponse<T>> request) {
        DtkApiResponse<T> response;
        try {
            response = gateway.execute(platform, request);
        } catch (UnionCouponException known) {
            throw known;
        } catch (RuntimeException upstream) {
            throw new UnionCouponException("UPSTREAM_FAILURE", "联盟服务暂不可用", upstream);
        }
        if (response == null || !response.isSuccess()) {
            // Provider messages may contain credentials/request details; never return them.
            throw failure("UPSTREAM_FAILURE", "联盟服务暂不可用");
        }
        if (response.getData() == null) throw invalidResponse();
        return response.getData();
    }

    private String validateItem(String item) {
        if (item == null || !item.matches("[A-Za-z0-9_-]{1,200}")) {
            throw failure("PARSE_FAILED", "商品标识无效");
        }
        if (platform == Platform.JD && !item.matches("[1-9][0-9]{0,19}")) {
            throw failure("PARSE_FAILED", "京东商品标识无效");
        }
        if (platform == Platform.PDD && item.matches("[0-9]+")) {
            throw failure("PDD_PARSE_UNSUPPORTED", "需要拼多多 goods_sign 商品标识");
        }
        return item;
    }

    private CommissionQuote checked(CommissionQuote quote) {
        if (quote == null || quote.rate() == null || quote.couponAmount() == null
                || quote.finalPrice() == null || quote.rate().signum() < 0
                || quote.rate().compareTo(BigDecimal.ONE) > 0
                || quote.couponAmount().signum() < 0 || quote.finalPrice().signum() < 0) throw invalidResponse();
        return quote;
    }

    private BigDecimal requiredAmount(BigDecimal value) {
        if (value == null || value.signum() < 0) throw invalidResponse();
        return value;
    }

    private BigDecimal percent(BigDecimal value) { return requiredAmount(value).movePointLeft(2); }

    private BigDecimal decimal(String value) {
        try { return requiredAmount(new BigDecimal(value)); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw invalidResponse(); }
    }

    private BigDecimal optionalDecimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : decimal(value);
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw failure("PROMOTION_NOT_CONFIGURED", "推广配置尚未就绪");
        return value;
    }

    private String attribution(String token, int maxLength) {
        if (!token.matches("[A-Za-z0-9_]{1," + maxLength + "}")) {
            throw failure("ATTRIBUTION_INVALID", "归因标识无效");
        }
        return token;
    }

    private String checkedUrl(String value) {
        URI uri = uri(value, "UPSTREAM_INVALID_RESPONSE");
        return uri.toASCIIString();
    }

    private URI uri(String value, String code) {
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw failure(code, "商品链接无效");
        }
    }

    private boolean hostMatches(URI uri, String host) {
        String actual = uri.getHost().toLowerCase(Locale.ROOT);
        return actual.equals(host) || actual.endsWith("." + host);
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private boolean retryableResponse(UnionCouponException failure) {
        return failure.code().equals("UPSTREAM_FAILURE") || failure.code().equals("UPSTREAM_INVALID_RESPONSE");
    }

    private UnionCouponException invalidResponse() {
        return failure("UPSTREAM_INVALID_RESPONSE", "联盟返回数据不完整，请稍后重试");
    }

    private UnionCouponException failure(String code, String message) { return new UnionCouponException(code, message); }

    @Override public Page<UnionOrderLine> fetchOrders(TimeWindow window, String cursor) { throw orderUnavailable(); }
    @Override public Page<UnionCase> fetchRefunds(TimeWindow window, String cursor) { throw orderUnavailable(); }
    @Override public Page<UnionCase> fetchPenalties(TimeWindow window, String cursor) { throw orderUnavailable(); }

    private UnsupportedOperationException orderUnavailable() {
        return new UnsupportedOperationException("Order ingestion requires the line 10 order adapter");
    }
}
