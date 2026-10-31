package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
/** businessKey is a stable idempotency key; all balance mutation belongs to the ledger. */
public record PostingCommand(String businessKey, Long userId, Long orderLineId, String type,
    String debitAccount, String creditAccount, BigDecimal amount, String reason) {}
