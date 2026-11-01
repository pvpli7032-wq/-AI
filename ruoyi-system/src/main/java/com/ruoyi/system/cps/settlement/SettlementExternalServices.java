package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Typed integration with payment-time invitation evidence and the mandatory transactional outbox. */
@Component
public class SettlementExternalServices {
    private final PaymentInvitationSnapshotService invitations;
    private final SettlementNotificationService notifications;
    public SettlementExternalServices(PaymentInvitationSnapshotService invitations, SettlementNotificationService notifications) {
        this.invitations=invitations; this.notifications=notifications;
    }
    public record Invitation(Long inviterUserId, boolean manualReview, String evidenceId) {}
    public Invitation invitation(long id, Long buyer, UnionOrderLine line) {
        var result=invitations.resolve(id,buyer,line);
        return new Invitation(result.inviterUserId(),result.manualReview(),result.evidenceId());
    }
    public void pddNotice(com.ruoyi.system.cps.contract.OrderEvent event,Long user,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        if (user!=null) new SettlementPddNoticeService(jdbc,notifications).capture(event,user);
    }
    public void notify(String key, Long user, Long line, String reason, BigDecimal loss, BigDecimal balance) {
        // The outbox contract is loss notifications, not receipts for successful funding/release.
        String code=switch (reason) {
            case "RECOVER" -> SettlementNotificationService.REFUND;
            case "PENALTY_RECOVER" -> SettlementNotificationService.WHOLE_MONTH_PENALTY;
            case "REPRICE_DOWN" -> SettlementNotificationService.REBATE_INVALID;
            default -> null;
        };
        if (code!=null) notifications.enqueue(key,user,line,code,loss,balance);
    }
}
