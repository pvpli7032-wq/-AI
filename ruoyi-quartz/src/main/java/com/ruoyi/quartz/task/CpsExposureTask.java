package com.ruoyi.quartz.task;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Minute tick; the business service reads the live interval and locks its durable checkpoint. */
@Component("cpsExposureTask")
public class CpsExposureTask {
    private final Runnable snapshot;

    public CpsExposureTask(@Qualifier("cpsExposureJob") Runnable snapshot) { this.snapshot = snapshot; }
    public void snapshot() { snapshot.run(); }
}
