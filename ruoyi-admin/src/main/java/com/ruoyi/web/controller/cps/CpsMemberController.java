package com.ruoyi.web.controller.cps;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.risk.MemberQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Member read views, deliberately separate from mutable wallet or risk operations. */
@RestController
@RequestMapping("/cps/members")
public class CpsMemberController {
    private final MemberQueryService members;
    public CpsMemberController(MemberQueryService members) { this.members = members; }

    @PreAuthorize("@ss.hasPermi('system:commission:user:list')")
    @GetMapping
    public AjaxResult list(@RequestParam(required=false) Long userId,
            @RequestParam(required=false) String nickname, @RequestParam(required=false) String phone,
            @RequestParam(required=false) String riskLevel, @RequestParam(defaultValue="1") int pageNum,
            @RequestParam(defaultValue="10") int pageSize) {
        return AjaxResult.success(members.list(new MemberQueryService.Query(userId,nickname,phone,riskLevel,pageNum,pageSize)));
    }

    @PreAuthorize("@ss.hasPermi('system:commission:user:query')")
    @GetMapping("/{userId}")
    public AjaxResult detail(@PathVariable long userId) {
        var member = members.detail(userId);
        return member == null ? AjaxResult.error("会员不存在") : AjaxResult.success(member);
    }
}
