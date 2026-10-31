package com.ruoyi.system.cps.attribution;
public record AttributionDecision(Long userId, AttributionMethod method, AttributionConfidence confidence, Long decidedBy) {}
