package com.ruoyi.system.cps.risk;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Trusted server observations. No credentials, tokens or recipient identifiers belong here. */
public record HealthObservation(String eventKey, Kind kind, String subject, LocalDateTime observedAt,
                                Boolean authorized, BigDecimal balance,
                                Long currentOrders, Long currentRefunds,
                                Long baselineOrders, Long baselineRefunds) {
    public enum Kind { AUTHORIZATION, CHANNEL_BALANCE, REFUND_RATE }
}
