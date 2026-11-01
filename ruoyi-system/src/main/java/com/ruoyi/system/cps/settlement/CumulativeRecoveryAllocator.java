package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure allocation of cumulative ALLIANCE COMMISSION recovery, never refund_fee.
 * Snapshots are the originally awarded rebate/referral/subsidy, in stable persisted order.
 *
 * Independent HALF_UP per item plus a last-item residual is impossible in general:
 * for three 0.01 snapshots, ratio 0.49 gives [0,0,0.01], but ratio 0.50 gives
 * [0.01,0.01,0], decreasing the last item. Two 0.01 snapshots at 0.50 can even
 * exhaust a rounded pool before a zero-valued last item.
 *
 * Explicit choice: round the proportional TOTAL pool HALF_UP, then sequentially
 * apportion the remaining integer cents against remaining snapshot weights HALF_UP.
 * The last item owns the residual. Thus individual exact-ratio rounding yields to
 * conservation, caps and monotonicity. For integer budget B and weight a <= S,
 * round(B*a/S) and B-round(B*a/S) both increase by 0 or 1 when B increases by 1.
 * Induction proves every item is monotone; endpoints 0 and full imply item caps.
 * No iteration per cent and no floating point are used. Do not reorder snapshots
 * between events; posting deltas and persisting recovered totals must be atomic upstream.
 */
public final class CumulativeRecoveryAllocator {
    private CumulativeRecoveryAllocator() { }

    public static List<BigDecimal> cumulativeTargets(BigDecimal originalCommission,
            BigDecimal cumulativeRecovered, List<BigDecimal> snapshotAmounts) {
        nonnegative(originalCommission);
        nonnegative(cumulativeRecovered);
        if (cumulativeRecovered.compareTo(originalCommission) > 0) {
            throw new IllegalArgumentException("cumulative recovery exceeds original commission");
        }
        Objects.requireNonNull(snapshotAmounts, "snapshotAmounts");
        List<BigDecimal> weights = snapshotAmounts.stream()
                .map(CumulativeRecoveryAllocator::cents).toList();
        BigDecimal remainingWeight = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budget = originalCommission.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : remainingWeight.multiply(cumulativeRecovered)
                    .divide(originalCommission, 2, RoundingMode.HALF_UP);
        List<BigDecimal> result = new ArrayList<>(weights.size());
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal weight = weights.get(i);
            BigDecimal target = remainingWeight.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                    : i == weights.size() - 1 ? budget
                    : budget.multiply(weight).divide(remainingWeight, 2, RoundingMode.HALF_UP);
            result.add(target);
            budget = budget.subtract(target);
            remainingWeight = remainingWeight.subtract(weight);
        }
        return List.copyOf(result);
    }

    /** Positive debits only. A repeated or stale smaller cumulative target is a no-op,
     * not a credit/reversal. Caller retains max(prior, target) as recovered-to-date.
     */
    public static List<BigDecimal> differences(List<BigDecimal> targets, List<BigDecimal> recovered) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(recovered, "recovered");
        if (targets.size() != recovered.size()) {
            throw new IllegalArgumentException("target/recovered sizes differ");
        }
        List<BigDecimal> result = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            result.add(cents(targets.get(i)).subtract(cents(recovered.get(i)))
                    .max(BigDecimal.ZERO.setScale(2)));
        }
        return List.copyOf(result);
    }

    private static void nonnegative(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) { throw new IllegalArgumentException("negative amount"); }
    }

    private static BigDecimal cents(BigDecimal amount) {
        nonnegative(amount);
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("snapshot/target/recovered must be exact cents", ex);
        }
    }
}
