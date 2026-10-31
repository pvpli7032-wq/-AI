package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Internal persistence model. Controllers expose explicit redacted views. */
public class MockWithdrawalPayment {
    private String withdrawNo;
    private BigDecimal amount;
    private String recipientHash;
    private String channelNo;
    private PaymentStatus status;
    private String callbackToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getWithdrawNo() { return withdrawNo; }
    public void setWithdrawNo(String withdrawNo) { this.withdrawNo = withdrawNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRecipientHash() { return recipientHash; }
    public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }
    public String getChannelNo() { return channelNo; }
    public void setChannelNo(String channelNo) { this.channelNo = channelNo; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getCallbackToken() { return callbackToken; }
    public void setCallbackToken(String callbackToken) { this.callbackToken = callbackToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
