package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
public record LedgerPosting(Long flowId, String businessKey, BigDecimal balanceAfter) {}
