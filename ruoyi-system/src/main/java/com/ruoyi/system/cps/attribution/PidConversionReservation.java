package com.ruoyi.system.cps.attribution;

import com.ruoyi.union.adapter.Platform;

/** An in-flight conversion, not an attribution identifier exposed to clients. */
public record PidConversionReservation(PidAllocation allocation, Platform platform, String itemId,
        Long leaseId, String attemptId) {}
