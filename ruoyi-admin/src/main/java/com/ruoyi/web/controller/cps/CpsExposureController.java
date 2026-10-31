package com.ruoyi.web.controller.cps;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.cps.recon.ExposureService;

@RestController
@RequestMapping("/cps/exposure")
public class CpsExposureController extends BaseController {
    private final ExposureService service;

    public CpsExposureController(ExposureService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('system:commission:exposure:list')")
    @GetMapping
    public AjaxResult current() { return success(service.current()); }

    @PreAuthorize("@ss.hasPermi('system:commission:exposure:list')")
    @GetMapping("/positions")
    public AjaxResult positions() { return success(service.positions()); }

    @PreAuthorize("@ss.hasPermi('system:commission:exposure:add')")
    @Log(title = "记录垫付敞口快照", businessType = BusinessType.INSERT)
    @PostMapping("/snapshots")
    public AjaxResult snapshot() { return success(service.capture()); }
}
