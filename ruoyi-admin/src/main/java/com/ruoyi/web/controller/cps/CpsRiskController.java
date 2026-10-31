package com.ruoyi.web.controller.cps;

import java.time.LocalDateTime;
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
import com.ruoyi.system.cps.risk.HealthMonitorService;
import com.ruoyi.system.cps.risk.HealthObservation;
import com.ruoyi.system.cps.risk.RiskService;

@RestController
@RequestMapping("/cps/risk")
public class CpsRiskController extends BaseController {
    private final RiskService risk;
    private final HealthMonitorService health;

    public CpsRiskController(RiskService risk, HealthMonitorService health) {
        this.risk = risk;
        this.health = health;
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("@ss.hasPermi('cps:risk:query')")
    public AjaxResult user(@PathVariable long userId) { return success(risk.evaluateWithdrawal(userId)); }

    @GetMapping("/signals")
    @PreAuthorize("@ss.hasPermi('cps:risk:list')")
    public AjaxResult signals(@RequestParam(required = false) Long userId) { return success(risk.signals(userId)); }

    @PostMapping("/users/{userId}/review")
    @PreAuthorize("@ss.hasPermi('cps:risk:review')")
    @Log(title = "用户风控审核", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    public AjaxResult review(@PathVariable long userId, @RequestBody ReviewRequest request) {
        risk.review(userId, request.blockWithdrawal(), request.pauseRewards(), request.manualReview(), request.reason(), getUserId());
        return success();
    }

    @PostMapping("/evidence/devices")
    @PreAuthorize("@ss.hasPermi('cps:risk:import')")
    @Log(title = "导入设备风险证据", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    public AjaxResult device(@RequestBody DeviceEvidence request) {
        risk.recordDeviceRegistration(request.userId(), request.deviceHash(), request.eventKey(), request.observedAt(), getUserId());
        return success();
    }

    @PostMapping("/scan")
    @PreAuthorize("@ss.hasPermi('cps:risk:review')")
    @Log(title = "风险信号检查", businessType = BusinessType.OTHER)
    public AjaxResult scan() { return success(risk.scan()); }

    @GetMapping("/alerts")
    @PreAuthorize("@ss.hasPermi('cps:health:list')")
    public AjaxResult alerts() { return success(health.alerts()); }

    @PostMapping("/evidence/health")
    @PreAuthorize("@ss.hasPermi('cps:health:import')")
    @Log(title = "导入健康检查证据", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    public AjaxResult observe(@RequestBody HealthObservation observation) {
        health.record(observation, getUserId());
        return success();
    }

    public record ReviewRequest(boolean blockWithdrawal, boolean pauseRewards, boolean manualReview, String reason) {}
    public record DeviceEvidence(long userId, String deviceHash, String eventKey, LocalDateTime observedAt) {}
}
