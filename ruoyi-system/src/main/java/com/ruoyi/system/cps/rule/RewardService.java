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
    public RewardService(RewardProgressMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

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
        return JSON.parseObject(payload, Snapshot.class);
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
        boolean valid = !state.closed() && refund.cumulativeRatio().compareTo(BigDecimal.ONE) < 0 &&
            RewardCalculator.valid(s, refund.remainingPaid(), refund.remainingCommission());
        mapper.refund(orderId, refund.cumulativeRatio(), valid);
        long count = history(s.buyerId()).stream().filter(o -> mapper.state(o.orderId()).valid()).count();
        for (Award a : mapper.awards(s.buyerId())) {
            BigDecimal ratio = BigDecimal.ZERO;
            if (a.orderId() == orderId) {
                // Award amount already excluded pre-release refunds; recover only the additional loss.
                ratio = state.baselineRatio().compareTo(BigDecimal.ONE) == 0 ? BigDecimal.ONE :
                    refund.cumulativeRatio().subtract(state.baselineRatio()).max(BigDecimal.ZERO)
                        .divide(BigDecimal.ONE.subtract(state.baselineRatio()), 18, RoundingMode.HALF_UP);
                if (!valid) ratio = BigDecimal.ONE;
            }
            // The triggering order need not be the refunded contribution.
            if (a.type() == Type.OWN_QUANTITY && count < a.threshold()) ratio = BigDecimal.ONE;
            BigDecimal target = a.orderId() == orderId && valid &&
                    !(a.type() == Type.OWN_QUANTITY && count < a.threshold()) ?
                RewardCalculator.recoveryTarget(a, refund.cumulativeRatio(), state.baselineRatio()) :
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
        return mapper.buyerSnapshots(buyerId).stream().map(p -> JSON.parseObject(p, Snapshot.class)).toList();
    }

    private void append(Instruction instruction) {
        mapper.instruction(instruction.businessKey(), instruction.causeOrderId(), JSON.toJSONString(instruction));
    }
    private void lock(Snapshot s) {
        for (long id : RewardCalculator.lockUsers(s)) { mapper.ensureUser(id); mapper.lockUser(id); }
    }
}
