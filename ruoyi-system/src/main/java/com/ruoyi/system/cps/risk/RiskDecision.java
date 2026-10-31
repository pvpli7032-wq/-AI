package com.ruoyi.system.cps.risk;

import java.util.List;

/** A read-only risk decision. No balance or reward is changed by this value. */
public record RiskDecision(boolean blocked, boolean manualReview, boolean rewardsPaused, List<String> reasons) {
    public RiskDecision {
        reasons = List.copyOf(reasons);
    }
}
