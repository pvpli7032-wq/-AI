package com.ruoyi.system.cps.settlement;

import java.time.Instant;
import com.ruoyi.system.cps.ledger.LedgerAuditMonitor;
import org.springframework.stereotype.Component;

/** Each hourly stage is attempted even if an earlier stage fails. */
@Component("cpsSettlementMaintenanceJob")
public class SettlementMaintenanceJob implements Runnable {
    private final DefaultSettlementService settlements;
    private final SettlementNotificationService notifications;
    private final LedgerAuditMonitor audit;
    public SettlementMaintenanceJob(DefaultSettlementService settlements,
                                    SettlementNotificationService notifications,
                                    LedgerAuditMonitor audit) {
        this.settlements=settlements;
        this.notifications=notifications;
        this.audit=audit;
    }
    @Override public void run() {
        RuntimeException failure = null;
        try {
            settlements.unfreezeDue(Instant.now(),1000);
        } catch (RuntimeException ex) {
            failure = ex;
        }
        try {
            notifications.retryPending(100);
        } catch (RuntimeException ex) {
            if (failure == null) failure = ex; else failure.addSuppressed(ex);
        }
        try {
            audit.runHourly();
        } catch (RuntimeException ex) {
            if (failure == null) failure = ex; else failure.addSuppressed(ex);
        }
        // Quartz must record a failed job even when its other stages completed.
        if (failure != null) throw failure;
    }
}
