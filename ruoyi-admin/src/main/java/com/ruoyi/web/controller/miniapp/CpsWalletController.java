package com.ruoyi.web.controller.miniapp;

import java.time.LocalDateTime;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.ledger.WalletReadService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only routes: identity is exclusively the authenticated principal. */
@RestController
@RequestMapping("/miniapp/wallet")
public class CpsWalletController extends BaseController {
    private final WalletReadService wallets;
    public CpsWalletController(WalletReadService wallets) { this.wallets = wallets; }

    @GetMapping("/account")
    public AjaxResult account() { return AjaxResult.success(wallets.account(getUserId())); }

    @GetMapping("/flows")
    public AjaxResult flows(@RequestParam(required = false) String flowType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return AjaxResult.success(wallets.flows(getUserId(), flowType, from, to, page, pageSize));
    }
}
