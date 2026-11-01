package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL 8 outbox storage. All methods run inside the caller's transaction. */
@Repository
public class SettlementNotificationRepository
{
    private final JdbcTemplate jdbc;

    public SettlementNotificationRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    public void insert(String keyHash, Long userId, Long orderLineId, String reason,
                       BigDecimal lossAmount, BigDecimal balanceAfter)
    {
        jdbc.update("""
                INSERT INTO cps_settlement_notification
                    (business_key_hash, delivery_key, user_id, order_line_id, reason, loss_amount, balance_after)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, keyHash, UUID.randomUUID().toString(), userId, orderLineId, reason, lossAmount, balanceAfter);
        // Locking read sees the committed winning insert even under REPEATABLE READ.
        Boolean matches = jdbc.queryForObject("""
                SELECT user_id, order_line_id, reason, loss_amount, balance_after
                FROM cps_settlement_notification WHERE business_key_hash = ? FOR UPDATE
                """, (rs, row) -> Objects.equals(userId, rs.getLong("user_id"))
                    && Objects.equals(orderLineId, rs.getObject("order_line_id", Long.class))
                    && reason.equals(rs.getString("reason"))
                    && lossAmount.compareTo(rs.getBigDecimal("loss_amount")) == 0
                    && balanceAfter.compareTo(rs.getBigDecimal("balance_after")) == 0, keyHash);
        if (!Boolean.TRUE.equals(matches))
        {
            throw new IllegalStateException("Notification business key payload conflict");
        }
    }

    public Claim claim()
    {
        List<Claim> rows = jdbc.query("""
                SELECT id, delivery_key, user_id, reason, loss_amount, balance_after
                FROM cps_settlement_notification
                WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6))
                   OR (status = 'CLAIMED' AND lease_until <= CURRENT_TIMESTAMP(6))
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Claim(rs.getLong("id"), UUID.randomUUID().toString(),
                    new SettlementNotificationChannel.Message(rs.getString("delivery_key"),
                        rs.getLong("user_id"), rs.getString("reason"),
                        rs.getBigDecimal("loss_amount"), rs.getBigDecimal("balance_after"))));
        if (rows.isEmpty())
        {
            return null;
        }
        Claim claim = rows.get(0);
        jdbc.update("""
                UPDATE cps_settlement_notification SET status = 'CLAIMED', claim_token = ?,
                    lease_until = TIMESTAMPADD(SECOND, 300, CURRENT_TIMESTAMP(6)), attempts = attempts + 1
                WHERE id = ?
                """, claim.token(), claim.id());
        return claim;
    }

    public boolean delivered(Claim claim)
    {
        return jdbc.update("""
                UPDATE cps_settlement_notification SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(6),
                    claim_token = NULL, lease_until = NULL, last_error_code = NULL
                WHERE id = ? AND status = 'CLAIMED' AND claim_token = ?
                """, claim.id(), claim.token()) == 1;
    }

    public void retry(Claim claim)
    {
        // Never persist exception messages: providers may include PII, secrets or raw orders.
        jdbc.update("""
                UPDATE cps_settlement_notification SET status = 'PENDING', claim_token = NULL,
                    lease_until = NULL, next_attempt_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(6)),
                    last_error_code = 'CHANNEL_FAILURE'
                WHERE id = ? AND status = 'CLAIMED' AND claim_token = ?
                """, claim.id(), claim.token());
    }

    public record Claim(long id, String token, SettlementNotificationChannel.Message message) { }
}
