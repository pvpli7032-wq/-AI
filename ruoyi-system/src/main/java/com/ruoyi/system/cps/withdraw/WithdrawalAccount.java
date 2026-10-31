package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;

/** Read-only locked view; only LedgerService can change its amounts. */
public record WithdrawalAccount(Long userId, BigDecimal balance, BigDecimal withdrawingAmount, String status) {}
