package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Internal persistence model. Controllers expose explicit redacted views. */
public class WithdrawalRecord {
    private Long id;
    private String withdrawNo;
    private Long userId;
    private BigDecimal amount;
    private BigDecimal fee;
    private WithdrawalStatus status;
    private String channel;
    private String channelNo;
    private Long auditBy;
    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime auditAt;
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;
    private String requestKey;
    private Long recipientId;
    private String recipientName;
    private String recipientAccount;
    private String recipientHash;
    private String reviewReasons;
    private String policySnapshot;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWithdrawNo() { return withdrawNo; }
    public void setWithdrawNo(String withdrawNo) { this.withdrawNo = withdrawNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public WithdrawalStatus getStatus() { return status; }
    public void setStatus(WithdrawalStatus status) { this.status = status; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getChannelNo() { return channelNo; }
    public void setChannelNo(String channelNo) { this.channelNo = channelNo; }
    public Long getAuditBy() { return auditBy; }
    public void setAuditBy(Long auditBy) { this.auditBy = auditBy; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getAuditAt() { return auditAt; }
    public void setAuditAt(LocalDateTime auditAt) { this.auditAt = auditAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }
    public Long getRecipientId() { return recipientId; }
    public void setRecipientId(Long recipientId) { this.recipientId = recipientId; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getRecipientAccount() { return recipientAccount; }
    public void setRecipientAccount(String recipientAccount) { this.recipientAccount = recipientAccount; }
    public String getRecipientHash() { return recipientHash; }
    public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }
    public String getReviewReasons() { return reviewReasons; }
    public void setReviewReasons(String reviewReasons) { this.reviewReasons = reviewReasons; }
    public String getPolicySnapshot() { return policySnapshot; }
    public void setPolicySnapshot(String policySnapshot) { this.policySnapshot = policySnapshot; }
}
