package com.ruoyi.web.controller.miniapp;

import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.rule.RuleActivityService;

@RestController
@RequestMapping("/miniapp/activities")
public class CpsActivityController extends BaseController {
    private final RuleActivityService activities;
    public CpsActivityController(RuleActivityService activities) { this.activities = activities; }
    @GetMapping public AjaxResult list() {
        getUserId();
        return AjaxResult.success(activities.current());
    }
}
