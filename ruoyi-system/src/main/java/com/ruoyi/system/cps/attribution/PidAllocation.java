package com.ruoyi.system.cps.attribution;
import java.time.Instant;
public record PidAllocation(String pid, boolean tracking, Instant expiresAt) {}
