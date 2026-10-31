package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;
/** withdrawNo is the channel idempotency key and must not change on retry. */
public record PaymentRequest(String withdrawNo, BigDecimal amount, String recipientName, String recipientAccount) {}
