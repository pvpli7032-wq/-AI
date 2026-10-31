package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;

/** Stored bucket balances, not a withdrawal-policy decision. Missing accounts are never created. */
public record WalletAccount(boolean accountExists, BigDecimal available, BigDecimal frozen,
        BigDecimal withdrawing, BigDecimal paid, BigDecimal debt) {
    public static WalletAccount of(boolean exists, BigDecimal available, BigDecimal frozen,
            BigDecimal withdrawing, BigDecimal paid) {
        return new WalletAccount(exists, available, frozen, withdrawing, paid,
            available.signum() < 0 ? available.negate() : BigDecimal.ZERO.setScale(2));
    }
}
