package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** amount is the original stored value. userDelta excludes cumulative paid money;
 * internal bucket transfers therefore have zero userDelta, not positive income. */
public record WalletFlow(long id, String flowType, LocalDateTime createTime, BigDecimal amount,
        String debitAccount, String creditAccount, BigDecimal availableDelta, BigDecimal frozenDelta,
        BigDecimal withdrawingDelta, BigDecimal paidDelta, BigDecimal userDelta) {
    public static WalletFlow of(long id, String type, LocalDateTime time, BigDecimal amount,
            String debit, String credit, String originalType) {
        boolean legacy = debit == null && credit == null;
        BigDecimal available = legacy ? amount : delta(amount, debit, credit, "USER_AVAILABLE");
        BigDecimal frozen = legacy ? BigDecimal.ZERO : delta(amount, debit, credit, "USER_FROZEN");
        BigDecimal withdrawing = legacy ? BigDecimal.ZERO : delta(amount, debit, credit, "USER_WITHDRAWING");
        BigDecimal paid = legacy
            ? ("withdraw".equals(type) || ("reversal".equals(type) && "withdraw".equals(originalType))
                ? amount.negate() : BigDecimal.ZERO)
            : delta(amount, debit, credit, "PAYOUT");
        return new WalletFlow(id, type, time, amount, debit, credit, available, frozen, withdrawing,
            paid, available.add(frozen).add(withdrawing));
    }
    private static BigDecimal delta(BigDecimal amount, String debit, String credit, String bucket) {
        return (bucket.equals(credit) ? amount : BigDecimal.ZERO)
            .subtract(bucket.equals(debit) ? amount : BigDecimal.ZERO);
    }
}
