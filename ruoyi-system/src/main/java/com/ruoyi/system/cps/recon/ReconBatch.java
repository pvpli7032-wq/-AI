package com.ruoyi.system.cps.recon;

import java.time.LocalDateTime;

public record ReconBatch(Long id, String platform, String period, String status, String fingerprint,
    Integer diffCount, LocalDateTime createdAt, LocalDateTime completedAt) {}
