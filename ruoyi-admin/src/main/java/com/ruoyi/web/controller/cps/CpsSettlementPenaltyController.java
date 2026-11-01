package com.ruoyi.web.controller.cps;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.cps.settlement.SettlementPenaltyImportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Administrative manual statement import; caller identity is always the authenticated operator. */
@RestController
@RequestMapping("/cps/settlement/penalties")
public class CpsSettlementPenaltyController extends BaseController {
    private final SettlementPenaltyImportService service;
    public CpsSettlementPenaltyController(SettlementPenaltyImportService service) { this.service=service; }

    @PreAuthorize("@ss.hasPermi('system:commission:punish:import')")
    @Log(title="人工处罚明细导入",businessType=BusinessType.IMPORT,isSaveRequestData=false)
    @PostMapping
    public AjaxResult importStatement(@RequestBody SettlementPenaltyImportService.Request request) {
        return success(service.importStatement(request,getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('system:commission:punish:query')")
    @GetMapping("/{id}")
    public AjaxResult result(@PathVariable long id) { return success(service.result(id)); }

    @PreAuthorize("@ss.hasPermi('system:commission:punish:import')")
    @Log(title="重试人工处罚明细",businessType=BusinessType.UPDATE)
    @PostMapping("/{id}/retry")
    public AjaxResult retry(@PathVariable long id) { return success(service.retry(id)); }
}
