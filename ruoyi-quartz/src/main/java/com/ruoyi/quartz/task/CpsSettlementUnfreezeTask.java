package com.ruoyi.quartz.task;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Keep Quartz independent of the system module, as with the existing withdrawal task. */
@Component("cpsSettlementUnfreezeTask")
public class CpsSettlementUnfreezeTask {
    private final Runnable maintenance;
    public CpsSettlementUnfreezeTask(@Qualifier("cpsSettlementMaintenanceJob") Runnable maintenance) {
        this.maintenance = maintenance;
    }
    public void run() { maintenance.run(); }
}
