package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.ruoyi.union.adapter.Platform;

/** Decision and evidence are persisted with full configuration; never resolve current policy to settle. */
public record RuleDecision(String proportionKey, BigDecimal userRate, List<String> incentiveKeys,
        List<String> publicActivities, List<String> manualReviewReasons) {
    public RuleDecision {
        incentiveKeys = List.copyOf(incentiveKeys); publicActivities = List.copyOf(publicActivities);
        manualReviewReasons = manualReviewReasons == null ? List.of() : List.copyOf(manualReviewReasons);
    }
    public RuleDecision(String proportionKey, BigDecimal userRate, List<String> incentiveKeys, List<String> publicActivities) {
        this(proportionKey, userRate, incentiveKeys, publicActivities, List.of());
    }
    /** Null is unknown, NOT zero/false. Counts exclude this order and refunded/self purchases.
     * Provider must reconstruct evidence AS OF paidAt, including historical inviter/qualification.
     * sourceReference identifies durable, auditable source records, not caller-supplied assertions. */
    public record Evidence(Instant asOf, String sourceReference, Long validOrders,
            BigDecimal validAmount, Long validInvitations, Long inviterUserId, Boolean selfPurchase,
            Instant joinedAt, Instant lastValidOrderAt, Long returnOrderOrdinal,
            String shopId, String categoryId, Boolean jdSelfOperated) {
        public static Evidence unknown(Instant at) {
            return new Evidence(at, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
    public record Facts(long buyerUserId, Platform platform, String itemId, Instant paidAt,
            BigDecimal estimatedCommission, Evidence evidence) { }
    public record Snapshot(long orderLineId, long buyerUserId, Platform platform, String orderNo,
            String subOrderNo, String itemId, Instant paidAt, long versionId, RulePolicy policy,
            Evidence evidence, RuleDecision decision) { }
    /** Advisory cost breakdown ONLY. RewardService owns reservation, freeze, posting and reversal.
     * Never post these payments in addition to RewardService results. All ratios use rebate, not commission. */
    public record Payment(String key, RulePolicy.IncentiveKind kind, Long recipientUserId,
            BigDecimal amount, boolean requiresValidOrderAndFreeze) { }
    public record Calculation(BigDecimal netCommission, BigDecimal rebate, List<Payment> rewards,
            BigDecimal payoutCost, BigDecimal margin) {
        public Calculation { rewards = List.copyOf(rewards); }
    }
}
