package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
/** businessKey is persisted as sys_commission_flow.idempotency_key; balance mutation belongs to the ledger. */
public record PostingCommand(String businessKey, Long userId, Long orderLineId, String type,
    AccountType debitAccount, AccountType creditAccount, BigDecimal amount, String reason, Long operatorId) {}
