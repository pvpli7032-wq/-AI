package com.ruoyi.web.controller.cps;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.cps.recon.ReconCoverageRequest;
import com.ruoyi.system.cps.recon.ReconImportRequest;
import com.ruoyi.system.cps.recon.ReconciliationService;

/** Financial evidence is imported explicitly; no live alliance or payout calls are made here. */
@RestController
@RequestMapping("/cps/reconciliation")
public class CpsReconciliationController extends BaseController {
    private final ReconciliationService service;

    public CpsReconciliationController(ReconciliationService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('system:commission:recon:list')")
    @GetMapping("/batches")
    public AjaxResult batches(@RequestParam(required = false) String platform, @RequestParam(required = false) String period) {
        return success(service.batches(platform, period));
    }

    @PreAuthorize("@ss.hasPermi('system:commission:recon:add')")
    @Log(title = "导入对账证据", businessType = BusinessType.IMPORT)
    @PostMapping("/sources")
    public AjaxResult importSource(@RequestBody ReconImportRequest request) {
        return success(service.importSource(request, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('system:commission:recon:list')")
    @GetMapping("/sources")
    public AjaxResult sources(@RequestParam String platform, @RequestParam String period) {
        return success(service.sources(platform, period));
    }

    @PreAuthorize("@ss.hasPermi('system:commission:recon:add')")
    @Log(title = "财务确认到账覆盖", businessType = BusinessType.INSERT)
    @PostMapping("/coverage")
    public AjaxResult allocate(@RequestBody ReconCoverageRequest request) {
        return success(service.allocateReceipt(request, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('system:commission:recon:add')")
    @Log(title = "执行四边对账", businessType = BusinessType.OTHER)
    @PostMapping("/batches")
    public AjaxResult reconcile(@RequestBody RunRequest request) {
        return success(service.reconcile(request.platform(), request.period()));
    }

    @PreAuthorize("@ss.hasPermi('system:commission:recon:list')")
    @GetMapping("/batches/{id}/differences")
    public AjaxResult differences(@PathVariable Long id) { return success(service.differences(id)); }

    @PreAuthorize("@ss.hasPermi('system:commission:recon:edit')")
    @Log(title = "处理对账差异", businessType = BusinessType.UPDATE)
    @PostMapping("/differences/{id}/resolve")
    public AjaxResult resolve(@PathVariable Long id, @RequestBody ResolveRequest request) {
        return success(service.resolve(id, request.note(), SecurityUtils.getUserId()));
    }

    public record RunRequest(String platform, String period) {}
    public record ResolveRequest(String note) {}
}
