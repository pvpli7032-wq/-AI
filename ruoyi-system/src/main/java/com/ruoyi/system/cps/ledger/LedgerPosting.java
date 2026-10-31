package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
/** replayed is true when an existing idempotent posting was returned. */
public record LedgerPosting(Long flowId, String businessKey, BigDecimal balanceAfter, boolean replayed) {}
