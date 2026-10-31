package com.ruoyi.system.cps.order;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.ruoyi.system.cps.mapper.OrderCollectionRetryMapper;
import com.ruoyi.union.adapter.Platform;

/** Durable, at-least-once failure queue. The caller must make downstream processing idempotent. */
@Service
public class OrderCollectionRetryQueue {
    public enum TaskType { INCREMENTAL, BACKFILL, REFUND, PENALTY }
    private final OrderCollectionRetryMapper mapper;

    public OrderCollectionRetryQueue(OrderCollectionRetryMapper mapper) { this.mapper = mapper; }

    /**
     * runId identifies one logical collection run, reused for retries of the same write.
     * Cursor must be the upstream pagination cursor only, never a URL, token, payload or credential.
     * Only a non-sensitive fixed error code may be persisted, never exception messages.
     */
    public OrderCollectionRetry record(UUID runId, Platform platform, TaskType type,
        LocalDateTime from, LocalDateTime to, String cursor, String errorCode) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        from = from.truncatedTo(ChronoUnit.MICROS);
        to = to.truncatedTo(ChronoUnit.MICROS);
        if (!from.isBefore(to)) throw new IllegalArgumentException("Invalid collection window");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 512
            || cursor.codePoints().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Only an opaque pagination cursor is allowed");
        validateCode(errorCode);
        OrderCollectionRetry item = new OrderCollectionRetry();
        item.setDedupeKey(key(runId, platform, type, from, to, cursor));
        item.setPlatform(platform.name());
        item.setTaskType(type.name());
        item.setWindowFrom(from);
        item.setWindowTo(to);
        item.setPageCursor(cursor);
        item.setErrorCode(errorCode);
        mapper.record(item);
        OrderCollectionRetry result = mapper.findByKey(item.getDedupeKey());
        if (result == null) throw new IllegalStateException("Failed to persist retry window");
        return result;
    }

    /** Lease duration and contention attempts are configured by the caller, never fixed by the queue. */
    public OrderCollectionRetry claimNext(int leaseSeconds, int contentionAttempts) {
        if (leaseSeconds <= 0 || contentionAttempts <= 0) throw new IllegalArgumentException("Invalid lease settings");
        for (int i = 0; i < contentionAttempts; i++) {
            Long id = mapper.findReadyId();
            if (id == null) return null;
            String token = UUID.randomUUID().toString();
            if (mapper.claim(id, token, leaseSeconds) == 1) {
                OrderCollectionRetry item = mapper.findByLease(id, token);
                if (item == null) throw new IllegalStateException("Claimed window is unavailable");
                return item;
            }
        }
        return null;
    }

    public void complete(long id, String token) {
        validateLease(id, token);
        if (mapper.complete(id, token) != 1) throw new IllegalStateException("Lease expired or already completed");
    }

    /** nextAttemptAt uses the MySQL session time zone. */
    public void fail(long id, String token, LocalDateTime nextAttemptAt, String errorCode) {
        validateLease(id, token);
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        validateCode(errorCode);
        if (mapper.fail(id, token, nextAttemptAt.truncatedTo(ChronoUnit.MICROS), errorCode) != 1)
            throw new IllegalStateException("Lease expired or already completed");
    }

    private static void validateLease(long id, String token) {
        if (id <= 0) throw new IllegalArgumentException("Invalid retry id");
        UUID.fromString(Objects.requireNonNull(token, "token"));
    }
    private static void validateCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Only a non-sensitive error code is allowed");
    }
    private static String key(UUID runId, Platform platform, TaskType type, LocalDateTime from,
                              LocalDateTime to, String cursor) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(runId.toString()); out.writeUTF(platform.name()); out.writeUTF(type.name());
                out.writeUTF(from.toString()); out.writeUTF(to.toString());
                out.writeBoolean(cursor != null); if (cursor != null) out.writeUTF(cursor);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot generate retry identity", e);
        }
    }
}
