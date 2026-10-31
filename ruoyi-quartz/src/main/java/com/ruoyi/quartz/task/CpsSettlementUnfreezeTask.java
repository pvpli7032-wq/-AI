package com.ruoyi.quartz.task;

import java.time.Instant;
import org.springframework.stereotype.Component;
import com.ruoyi.system.cps.settlement.DefaultSettlementService;
import com.ruoyi.system.cps.settlement.SettlementNotificationService;

/** Quartz forbids concurrent executions in the job seed; item/order locks also make replay safe. */
@Component("cpsSettlementUnfreezeTask")
public class CpsSettlementUnfreezeTask {
    private final DefaultSettlementService settlements;
    private final SettlementNotificationService notifications;
    public CpsSettlementUnfreezeTask(DefaultSettlementService settlements,SettlementNotificationService notifications) {
        this.settlements=settlements; this.notifications=notifications;
    }
    public int run() {
        int processed=settlements.unfreezeDue(Instant.now(),1000);
        notifications.retryPending(100); // Outside settlement transactions; absent channel stays PENDING.
        return processed;
    }
}
