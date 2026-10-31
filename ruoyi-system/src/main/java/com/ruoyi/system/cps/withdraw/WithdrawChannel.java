package com.ruoyi.system.cps.withdraw;

public interface WithdrawChannel {
    PaymentResult submit(PaymentRequest request);
    PaymentResult query(String withdrawNo);
    PaymentResult handleCallback(ChannelNotification notification);
}
