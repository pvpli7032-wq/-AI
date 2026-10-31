package com.ruoyi.quartz.task;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Recovery after an application committed but its automatic dispatch did not complete. */
@Component("cpsAutomaticWithdrawalTask")
public class CpsAutomaticWithdrawalTask {
    private final Runnable automatic;

    public CpsAutomaticWithdrawalTask(@Qualifier("cpsWithdrawalAutomaticJob") Runnable automatic) {
        this.automatic = automatic;
    }

    public void process() { automatic.run(); }
}
