package com.ruoyi.system.cps.attribution;
import com.ruoyi.union.adapter.Platform;
import java.time.Instant;
public record UnclaimedOrder(Long orderLineId, Platform platform, String maskedOrderNo,
    String maskedSubOrderNo, String status, String reason, Instant expiresAt) {}
