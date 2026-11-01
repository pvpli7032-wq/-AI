package com.ruoyi.system.cps.order;

import java.time.LocalDateTime;

/** Failure metadata only; cursor must contain pagination state, never credentials or URLs. */
public class OrderCollectionRetry {
    private Long id;
    private String dedupeKey, platform, taskType, pageCursor, status, errorCode, leaseToken;
    private LocalDateTime windowFrom, windowTo, nextAttemptAt, leaseUntil;
    private Integer attempts;
    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String v) { dedupeKey = v; }
    public String getPlatform() { return platform; }
    public void setPlatform(String v) { platform = v; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String v) { taskType = v; }
    public String getPageCursor() { return pageCursor; }
    public void setPageCursor(String v) { pageCursor = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { errorCode = v; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String v) { leaseToken = v; }
    public LocalDateTime getWindowFrom() { return windowFrom; }
    public void setWindowFrom(LocalDateTime v) { windowFrom = v; }
    public LocalDateTime getWindowTo() { return windowTo; }
    public void setWindowTo(LocalDateTime v) { windowTo = v; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime v) { nextAttemptAt = v; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime v) { leaseUntil = v; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer v) { attempts = v; }
}
