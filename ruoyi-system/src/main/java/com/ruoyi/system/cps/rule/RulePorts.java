package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Server-owned adapters only. Never bind these contracts to request JSON. */
public final class RulePorts {
    private RulePorts() { }
    public interface Qualification {
        /** Must use historical, auditable effective records, never today's counters/relationships.
         * Shop/category/JD evidence must be independently authoritative: normalized DTO lacks them. */
        RuleDecision.Evidence asOf(long orderLineId, long buyerUserId, UnionOrderLine line, Instant paidAt);
    }
    public interface PublicationSafety {
        /** Read trusted server limits and certify that independent Reward* rules cannot stack.
         * Until a trusted reward cost adapter exists, rewardsDisabled must be true or publishing fails. */
        Limits limits(RulePolicy policy);
    }
    public record Limits(BigDecimal maximumActualCommission, BigDecimal minimumMargin,
            boolean rewardsDisabled, String sourceReference) { }
}
