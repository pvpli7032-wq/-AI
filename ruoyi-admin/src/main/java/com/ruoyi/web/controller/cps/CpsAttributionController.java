package com.ruoyi.web.controller.cps;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.cps.attribution.*;
import com.ruoyi.union.adapter.Platform;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/commission")
public class CpsAttributionController {
    private final PidPoolService pids;
    private final OrderAttributionService orders;
    private final InviteBindingService invitations;
    public CpsAttributionController(PidPoolService pids, OrderAttributionService orders, InviteBindingService invitations) {
        this.pids = pids; this.orders = orders; this.invitations = invitations;
    }
    public record PidRequest(Platform platform, String pid, PidType type, boolean enabled, String reason) {}
    public record AssignmentRequest(Long userId, String reason) {}
    public record InviterRequest(Long inviterUserId, String reason) {}
    @PreAuthorize("@ss.hasPermi('system:commission:pid:list')")
    @GetMapping("/pid/occupancy")
    public AjaxResult occupancy(@RequestParam Platform platform, @RequestParam(required = false) String itemId) {
        return AjaxResult.success(pids.occupancy(platform, itemId));
    }
    @PreAuthorize("@ss.hasPermi('system:commission:pid:edit')")
    @Log(title = "推广位配置", businessType = BusinessType.UPDATE)
    @PutMapping("/pid")
    public AjaxResult register(@RequestBody PidRequest request) {
        pids.register(request.platform(), request.pid(), request.type(), request.enabled(), SecurityUtils.getUserId(), request.reason());
        return AjaxResult.success();
    }
    @PreAuthorize("@ss.hasPermi('system:commission:unclaimed:list')")
    @GetMapping("/unclaimed")
    public AjaxResult unclaimed(@RequestParam(required = false) Platform platform,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        return AjaxResult.success(orders.unclaimed(platform, status, Math.min(Math.max(limit, 1), 500)));
    }
    @PreAuthorize("@ss.hasPermi('system:commission:unclaimed:edit')")
    @Log(title = "订单人工归因", businessType = BusinessType.UPDATE)
    @PostMapping("/unclaimed/{orderLineId}/assign")
    public AjaxResult assign(@PathVariable Long orderLineId, @RequestBody AssignmentRequest request) {
        return AjaxResult.success(orders.manualAssign(orderLineId, request.userId(), SecurityUtils.getUserId(), request.reason()));
    }
    @PreAuthorize("@ss.hasPermi('system:commission:unclaimed:edit')")
    @Log(title = "无主订单到期处理", businessType = BusinessType.UPDATE)
    @PostMapping("/unclaimed/expire")
    public AjaxResult expire() { return AjaxResult.success(orders.expireUnclaimed(100)); }
    @PreAuthorize("@ss.hasPermi('system:commission:unclaimed:edit')")
    @Log(title = "归因后结算重试", businessType = BusinessType.UPDATE)
    @PostMapping("/unclaimed/retry-settlement")
    public AjaxResult retry() { return AjaxResult.success(orders.retrySettlement(100)); }
    @PreAuthorize("@ss.hasPermi('system:commission:unclaimed:list')")
    @GetMapping("/unclaimed/failed-settlements")
    public AjaxResult failedSettlements(@RequestParam(defaultValue = "100") int limit) {
        return AjaxResult.success(orders.failedSettlements(Math.min(Math.max(limit, 1), 500)));
    }
    @PreAuthorize("@ss.hasPermi('system:commission:user:edit')")
    @Log(title = "邀请人变更", businessType = BusinessType.UPDATE)
    @PutMapping("/invites/{userId}")
    public AjaxResult changeInviter(@PathVariable Long userId, @RequestBody InviterRequest request) {
        invitations.changeInviter(userId, request.inviterUserId(), SecurityUtils.getUserId(), request.reason());
        return AjaxResult.success();
    }
}
