package com.ruoyi.web.controller.miniapp;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.attribution.CouponRequestIdentity;
import com.ruoyi.system.cps.attribution.OrderAttributionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/miniapp/orders")
public class MiniappOrderClaimController {
    private final OrderAttributionService attribution;
    public MiniappOrderClaimController(OrderAttributionService attribution) { this.attribution = attribution; }
    public record ClaimRequest(String platform, String orderNo) {}
    @PostMapping("/claim")
    public AjaxResult claim(@RequestBody ClaimRequest request) {
        var platform = MiniappConvertController.platform(request.platform());
        if (platform == null) return AjaxResult.error("请选择有效的订单平台");
        var result = attribution.claim(CouponRequestIdentity.requireUserId(), platform, request.orderNo());
        return java.util.Set.of("BOUND", "ALREADY_BOUND", "MANUAL_REVIEW").contains(result.code())
            ? AjaxResult.success(result) : AjaxResult.error(result.message()).put("data", result);
    }
}
