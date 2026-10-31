package com.ruoyi.system.cps.withdraw;

public record PaymentResult(String withdrawNo, String channelNo, PaymentStatus status, String reason) {}
