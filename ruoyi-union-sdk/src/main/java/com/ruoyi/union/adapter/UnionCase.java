package com.ruoyi.union.adapter;

import java.time.Instant;
/** Raw platform payload is retained until each platform mapping is defined. */
public record UnionCase(Platform platform, String orderNo, String subOrderNo,
    String caseId, String rawStatus, String rawPayload, Instant occurredAt) {}
