package com.ruoyi.web.controller.miniapp;

import java.util.Locale;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.attribution.CouponConversionService;
import com.ruoyi.system.cps.attribution.CouponRequestIdentity;
import com.ruoyi.system.cps.attribution.CouponResult;
import com.ruoyi.union.adapter.Platform;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/miniapp/convert")
public class MiniappConvertController {
    private final CouponConversionService service;
    public MiniappConvertController(CouponConversionService service) { this.service = service; }
    public record ParseRequest(String platform, String input) {}
    public record LinkRequest(String platform, String itemId) {}
    @Anonymous
    @PostMapping("/parse")
    public AjaxResult parse(@RequestBody ParseRequest request) {
        return result(service.parse(CouponRequestIdentity.optionalUserId(), CouponRequestIdentity.remoteAddress(),
            platform(request.platform()), request.input()));
    }
    @Anonymous
    @PostMapping("/link")
    public AjaxResult link(@RequestBody LinkRequest request) {
        return result(service.convert(CouponRequestIdentity.optionalUserId(), CouponRequestIdentity.remoteAddress(),
            platform(request.platform()), request.itemId()));
    }
    private AjaxResult result(CouponResult result) {
        return result.successful() ? AjaxResult.success(result) : AjaxResult.error(result.message()).put("data", result);
    }
    static Platform platform(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Platform.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
