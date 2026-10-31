package com.ruoyi.system.cps.settlement;

import java.util.Objects;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.union.adapter.UnionOrderLine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable first decision per persisted order-line ID, including unknown/quarantined history.
 * Never reads a user's present invitation relationship. Manual review is not auto-retry permission.
 */
@Service
public class PaymentInvitationSnapshotService
{
    private final JdbcTemplate jdbc;
    private final ObjectProvider<PaymentInvitationSnapshotPort> ports;

    public PaymentInvitationSnapshotService(JdbcTemplate jdbc, ObjectProvider<PaymentInvitationSnapshotPort> ports)
    {
        this.jdbc = jdbc;
        this.ports = ports;
    }

    public record Decision(Long inviterUserId, boolean manualReview, String evidenceId) {}

    @Transactional
    public Decision resolve(long orderLineId, Long buyerUserId, UnionOrderLine line)
    {
        if (orderLineId <= 0 || buyerUserId == null || buyerUserId <= 0 || line == null
            || line.platform() == null || line.orderNo() == null || line.orderNo().isBlank())
            throw new ServiceException("Invalid payment invitation order identity");
        var existing = read(orderLineId, buyerUserId, line, false);
        if (existing != null) return existing;

        Decision decision = new Decision(null, true, null);
        // Absence of trusted paidAt is unknown even when the legacy order creation time exists.
        PaymentInvitationSnapshotPort port = ports == null ? null : ports.getIfAvailable();
        if (line.paidAt() != null && port != null)
        {
            var evidence = port.findAtPayment(line.platform(), line.orderNo(), line.subOrderNo(), buyerUserId, line.paidAt());
            if (evidence.isPresent())
            {
                var snapshot = evidence.get();
                String id = snapshot.evidenceId();
                boolean identifiable = id != null && !id.isBlank() && id.length() <= 255;
                boolean valid = identifiable && buyerUserId.equals(snapshot.buyerUserId())
                    && line.paidAt().equals(snapshot.paidAt())
                    && (snapshot.inviterUserId() == null || snapshot.inviterUserId() > 0
                        && !buyerUserId.equals(snapshot.inviterUserId()));
                decision = new Decision(valid ? snapshot.inviterUserId() : null, !valid, identifiable ? id : null);
            }
        }
        // Unique key serializes concurrent first decisions. The losing writer does not overwrite
        // any evidence, review status or identity (and reads the winner using a current read).
        jdbc.update("""
            INSERT INTO cps_payment_invitation_snapshot
                (order_line_id, buyer_user_id, platform, order_no, sub_order_no, paid_at,
                 inviter_user_id, evidence_id, review_status)
            VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE order_line_id=order_line_id
            """, orderLineId, buyerUserId, line.platform().name(), line.orderNo(), line.subOrderNo(),
            paidAt(line), decision.inviterUserId(), decision.evidenceId(), decision.manualReview() ? "MANUAL_REVIEW" : "VERIFIED");
        return read(orderLineId, buyerUserId, line, true);
    }

    private Decision read(long id, Long buyer, UnionOrderLine line, boolean lock)
    {
        var rows = jdbc.query("SELECT * FROM cps_payment_invitation_snapshot WHERE order_line_id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> {
                if (!buyer.equals(rs.getObject("buyer_user_id", Long.class))
                    || !line.platform().name().equals(rs.getString("platform"))
                    || !line.orderNo().equals(rs.getString("order_no"))
                    || !Objects.equals(line.subOrderNo(), rs.getString("sub_order_no"))
                    || !Objects.equals(paidAt(line), rs.getString("paid_at")))
                    throw new ServiceException("Payment invitation snapshot identity mismatch");
                return new Decision(rs.getObject("inviter_user_id", Long.class),
                    "MANUAL_REVIEW".equals(rs.getString("review_status")), rs.getString("evidence_id"));
            }, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String paidAt(UnionOrderLine line)
    {
        return line.paidAt() == null ? null : line.paidAt().toString();
    }
}
