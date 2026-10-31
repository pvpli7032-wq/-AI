package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The authenticated user and the verified payee are resolved on the server. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WithdrawalApplyRequest(BigDecimal amount, String requestId) {}
