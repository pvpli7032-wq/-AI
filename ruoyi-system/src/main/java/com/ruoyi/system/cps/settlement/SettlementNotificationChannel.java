package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;

/**
 * Optional delivery adapter; no default/no-op implementation is registered.
 * Implementations MUST atomically deduplicate by idempotencyKey at the destination,
 * including concurrent calls after lease expiry and retries after ambiguous timeouts.
 * Return only on durable acceptance; throw on failure/unknown outcome. Use bounded
 * network timeouts (less than the five-minute claim lease). Never log routing IDs.
 * This port is at-least-once: without destination idempotency exactly-once delivery
 * cannot be guaranteed. userId is private routing metadata, NOT message content.
 */
public interface SettlementNotificationChannel
{
    void deliver(Message message) throws Exception;

    /** Deliberately excludes business keys, order identifiers and free-text/PII. */
    record Message(String idempotencyKey, Long userId, String reason,
                   BigDecimal lossAmount, BigDecimal balanceAfter)
    {
        @Override
        public String toString()
        {
            return "SettlementNotification.Message[redacted]";
        }
    }
}
