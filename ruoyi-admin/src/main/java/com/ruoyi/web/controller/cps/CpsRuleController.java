package com.ruoyi.web.controller.cps;

import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.rule.*;

/** Every write uses the authenticated operator; publications remain append-only. */
@RestController
@RequestMapping("/cps/rules")
public class CpsRuleController extends BaseController {
    private final RuleEngine rules;
    public CpsRuleController(RuleEngine rules) { this.rules = rules; }
    @GetMapping @PreAuthorize("@ss.hasPermi('system:commission:rule:list')")
    public AjaxResult list() { return AjaxResult.success(rules.list()); }
    @PostMapping @PreAuthorize("@ss.hasPermi('system:commission:rule:add')")
    public AjaxResult create(@RequestBody RulePolicy policy) {
        return AjaxResult.success(rules.create(policy, getUserId()));
    }
    public record Revision(long revision, RulePolicy policy) { }
    @PutMapping("/{id}") @PreAuthorize("@ss.hasPermi('system:commission:rule:edit')")
    public AjaxResult update(@PathVariable long id, @RequestBody Revision request) {
        return AjaxResult.success(rules.update(id, request.revision(), request.policy(), getUserId()));
    }
    @DeleteMapping("/{id}") @PreAuthorize("@ss.hasPermi('system:commission:rule:remove')")
    public AjaxResult delete(@PathVariable long id, @RequestParam long revision) {
        rules.delete(id, revision, getUserId()); return AjaxResult.success();
    }
    @PostMapping("/{id}/publish") @PreAuthorize("@ss.hasPermi('system:commission:rule:edit')")
    public AjaxResult publish(@PathVariable long id, @RequestParam long revision) {
        return AjaxResult.success(rules.publish(id, revision, getUserId()));
    }
    @GetMapping("/{id}/versions") @PreAuthorize("@ss.hasPermi('system:commission:rule:query')")
    public AjaxResult versions(@PathVariable long id) { return AjaxResult.success(rules.history(id)); }
    public record Trial(RulePolicy policy, RuleDecision.Facts facts, BigDecimal commission) { }
    /** Hypothetical facts are admin-only and never persisted as production qualification. */
    @PostMapping("/trial") @PreAuthorize("@ss.hasPermi('system:commission:rule:query')")
    public AjaxResult trial(@RequestBody Trial request) {
        return AjaxResult.success(rules.trial(request.policy(), request.facts(), request.commission()));
    }
}
