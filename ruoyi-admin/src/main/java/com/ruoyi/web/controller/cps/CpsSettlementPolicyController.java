package com.ruoyi.web.controller.cps;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.settlement.SettlementConfigurationService;

@RestController
@RequestMapping("/cps/rules/settlement-configuration")
public class CpsSettlementPolicyController extends BaseController {
    private final SettlementConfigurationService configuration;
    public CpsSettlementPolicyController(SettlementConfigurationService configuration) { this.configuration = configuration; }
    @GetMapping @PreAuthorize("@ss.hasPermi('system:commission:rule:query')")
    public AjaxResult history() { return AjaxResult.success(configuration.history()); }
    @PostMapping @PreAuthorize("@ss.hasPermi('system:commission:rule:edit')")
    public AjaxResult publish(@RequestBody SettlementConfigurationService.Settings settings) {
        configuration.publish(settings, getUserId());
        return AjaxResult.success();
    }
}
