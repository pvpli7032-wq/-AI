package com.ruoyi.web.controller.cps;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.withdraw.*;
import java.util.Map;

@RestController
@RequestMapping("/cps/withdrawals")
public class CpsWithdrawalController extends BaseController {
    private final WithdrawalService withdrawals;
    private final ObjectProvider<MockWithdrawChannel> mock;
    public CpsWithdrawalController(WithdrawalService withdrawals, ObjectProvider<MockWithdrawChannel> mock) {
        this.withdrawals = withdrawals; this.mock = mock;
    }
    @GetMapping @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:list')")
    public AjaxResult list(@RequestParam(required=false) String status, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int pageSize) {
        AjaxResult result = AjaxResult.success(); result.putAll(withdrawals.listAdmin(status, page, pageSize)); return result;
    }
    public record Review(boolean approve, String reason) {}
    @PostMapping("/{withdrawNo}/audit") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult audit(@PathVariable String withdrawNo, @RequestBody Review review) {
        return AjaxResult.success(withdrawals.audit(withdrawNo, review.approve(), getUserId(), review.reason()));
    }
    @PostMapping("/{withdrawNo}/pay") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult pay(@PathVariable String withdrawNo) { return AjaxResult.success(withdrawals.pay(withdrawNo, getUserId())); }
    // Refreshing a channel result can settle or release frozen funds, so it requires write permission.
    @PostMapping("/{withdrawNo}/query") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult query(@PathVariable String withdrawNo) { return AjaxResult.success(withdrawals.query(withdrawNo)); }
    @GetMapping("/policy") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:query')")
    public AjaxResult policy() { return AjaxResult.success(JSON.parseObject(withdrawals.configuration())); }
    @PutMapping("/policy") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult policy(@RequestBody Map<String,Object> config) {
        withdrawals.configure(JSON.toJSONString(config), getUserId()); return AjaxResult.success();
    }
    public record Payee(String channel, String name, String account, boolean verified, boolean identityVerified,
        String verificationRef, String reason) {}
    // Explicit audit stores masked change details; the generic request logger must not log these bodies.
    @PutMapping("/recipients/{userId}") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult payee(@PathVariable long userId, @RequestBody Payee payee) {
        withdrawals.bindRecipient(userId, payee.channel(), payee.name(), payee.account(), payee.verified(),
            payee.identityVerified(), payee.verificationRef(), getUserId(), payee.reason());
        return AjaxResult.success();
    }
    public record Group(String group, String reason) {}
    @PutMapping("/groups/{userId}") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult group(@PathVariable long userId, @RequestBody Group group) {
        withdrawals.assignGroup(userId, group.group(), getUserId(), group.reason()); return AjaxResult.success();
    }
    public record MockResult(PaymentStatus status) {}
    @PostMapping("/{withdrawNo}/mock-result") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult mockResult(@PathVariable String withdrawNo, @RequestBody MockResult result) {
        MockWithdrawChannel channel = mock.getIfAvailable();
        if (channel == null) throw new ServiceException("模拟通道未启用");
        channel.simulate(withdrawNo, result.status()); return AjaxResult.success(withdrawals.query(withdrawNo));
    }
    @PostMapping("/mock-callback") @PreAuthorize("@ss.hasPermi('system:commission:withdrawal:edit')")
    public AjaxResult mockCallback(@RequestBody ChannelNotification notification) { return AjaxResult.success(withdrawals.callback(notification)); }
}
