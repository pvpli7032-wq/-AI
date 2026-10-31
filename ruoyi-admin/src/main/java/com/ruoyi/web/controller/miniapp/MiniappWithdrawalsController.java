package com.ruoyi.web.controller.miniapp;

import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.withdraw.WithdrawalApplyRequest;
import com.ruoyi.system.cps.withdraw.WithdrawalService;

/** Both paths use the login user; no userId, PID or payee is accepted from the client. */
@RestController
@RequestMapping("/miniapp/withdrawals")
public class MiniappWithdrawalsController extends BaseController {
    private final WithdrawalService withdrawals;
    public MiniappWithdrawalsController(WithdrawalService withdrawals) { this.withdrawals = withdrawals; }
    @PostMapping public AjaxResult apply(@RequestBody WithdrawalApplyRequest request) {
        return AjaxResult.success(withdrawals.apply(getUserId(), request));
    }
    @GetMapping public AjaxResult list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int pageSize) {
        AjaxResult result = AjaxResult.success(); result.putAll(withdrawals.list(getUserId(), page, pageSize)); return result;
    }
}
