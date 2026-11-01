package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Server-owned adapters only. Never bind these contracts to request JSON. */
public final class RulePorts {
    private RulePorts() { }
    public interface Qualification {
        /** Must use historical, auditable effective records, never today's counters/relationships.
         * Shop/category/JD evidence must be independently authoritative: normalized DTO lacks them.
         * Return null/unknown evidence when history cannot be proved: unproven branches are skipped
         * with persisted manual review, not fabricated counts or a globally blocked ordinary base.
         * Unknown JD operation still blocks when a nonzero JD fee requires that distinction. */
        RuleDecision.Evidence asOf(long orderLineId, long buyerUserId, UnionOrderLine line, Instant paidAt);
    }
    public interface PublicationSafety {
        /** Read authoritative server limits. All rewards MUST live in the published RulePolicy;
         * independent reward-configuration write paths are forbidden by the integration contract. */
        Limits limits(RulePolicy policy);
    }
    /** Authoritative server commission cap only: publication does not apply a margin floor.
     * rewardsDisabled is retained for source compatibility only; policy incentives are now fully costed. */
    public record Limits(BigDecimal maximumActualCommission, boolean rewardsDisabled, String sourceReference) {
        public Limits(BigDecimal maximumActualCommission, String sourceReference) {
            this(maximumActualCommission, false, sourceReference);
        }
    }
}
