package com.ruoyi.system.cps.attribution;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;

/** Preserve legacy response shapes while withholding internal commission and foreign links. */
public final class CouponPresentation {
    private static final Set<String> INTERNAL = Set.of("commission", "commissionrate", "commissionshare",
        "couponcommission", "maxcommissionrate", "commissioninfo", "tkcommrate", "tkrate",
        "promotionrate", "predictpromotionrate", "zkfinalpricewap", "commission_rate", "commission_share",
        "promotion_rate", "predict_promotion_rate", "coupon_commission", "max_commission_rate",
        "commissionamount", "commission_amount", "estimatedcommission", "estimated_commission", "incomeinfo");
    private static final Set<String> PROMOTION_LINKS = Set.of("clickurl", "click_url", "couponshareurl",
        "coupon_share_url", "couponclickurl", "coupon_click_url", "shorturl", "short_url",
        "mobileurl", "mobile_url", "mobileshorturl", "mobile_short_url", "promotionurl",
        "promotion_url", "couponlink", "coupon_link", "tpwd", "longtpwd");
    private CouponPresentation() {}
    public static JSONObject detail(JSONObject source) {
        if (source == null) return null;
        JSONObject copy = JSONObject.parseObject(source.toJSONString());
        scrub(copy);
        copy.put("estimatedRebate", null);
        copy.put("rebateStatus", "PENDING_RULE_CONTRACT");
        return copy;
    }
    private static void scrub(Object value) {
        if (value instanceof JSONObject object) {
            for (String key : new ArrayList<>(object.keySet())) {
                String normalized = key.toLowerCase(Locale.ROOT);
                if (INTERNAL.contains(normalized) || PROMOTION_LINKS.contains(normalized)) object.remove(key);
                else scrub(object.get(key));
            }
        } else if (value instanceof JSONArray array) for (Object child : array) scrub(child);
    }
    public static JSONObject link(CouponResult converted) {
        if (!converted.successful()) throw new ServiceException(converted.message());
        if (converted.value() == null || converted.value().isBlank()) throw new ServiceException("转链结果为空");
        JSONObject result = new JSONObject();
        result.put("value", converted.value());
        result.put("estimatedRebate", converted.estimatedRebate());
        result.put("rebateStatus", converted.rebateStatus());
        result.put("bindingMode", converted.bindingMode());
        result.put("notices", converted.notices());
        // Legacy miniapp pages copy data.model. Keep the original value; a URL is never fabricated into a token.
        JSONObject legacyData = new JSONObject();
        legacyData.put("model", converted.value());
        result.put("data", legacyData);
        if (converted.value().startsWith("https://") || converted.value().startsWith("http://")) {
            result.put("valueType", "URL");
            result.put("clickURL", converted.value()); result.put("clickUrl", converted.value());
            result.put("shortURL", converted.value()); result.put("shortUrl", converted.value());
            result.put("couponClickUrl", converted.value()); result.put("couponShareUrl", converted.value());
            result.put("url", converted.value());
        } else {
            result.put("valueType", "TPWD");
            result.put("tpwd", converted.value()); result.put("longTpwd", converted.value());
            result.put("model", converted.value());
        }
        return result;
    }
}
