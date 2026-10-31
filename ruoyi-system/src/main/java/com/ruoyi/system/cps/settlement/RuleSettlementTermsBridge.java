package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.rule.*;
import com.ruoyi.union.adapter.UnionOrderLine;

/** The single production bridge: both economics and rewards consume the same immutable publication.
 * Ordinary referral is frozen with the order; RewardService exclusively owns the other awards.
 * No current invitation relationship, current policy or client-supplied cost is consulted on repricing. */
@Component
public class RuleSettlementTermsBridge implements SettlementTermsPort, SettlementRewardTermsPort {
    public record Frozen(RuleDecision.Snapshot rule, SettlementConfigurationService.Version configuration,
            Long inviterUserId) { }
    private final RuleEngine rules;
    private final SettlementConfigurationService configuration;
    public RuleSettlementTermsBridge(RuleEngine rules, SettlementConfigurationService configuration) {
        this.rules = rules; this.configuration = configuration;
    }
    @Override public Terms snapshot(long orderId, Long buyer, UnionOrderLine line, Long inviter) {
        var frozen = new Frozen(rules.snapshotFor(orderId, buyer, line), configuration.atPayment(line.paidAt()), inviter);
        return quote(frozen, line.estimatedCommission(), JSON.toJSONString(frozen));
    }
    @Override public Terms recalculate(Terms original, UnionOrderLine line) {
        var frozen = JSON.parseObject(original.ruleSnapshotJson(), Frozen.class);
        if (frozen == null || line.platform()!=frozen.rule().platform()
                || !line.orderNo().equals(frozen.rule().orderNo()) || !line.subOrderNo().equals(frozen.rule().subOrderNo())
                || !line.paidAt().equals(frozen.rule().paidAt()))
            throw new IllegalArgumentException("immutable settlement rule identity mismatch");
        return quote(frozen, line.actualCommission(), original.ruleSnapshotJson());
    }
    private Terms quote(Frozen frozen, BigDecimal commission, String source) {
        var snapshot = frozen.rule();
        var calculation = rules.calculate(snapshot, commission);
        var benefits = new ArrayList<Benefit>();
        benefits.add(new Benefit("REBATE", snapshot.buyerUserId(), calculation.rebate(), snapshot.versionId()));
        BigDecimal referral = BigDecimal.ZERO.setScale(2);
        if (frozen.inviterUserId()!=null) {
            // Invitation commission has no order-count qualification; the authenticated payment
            // invitation decision is authoritative even when historical tier counters are unknown.
            for (var incentive : snapshot.policy().incentives()) {
                if (incentive.kind()!=RulePolicy.IncentiveKind.INVITATION_COMMISSION || !active(incentive, snapshot.paidAt())) continue;
                referral = referral.add(RuleEvaluation.money(calculation.rebate().multiply(incentive.rate())));
            }
            benefits.add(new Benefit("REFERRAL", frozen.inviterUserId(), referral, snapshot.versionId()));
        }
        BigDecimal platform = RuleEvaluation.money(calculation.netCommission().subtract(calculation.rebate()).subtract(referral));
        return new Terms(benefits, snapshot.policy().maximumCommission(),
                JSON.toJSONString(frozen.configuration().settings().freeze()), platform, source);
    }
    @Override public RewardApi.Snapshot snapshot(long orderId, Long buyer, UnionOrderLine line,
            Long inviter, boolean historicalUnknown) {
        var snapshot = rules.snapshotFor(orderId, buyer, line);
        var settings = configuration.atPayment(line.paidAt()).settings();
        List<RewardApi.Config> configs = new ArrayList<>();
        for (var i : snapshot.policy().incentives()) {
            if (i.kind()==RulePolicy.IncentiveKind.INVITATION_COMMISSION || !active(i, line.paidAt())) continue;
            // Unknown historical qualification is never replaced by today's counters.
            if (!snapshot.decision().incentiveKeys().contains(i.key())) continue;
            var type = switch (i.kind()) {
                case INVITATION_FIRST -> RewardApi.Type.INVITEE_FIRST_FIXED;
                case INVITATION_FIRST_N -> RewardApi.Type.INVITEE_FIRST_N_FIXED;
                case ORDER_COUNT -> RewardApi.Type.OWN_QUANTITY;
                case FIRST_N_SUBSIDY -> RewardApi.Type.FIRST_N_SUBSIDY;
                case FORCED_FIRST -> RewardApi.Type.FORCED_FIRST;
                default -> throw new IllegalArgumentException("unsupported award type");
            };
            boolean ratio = i.rate().signum()>0;
            configs.add(new RewardApi.Config(type, snapshot.versionId(), ratio ? RewardApi.Mode.RATIO : RewardApi.Mode.FIXED,
                    Math.toIntExact(Math.max(1, i.n())), ratio ? i.rate() : i.fixed(), Set.of(line.platform().name()),
                    i.startsAt()==null ? line.paidAt() : i.startsAt(),
                    i.endsAt()==null ? line.paidAt().plusNanos(1) : i.endsAt()));
        }
        return new RewardApi.Snapshot(orderId, new RewardApi.OrderKey(line.platform().name(), line.orderNo(), line.subOrderNo()),
                buyer, inviter, line.paidAt(), historicalUnknown, settings.minimumPaid(), settings.minimumCommission(),
                settings.maximumReward(), configs);
    }
    private static boolean active(RulePolicy.Incentive incentive, Instant paidAt) {
        return (incentive.startsAt()==null || !paidAt.isBefore(incentive.startsAt()))
                && (incentive.endsAt()==null || paidAt.isBefore(incentive.endsAt()));
    }
}
