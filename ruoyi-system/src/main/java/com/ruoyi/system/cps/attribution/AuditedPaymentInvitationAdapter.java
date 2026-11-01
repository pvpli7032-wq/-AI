package com.ruoyi.system.cps.attribution;

import com.ruoyi.system.cps.settlement.PaymentInvitationSnapshotPort;
import com.ruoyi.union.adapter.Platform;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connects payment-time settlement decisions to immutable invitation audit evidence.
 * Migration backfills describe an unknown historical binding time and are never proof.
 * No present-day inviter or legacy order referrer is used as a fallback.
 */
@Service
public class AuditedPaymentInvitationAdapter implements PaymentInvitationSnapshotPort {
    private final JdbcTemplate jdbc;
    public AuditedPaymentInvitationAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private record Audit(long id, Long inviter, String eventType) {}

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> findAtPayment(Platform platform, String orderNo, String subOrderNo,
            Long buyerUserId, Instant paidAt) {
        if (platform == null || orderNo == null || orderNo.isBlank() || buyerUserId == null
                || buyerUserId <= 0 || paidAt == null) return Optional.empty();
        String sub = subOrderNo == null ? "" : subOrderNo;
        // Only a persisted, matching order line can ask for financial invitation evidence.
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_order_commission
            WHERE LOWER(platform_type)=? AND order_id=? AND sub_order_no=? AND buyer_user_id=?
              AND ( (cps_paid_second IS NOT NULL AND cps_paid_second=? AND cps_paid_nano=?)
                OR (cps_paid_second IS NULL AND DATE_FORMAT(CONVERT_TZ(?, '+00:00', @@session.time_zone),'%Y-%m-%d %H:%i:%s.%f')=DATE_FORMAT(paid_time,'%Y-%m-%d %H:%i:%s.%f')) )
            """, Long.class, platform.name().toLowerCase(Locale.ROOT),orderNo,sub,buyerUserId,
            paidAt.getEpochSecond(),paidAt.getNano(),Timestamp.from(paidAt));
        if (count == null || count != 1) return Optional.empty();
        var history = jdbc.query("""
            SELECT id,next_inviter_user_id,event_type FROM cps_invite_binding_audit
            WHERE user_id=? AND created_at<=?
            ORDER BY created_at DESC,id DESC LIMIT 1
            """, (r,n)->new Audit(r.getLong("id"),r.getObject("next_inviter_user_id",Long.class),r.getString("event_type")),
            buyerUserId,Timestamp.from(paidAt));
        if (history.isEmpty()) return Optional.empty();
        Audit evidence = history.getFirst();
        if (!"FIRST_AUTH".equals(evidence.eventType()) && !"ADMIN_CHANGE".equals(evidence.eventType())) {
            // Legacy backfill describes an unknown binding time, not absence of an inviter.
            if ("LEGACY_BACKFILL".equals(evidence.eventType())) return Optional.empty();
            throw new IllegalArgumentException("Unsupported invitation evidence type: " + evidence.eventType());
        }
        if (evidence.inviter() != null && (evidence.inviter() <= 0 || evidence.inviter().equals(buyerUserId)))
            return Optional.empty();
        // A proven null is distinct from missing history. Existing settlement snapshots remain immutable.
        return Optional.of(new Snapshot(buyerUserId,evidence.inviter(),paidAt,"invite-audit:"+evidence.id()));
    }
}
