package com.ruoyi.web.controller.system;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 冲正只接受原流水、原因及幂等键；金额、账户和操作人由服务端确定。 */
public class CommissionFlowReversalRequest
{
    @NotNull
    @Positive
    private Long originalFlowId;

    @NotBlank
    @Size(max = 255)
    private String reason;

    @NotBlank
    @Size(max = 100)
    private String idempotencyKey;

    public Long getOriginalFlowId() { return originalFlowId; }
    public void setOriginalFlowId(Long originalFlowId) { this.originalFlowId = originalFlowId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value)
    {
        throw new IllegalArgumentException("不允许的冲正字段: " + name);
    }
}
