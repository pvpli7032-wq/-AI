package com.ruoyi.system.cps.attribution;

public record AttributionDecision(Long userId, String method, String confidence, Long decidedBy) {}
