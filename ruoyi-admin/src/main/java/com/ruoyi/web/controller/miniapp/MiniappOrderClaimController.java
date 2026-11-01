package com.ruoyi.web.controller.miniapp;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.attribution.CouponRequestIdentity;
import com.ruoyi.system.cps.attribution.OrderAttributionService;
import com.ruoyi.system.cps.attribution.OrderClaimRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/miniapp/orders")
public class MiniappOrderClaimController {
    private static final Logger log = LoggerFactory.getLogger(MiniappOrderClaimController.class);
    private static final String GENERIC_FAILURE = "订单暂未完成绑定，请等待核实或稍后重试";
    private final OrderAttributionService attribution;
    private final OrderClaimRateLimiter limiter;
    public MiniappOrderClaimController(OrderAttributionService attribution, OrderClaimRateLimiter limiter) {
        this.attribution = attribution;
        this.limiter = limiter;
    }
    public record ClaimRequest(String platform, String orderNo) {}
    @PostMapping("/claim")
    public AjaxResult claim(@RequestBody ClaimRequest request) {
        Long userId = CouponRequestIdentity.requireUserId();
        try {
            limiter.check(userId);
            var platform = request == null ? null : MiniappConvertController.platform(request.platform());
            if (platform == null) return AjaxResult.error(GENERIC_FAILURE);
            var result = attribution.claimFromClient(userId, platform, request.orderNo());
            return java.util.Set.of("BOUND", "ALREADY_BOUND").contains(result.code())
                ? AjaxResult.success("订单已绑定") : AjaxResult.error(GENERIC_FAILURE);
        } catch (RuntimeException failure) {
            log.warn("订单认领未完成，用户 {}", userId, failure);
            return AjaxResult.error(GENERIC_FAILURE);
        }
    }
}
