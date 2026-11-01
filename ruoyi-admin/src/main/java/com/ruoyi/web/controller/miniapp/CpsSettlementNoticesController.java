package com.ruoyi.web.controller.miniapp;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.settlement.SettlementPddNoticeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Only the authenticated recipient may read the persisted outcome text. */
@RestController
@RequestMapping("/miniapp/wallet/notices")
public class CpsSettlementNoticesController extends BaseController {
    private final SettlementPddNoticeService notices;
    public CpsSettlementNoticesController(SettlementPddNoticeService notices) { this.notices=notices; }
    @GetMapping
    public AjaxResult list(@RequestParam(defaultValue="20") int limit) {
        return AjaxResult.success(notices.list(getUserId(),limit));
    }
}
