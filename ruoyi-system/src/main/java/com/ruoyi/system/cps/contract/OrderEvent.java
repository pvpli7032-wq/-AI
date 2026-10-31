package com.ruoyi.system.cps.contract;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import com.ruoyi.union.adapter.Platform;
import java.time.Instant;
import com.ruoyi.union.adapter.UnionOrderLine;

/**
 * eventKey is stable across redelivery. recoveredAmount is null when no amount is
 * recovered; for a penalty it records the actual partial or full recovery.
 * When entering DISPUTED, previousStatus records the pre-dispute state. On
 * resolution back from DISPUTED it must match the persisted pre-dispute state
 * and toStatus must equal it; refund outcomes need not restore that state.
 */
public record OrderEvent(String eventKey, Long orderLineId, UnionOrderLine line,
    OrderStatus fromStatus, OrderStatus toStatus, OrderStatus previousStatus,
    BigDecimal recoveredAmount, String source, Long rawId, Instant occurredAt) {
    /** Stable event identity, using the alliance status timestamp (not fetch time).
     * A missing status timestamp must be resolved upstream; it must not be fabricated.
     */
    public static String generateEventKey(Platform platform, String orderNo, String subOrderNo,
            String rawStatus, Instant allianceStatusTime) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] { Objects.requireNonNull(platform).name(),
                    Objects.requireNonNull(orderNo), Objects.requireNonNull(subOrderNo),
                    Objects.requireNonNull(rawStatus), Objects.requireNonNull(allianceStatusTime).toString() }) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
