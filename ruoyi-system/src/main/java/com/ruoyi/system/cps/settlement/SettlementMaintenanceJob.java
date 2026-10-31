package com.ruoyi.system.cps.settlement;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component("cpsSettlementMaintenanceJob")
public class SettlementMaintenanceJob implements Runnable {
    private final DefaultSettlementService settlements;
    private final SettlementNotificationService notifications;
    public SettlementMaintenanceJob(DefaultSettlementService settlements, SettlementNotificationService notifications) {
        this.settlements=settlements; this.notifications=notifications;
    }
    @Override public void run() {
        settlements.unfreezeDue(Instant.now(),1000);
        notifications.retryPending(100);
    }
}
