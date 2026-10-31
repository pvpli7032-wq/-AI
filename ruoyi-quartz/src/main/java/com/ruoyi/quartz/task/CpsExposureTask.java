package com.ruoyi.quartz.task;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Daily exposure snapshot; current withdrawal checks always query fresh data. */
@Component("cpsExposureTask")
public class CpsExposureTask {
    private final Runnable snapshot;

    public CpsExposureTask(@Qualifier("cpsExposureJob") Runnable snapshot) { this.snapshot = snapshot; }
    public void snapshot() { snapshot.run(); }
}
