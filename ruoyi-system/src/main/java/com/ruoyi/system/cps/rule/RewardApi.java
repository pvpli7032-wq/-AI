package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Caller supplies authenticated attribution and payment-time configuration, never live referral lookup. */
public final class RewardApi {
    private RewardApi() {}
    public enum Type { INVITATION_RATIO, INVITEE_FIRST_FIXED, INVITEE_FIRST_N_FIXED, OWN_QUANTITY, FIRST_N_SUBSIDY, FORCED_FIRST }
    public enum Mode { FIXED, RATIO }
    public record OrderKey(String platform, String orderNo, String subOrderNo) {
        public OrderKey {
            text(platform); text(orderNo); Objects.requireNonNull(subOrderNo);
            if (platform.length() > 20 || orderNo.length() > 100 || subOrderNo.length() > 100)
                throw new IllegalArgumentException("order identity exceeds storage limits");
        }
    }
    /** FORCED_FIRST uses FIXED value as minimum TOTAL ordinary rebate, not an extra fixed bonus.
     * Its first-valid entitlement is per buyer/platform lifetime; n must be 1.
     */
    public record Config(String key, Type type, long version, Mode mode, int n, BigDecimal value,
                         Set<String> platforms, Instant from, Instant until) {
        /** Explicit legacy API: stable type-only identity, never publication-version identity. */
        public Config(Type type, long version, Mode mode, int n, BigDecimal value,
                      Set<String> platforms, Instant from, Instant until) {
            this(type.name(), type, version, mode, n, value, platforms, from, until);
        }
        public Config {
            text(key);
            Objects.requireNonNull(type); Objects.requireNonNull(mode);
            if (version <= 0 || n <= 0) throw new IllegalArgumentException("version/n must be positive");
            value = nonnegative(value); platforms = Set.copyOf(platforms);
            Objects.requireNonNull(from); Objects.requireNonNull(until);
            if (!from.isBefore(until)) throw new IllegalArgumentException("empty activity window");
            if (mode == Mode.RATIO) { ratio(value); value = value.stripTrailingZeros(); }
            if (type == Type.INVITATION_RATIO ? mode != Mode.RATIO :
                    type != Type.FIRST_N_SUBSIDY && mode != Mode.FIXED)
                throw new IllegalArgumentException("unsupported reward mode");
            if (type == Type.FORCED_FIRST && n != 1)
                throw new IllegalArgumentException("FORCED_FIRST requires n=1");
            if (mode == Mode.FIXED) value = money(value);
        }
        public boolean applies(Snapshot s) {
            return platforms.contains(s.order().platform()) && !s.paidAt().isBefore(from) && s.paidAt().isBefore(until);
        }
    }
    /** historicalUnknown prevents ALL automatic reward qualification, including own subsidies.
     * It MUST NOT prevent settlement from posting the separate ordinary buyer rebate.
     */
    public record Snapshot(long orderId, OrderKey order, long buyerId, Long inviterId, Instant paidAt,
                           boolean historicalUnknown, BigDecimal minimumPaid, BigDecimal minimumCommission,
                           BigDecimal maximumReward, List<Config> configs) {
        public Snapshot {
            if (orderId <= 0 || buyerId <= 0 || inviterId != null && (inviterId <= 0 || inviterId == buyerId))
                throw new IllegalArgumentException("invalid user/order identity");
            Objects.requireNonNull(order); Objects.requireNonNull(paidAt);
            minimumPaid = money(nonnegative(minimumPaid)); minimumCommission = money(nonnegative(minimumCommission));
            maximumReward = money(nonnegative(maximumReward)); configs = List.copyOf(configs);
            Set<String> keys = new HashSet<>();
            for (Config c : configs) if (!keys.add(c.key())) throw new IllegalArgumentException("duplicate reward key");
        }
    }
    /** freezeReleased is authoritative settlement evidence; paused/risk-held orders must pass false.
     * userRebate is the current ordinary rebate ONLY, excluding every reward/guarantee instruction.
     */
    public record Qualification(Instant now, Instant freezeUntil, boolean freezeReleased,
                                boolean pausedOrRiskHeld, BigDecimal paid, BigDecimal commission,
                                BigDecimal userRebate) {
        public Qualification {
            Objects.requireNonNull(now); Objects.requireNonNull(freezeUntil);
            paid = nonnegative(paid); commission = nonnegative(commission); userRebate = money(nonnegative(userRebate));
        }
    }
    /** Cumulative loss. Ratio is storage/informational only; monetary recovery uses the exact fraction. */
    public record Refund(BigDecimal cumulativeRatio, BigDecimal remainingPaid, BigDecimal remainingCommission,
                         BigDecimal cumulativeNumerator, BigDecimal cumulativeDenominator) {
        /** Legacy callers explicitly provide a finite decimal ratio. */
        public Refund(BigDecimal cumulativeRatio, BigDecimal remainingPaid, BigDecimal remainingCommission) {
            this(cumulativeRatio, remainingPaid, remainingCommission, cumulativeRatio, BigDecimal.ONE);
        }
        public Refund {
            cumulativeRatio = ratio(cumulativeRatio); remainingPaid = nonnegative(remainingPaid);
            remainingCommission = nonnegative(remainingCommission);
            cumulativeNumerator = nonnegative(cumulativeNumerator);
            if (Objects.requireNonNull(cumulativeDenominator).signum() <= 0 ||
                    cumulativeNumerator.compareTo(cumulativeDenominator) > 0)
                throw new IllegalArgumentException("invalid cumulative recovery fraction");
            if (cumulativeRatio.compareTo(cumulativeNumerator.divide(cumulativeDenominator, 18, RoundingMode.HALF_UP)) != 0)
                throw new IllegalArgumentException("recovery ratio disagrees with exact fraction");
        }
        public static Refund fromCommissionRecovery(BigDecimal cumulativeRecovered, BigDecimal originalCommission,
                                                     BigDecimal remainingPaid, BigDecimal remainingCommission) {
            nonnegative(cumulativeRecovered);
            if (Objects.requireNonNull(originalCommission).signum() <= 0)
                throw new IllegalArgumentException("original commission must be positive");
            return new Refund(cumulativeRecovered.divide(originalCommission, 18, RoundingMode.HALF_UP),
                remainingPaid, remainingCommission, cumulativeRecovered, originalCommission);
        }
    }
    public record Award(String id, long userId, long orderId, long buyerId, Type type,
                        int threshold, BigDecimal amount, BigDecimal recovered) {}
    /**
     * Append-only outbox. Settlement passes businessKey to LedgerService; amount is always positive
     * and authoritative. cumulativeRecoverRatio is award-relative (excluding pre-grant loss), rounded
     * to 18 decimal places for information only; never recalculate the posted amount from this ratio.
     */
    public record Instruction(String businessKey, long causeOrderId, String awardId, long userId,
                              long awardOrderId, Type type, boolean recovery, BigDecimal amount,
                              BigDecimal cumulativeRecoverRatio) {}
    public record Result(boolean manualReview, List<Instruction> instructions) {
        public Result { instructions = List.copyOf(instructions); }
    }
    public static BigDecimal money(BigDecimal v) {
        BigDecimal result = v.setScale(2, RoundingMode.HALF_UP);
        if (result.precision() > 18) throw new IllegalArgumentException("amount exceeds storage limits");
        return result;
    }
    static BigDecimal nonnegative(BigDecimal v) {
        if (Objects.requireNonNull(v).signum() < 0) throw new IllegalArgumentException("negative amount"); return v;
    }
    static BigDecimal ratio(BigDecimal v) {
        if (nonnegative(v).compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("ratio outside [0,1]");
        if (v.stripTrailingZeros().scale() > 18) throw new IllegalArgumentException("ratio exceeds storage precision");
        return v;
    }
    static void text(String v) { if (Objects.requireNonNull(v).isBlank()) throw new IllegalArgumentException("blank identity"); }
}
