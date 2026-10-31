package com.ruoyi.quartz.task;

import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Configure in Quartz: cpsReconTask.reconcileMonth('2026-01') or cpsReconTask.recent(). */
@Component("cpsReconTask")
public class CpsReconTask {
    private final Runnable recent;
    private final Consumer<String> month;

    public CpsReconTask(@Qualifier("cpsReconRecentJob") Runnable recent,
        @Qualifier("cpsReconMonthJob") Consumer<String> month) {
        this.recent = recent;
        this.month = month;
    }

    public void reconcileMonth(String period) {
        month.accept(period);
    }

    public void recent() {
        recent.run();
    }
}
