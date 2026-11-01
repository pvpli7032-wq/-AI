package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.mapper.RewardProgressMapper;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/**
 * Settlement integration: call within its transaction, then submit every returned instruction to
 * LedgerService with businessKey as idempotency key before committing. On retry, instructions are
 * replayed from an append-only outbox. No account mutation occurs here. Consumers must use the same
 * database transaction or durably drain the outbox; a returned instruction is NOT proof of payment.
 * First-N eligibility is payment-time ordered. Capture EVERY payment (even empty configs) before
 * settlement release. Later payments wait for earlier captured candidates to qualify or be explicitly
 * excluded. Late backfills preceding an already qualified order fail closed for manual reconciliation;
 * there is no way to infer unreceived historical orders here. Retry deferred orders after predecessors.
 * historicalUnknown blocks rewards only: ordinary buyer rebate is a separate settlement responsibility.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class RewardService {
    private final RewardProgressMapper mapper;
    private final RewardRepricingStore repricing;
    /** Legacy construction cannot perform basis-changing repricing; production uses the JDBC store. */
    public RewardService(RewardProgressMapper mapper) { this(mapper, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public RewardService(RewardProgressMapper mapper, RewardRepricingStore repricing) {
        this.mapper = Objects.requireNonNull(mapper); this.repricing = repricing;
    }

    /** Must run at payment snapshot creation, even if configs is empty. Insert-only and fail on drift. */
    public void capture(Snapshot snapshot) {
        lock(snapshot);
        List<Snapshot> history = history(snapshot.buyerId());
        var prior = history.stream().filter(o -> o.orderId() == snapshot.orderId()).findFirst();
        if (prior.isPresent()) {
            if (!prior.get().equals(snapshot)) throw new IllegalStateException("immutable reward snapshot conflict");
            return;
        }
        for (Snapshot later : history) {
            if (RewardCalculator.PAYMENT_ORDER.compare(snapshot, later) < 0 && mapper.state(later.orderId()).qualified())
                throw new IllegalStateException("late payment precedes qualified reward history; manual reconciliation required");
        }
        mapper.insertSnapshot(snapshot.orderId(), snapshot.order().platform(), snapshot.order().orderNo(),
            snapshot.order().subOrderNo(), snapshot.buyerId(), snapshot.inviterId(), JSON.toJSONString(snapshot));
        mapper.insertState(snapshot.orderId(), snapshot.buyerId());
    }

    public Snapshot snapshot(long orderId) {
        String payload = mapper.snapshot(orderId);
        if (payload == null) throw new IllegalStateException("missing payment-time reward snapshot; manual review required");
        return readSnapshot(payload);
    }

    public Result qualify(long orderId, Qualification evidence) {
        Snapshot s = snapshot(orderId); lock(s);
        var state = mapper.state(orderId);
        if (s.historicalUnknown()) return new Result(true, replay(orderId));
        if (state.qualified() || state.closed()) return new Result(false, replay(orderId));
        if (!evidence.freezeReleased() || evidence.pausedOrRiskHeld() || evidence.now().isBefore(s.paidAt()) ||
                evidence.now().isBefore(evidence.freezeUntil()) ||
                !RewardCalculator.valid(s, evidence.paid(), evidence.commission()))
            return new Result(false, replay(orderId));
        long ordinal = 1, platformOrdinal = 1, count = 1;
        for (Snapshot other : history(s.buyerId())) {
            if (other.orderId() == orderId) continue;
            var otherState = mapper.state(other.orderId());
            if (otherState.valid()) count++;
            if (RewardCalculator.PAYMENT_ORDER.compare(other, s) < 0) {
                if (!otherState.qualified() && !otherState.closed())
                    return new Result(other.historicalUnknown(), replay(orderId));
                if (otherState.qualified()) {
                    ordinal++;
                    if (other.order().platform().equals(s.order().platform())) platformOrdinal++;
                }
            }
        }
        List<Award> awards = RewardCalculator.awards(s, evidence, ordinal, platformOrdinal, count, state.refundRatio());
        mapper.qualify(orderId); // Every valid order contributes, even when no configured reward applies.
        for (Award a : awards) {
            if (mapper.awardExists(a.id()) != 0) continue;
            mapper.insertAward(a);
            append(new Instruction(a.id() + ":A", orderId, a.id(), a.userId(), a.orderId(), a.type(),
                false, a.amount(), BigDecimal.ZERO));
        }
        return new Result(false, replay(orderId));
    }

    /** Append-only repricing before any refund. Caller must pass ordered authoritative evidence and
     * post ALL returned instructions in the same transaction as ordinary repricing. Financial evidence
     * (not wall clock or randomness), chained to the preceding revision, identifies every adjustment.
     * Original grants, payment policies, attribution and lifetime slots are never rewritten.
     */
    public Result reprice(long orderId, Qualification actualEvidence) {
        Snapshot s = snapshot(orderId); lock(s);
        var state = mapper.state(orderId);
        if (s.historicalUnknown()) return new Result(true, replay(orderId));
        if (!state.qualified()) return qualify(orderId, actualEvidence);
        if (!actualEvidence.freezeReleased() || actualEvidence.pausedOrRiskHeld() ||
                actualEvidence.now().isBefore(s.paidAt()) || actualEvidence.now().isBefore(actualEvidence.freezeUntil()))
            throw repricingRequired();
        // Historical pre-release refund baselines lack an exact commission fraction. Never infer one.
        if (state.refundRatio().signum() != 0 || state.baselineRatio().signum() != 0) throw repricingRequired();
        boolean valid = RewardCalculator.valid(s, actualEvidence.paid(), actualEvidence.commission());
        if (state.closed() && valid) throw repricingRequired(); // terminal exclusions never recycle slots
        List<Snapshot> history = history(s.buyerId());
        long count = history.stream().filter(o -> mapper.state(o.orderId()).valid()).count();
        count += (valid ? 1 : 0) - (state.valid() ? 1 : 0);
        List<Award> existing = mapper.awards(s.buyerId());
        long ordinal = 1, platformOrdinal = 1;
        for (Snapshot other : history) {
            if (RewardCalculator.PAYMENT_ORDER.compare(other, s) < 0 && mapper.state(other.orderId()).qualified()) {
                ordinal++;
                if (other.order().platform().equals(s.order().platform())) platformOrdinal++;
            }
        }
        List<Award> desired = valid ? RewardCalculator.awards(s, actualEvidence, ordinal, platformOrdinal,
            count, state.baselineRatio()) : List.of();
        var targets = new java.util.LinkedHashMap<String, Award>();
        for (Award a : desired) targets.put(a.id(), a);
        String evidence = JSON.toJSONString(new java.util.TreeMap<>(java.util.Map.of("orderId", orderId,
            "paid", actualEvidence.paid().stripTrailingZeros().toPlainString(),
            "commission", actualEvidence.commission().stripTrailingZeros().toPlainString(),
            "ordinaryRebate", actualEvidence.userRebate().toPlainString(), "valid", valid, "validCount", count)));
        // Each revision and outbox delta shares the mandatory transaction; failures roll back all changes.
        for (Award a : existing) {
            Award target = targets.remove(a.id());
            boolean quantityLost = a.type() == Type.OWN_QUANTITY && count < a.threshold();
            if (a.orderId() != orderId && !quantityLost) continue;
            BigDecimal amount = quantityLost || target == null ? money(BigDecimal.ZERO) : target.amount();
            adjust(a, amount, orderId, evidence);
        }
        for (Award a : targets.values()) {
            // Repricing is not a new quantity qualification event for an older payment.
            if (a.type() == Type.OWN_QUANTITY) continue;
            // A previously zero ratio/guarantee needs an immutable zero-grant anchor, NOT a new grant.
            if (repricing == null) throw repricingRequired();
            Award anchor = new Award(a.id(), a.userId(), a.orderId(), a.buyerId(), a.type(), a.threshold(),
                money(BigDecimal.ZERO), money(BigDecimal.ZERO));
            mapper.insertAward(anchor);
            adjust(anchor, a.amount(), orderId, evidence);
        }
        if (state.valid() != valid) mapper.refund(orderId, state.refundRatio(), valid);
        return new Result(false, replay(orderId));
    }

    private void adjust(Award award, BigDecimal target, long cause, String evidence) {
        RewardRepricingStore.Basis basis = repricing == null ? null : repricing.basis(award.id());
        BigDecimal current = basis == null ? award.amount().subtract(award.recovered()) :
            basis.entitlement().subtract(basis.refundRecovered());
        // Quantity is a one-shot lifetime award; a lost threshold never creates a fresh grant.
        if (award.type() == Type.OWN_QUANTITY && basis != null && basis.entitlement().signum() == 0 &&
                target.signum() > 0) return;
        if (current.compareTo(target) == 0) return;
        if (repricing == null || award.recovered().signum() != 0 ||
                basis != null && basis.refundRecovered().signum() != 0) throw repricingRequired();
        String previous = basis == null ? "grant:" + award.id() : basis.revisionKey();
        String key = revisionKey(previous + "|" + evidence + "|" + target.toPlainString());
        var next = new RewardRepricingStore.Basis(award.id(), target, money(BigDecimal.ZERO), key);
        repricing.revise(basis, next, award.amount(), evidence);
        append(new Instruction("RP:" + key, cause, award.id(), award.userId(), award.orderId(), award.type(),
            current.compareTo(target) > 0, target.subtract(current).abs(), BigDecimal.ZERO));
    }

    private static String revisionKey(String evidence) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static IllegalStateException repricingRequired() {
        return new IllegalStateException("reward repricing requires append-only basis reconciliation; manual review required");
    }

    /** Explicit terminal exclusion for a final below-threshold/zero-commission candidate.
     * A merely pending commission update must NOT use this method. Ordinary rebate is unaffected.
     */
    public void excludeInvalid(long orderId, Qualification finalEvidence) {
        Snapshot s = snapshot(orderId); lock(s);
        var state = mapper.state(orderId);
        if (!finalEvidence.freezeReleased() || finalEvidence.pausedOrRiskHeld() ||
                finalEvidence.now().isBefore(s.paidAt()) || finalEvidence.now().isBefore(finalEvidence.freezeUntil()) ||
                RewardCalculator.valid(s, finalEvidence.paid(), finalEvidence.commission()))
            throw new IllegalArgumentException("terminal invalid-order evidence required");
        if (state.qualified()) throw new IllegalStateException("qualified contribution must be reversed through refund");
        mapper.refund(orderId, state.refundRatio(), false);
    }

    public Result refund(long orderId, Refund refund) {
        Snapshot s = snapshot(orderId); lock(s);
        var state = mapper.state(orderId);
        if (refund.cumulativeRatio().compareTo(state.refundRatio()) < 0)
            throw new IllegalArgumentException("cumulative refund cannot decrease");
        boolean valid = !state.closed() && refund.cumulativeNumerator().compareTo(refund.cumulativeDenominator()) < 0 &&
            RewardCalculator.valid(s, refund.remainingPaid(), refund.remainingCommission());
        mapper.refund(orderId, refund.cumulativeRatio(), valid);
        long count = history(s.buyerId()).stream().filter(o -> mapper.state(o.orderId()).valid()).count();
        for (Award a : mapper.awards(s.buyerId())) {
            RewardRepricingStore.Basis basis = repricing == null ? null : repricing.basis(a.id());
            if (basis != null) {
                boolean quantityLost = a.type() == Type.OWN_QUANTITY && count < a.threshold();
                if (a.orderId() != orderId && !quantityLost) continue;
                if (state.baselineRatio().signum() != 0 && a.orderId() == orderId) throw repricingRequired();
                BigDecimal target = !valid || quantityLost ? basis.entitlement() :
                    basis.entitlement().multiply(refund.cumulativeNumerator())
                        .divide(refund.cumulativeDenominator(), 2, RoundingMode.HALF_UP).min(basis.entitlement());
                if (target.compareTo(basis.refundRecovered()) <= 0) continue;
                repricing.recover(a.id(), target);
                append(new Instruction("RF:" + basis.revisionKey() + ":" + target.toPlainString(), orderId,
                    a.id(), a.userId(), a.orderId(), a.type(), true,
                    target.subtract(basis.refundRecovered()), refund.cumulativeRatio()));
                continue;
            }
            BigDecimal ratio = BigDecimal.ZERO;
            if (a.orderId() == orderId) {
                // Award amount already excluded pre-release refunds; recover only the additional loss.
                ratio = state.baselineRatio().compareTo(BigDecimal.ONE) == 0 ? BigDecimal.ONE :
                    refund.cumulativeNumerator().subtract(state.baselineRatio().multiply(refund.cumulativeDenominator()))
                        .max(BigDecimal.ZERO).divide(refund.cumulativeDenominator().multiply(
                            BigDecimal.ONE.subtract(state.baselineRatio())), 18, RoundingMode.HALF_UP);
                if (!valid) ratio = BigDecimal.ONE;
            }
            // The triggering order need not be the refunded contribution.
            if (a.type() == Type.OWN_QUANTITY && count < a.threshold()) ratio = BigDecimal.ONE;
            BigDecimal target = a.orderId() == orderId && valid &&
                    !(a.type() == Type.OWN_QUANTITY && count < a.threshold()) ?
                RewardCalculator.recoveryTarget(a, refund, state.baselineRatio()) :
                RewardCalculator.recoveryTarget(a, ratio);
            if (target.compareTo(a.recovered()) <= 0) continue;
            BigDecimal delta = target.subtract(a.recovered());
            mapper.recover(a.id(), target);
            append(new Instruction(a.id() + ":R:" + target.toPlainString(), orderId, a.id(), a.userId(),
                a.orderId(), a.type(), true, delta, ratio));
        }
        return new Result(s.historicalUnknown(), replay(orderId));
    }

    /** Replay includes cross-order quantity recoveries caused by this order; ledger deduplicates keys. */
    public List<Instruction> replay(long causeOrderId) {
        return mapper.instructions(causeOrderId).stream().map(s -> JSON.parseObject(s, Instruction.class)).toList();
    }

    public long validInvitees(long inviterId) {
        if (inviterId <= 0) throw new IllegalArgumentException("invalid inviter");
        mapper.ensureUser(inviterId); mapper.lockUser(inviterId);
        return mapper.validInviteeBuyers(inviterId).stream().distinct().count();
    }

    private List<Snapshot> history(long buyerId) {
        return mapper.buyerSnapshots(buyerId).stream().map(RewardService::readSnapshot).toList();
    }

    /** Explicit decoder for the historical unkeyed JSON format. Never updates the immutable payload. */
    private static Snapshot readSnapshot(String payload) {
        var json = JSON.parseObject(payload);
        var configs = json.getJSONArray("configs");
        if (configs != null) for (int i = 0; i < configs.size(); i++) {
            var config = configs.getJSONObject(i);
            if (!config.containsKey("key")) config.put("key", config.getString("type"));
        }
        return json.toJavaObject(Snapshot.class);
    }

    private void append(Instruction instruction) {
        mapper.instruction(instruction.businessKey(), instruction.causeOrderId(), JSON.toJSONString(instruction));
    }
    private void lock(Snapshot s) {
        for (long id : RewardCalculator.lockUsers(s)) { mapper.ensureUser(id); mapper.lockUser(id); }
    }
}
