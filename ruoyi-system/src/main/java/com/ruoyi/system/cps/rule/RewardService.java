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
 * Qualification order is serialized freeze-release order, not backdated payment order.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class RewardService {
    private final RewardProgressMapper mapper;
    public RewardService(RewardProgressMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    /** Must run at payment snapshot creation, even if configs is empty. Insert-only and fail on drift. */
    public void capture(Snapshot snapshot) {
        lock(snapshot);
        String prior = mapper.snapshot(snapshot.orderId());
        if (prior != null) {
            // Compare decoded immutable values (set iteration order does not affect equality).
            if (!JSON.parseObject(prior, Snapshot.class).equals(snapshot))
                throw new IllegalStateException("immutable reward snapshot conflict");
            return;
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
        if (!evidence.freezeReleased() || evidence.pausedOrRiskHeld() || evidence.now().isBefore(evidence.freezeUntil()) ||
                !RewardCalculator.valid(s, evidence.paid(), evidence.commission()))
            return new Result(false, replay(orderId));
        long ordinal = mapper.lifetimeCount(s.buyerId()) + 1;
        long count = mapper.validCount(s.buyerId()) + 1;
        List<Award> awards = RewardCalculator.awards(s, evidence, ordinal, count, state.refundRatio());
        mapper.qualify(orderId); // Every valid order contributes, even when no configured reward applies.
        for (Award a : awards) {
            if (mapper.awardExists(a.id()) != 0) continue;
            mapper.insertAward(a);
            append(new Instruction(a.id() + ":A", orderId, a.id(), a.userId(), a.orderId(), a.type(),
                false, a.amount(), BigDecimal.ZERO));
        }
        return new Result(false, replay(orderId));
    }

    public Result refund(long orderId, Refund refund) {
        Snapshot s = snapshot(orderId); lock(s);
        var state = mapper.state(orderId);
        if (refund.cumulativeRatio().compareTo(state.refundRatio()) < 0)
            throw new IllegalArgumentException("cumulative refund cannot decrease");
        boolean valid = !state.closed() && refund.cumulativeRatio().compareTo(BigDecimal.ONE) < 0 &&
            RewardCalculator.valid(s, refund.remainingPaid(), refund.remainingCommission());
        mapper.refund(orderId, refund.cumulativeRatio(), valid);
        long count = mapper.validCount(s.buyerId());
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
        return mapper.validInvitees(inviterId);
    }

    private void append(Instruction instruction) {
        mapper.instruction(instruction.businessKey(), instruction.causeOrderId(), JSON.toJSONString(instruction));
    }
    private void lock(Snapshot s) {
        for (long id : RewardCalculator.lockUsers(s)) { mapper.ensureUser(id); mapper.lockUser(id); }
    }
}
