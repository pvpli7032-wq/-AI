package com.ruoyi.system.cps.settlement;

import java.time.Instant;
import java.util.Optional;
import com.ruoyi.union.adapter.Platform;

/** Line 9 integration boundary. Only payment-time historical evidence is authoritative.
 * No production adapter is supplied until line 9 provides that evidence.
 */
public interface PaymentInvitationSnapshotPort
{
    /** Empty means unknown history; a snapshot with null inviter means known no inviter. */
    Optional<Snapshot> findAtPayment(Platform platform, String orderNo, String subOrderNo,
        Long buyerUserId, Instant paidAt);

    record Snapshot(Long buyerUserId, Long inviterUserId, Instant paidAt, String evidenceId) {}
}
