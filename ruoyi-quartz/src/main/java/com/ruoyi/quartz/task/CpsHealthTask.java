package com.ruoyi.quartz.task;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Also callable from Quartz as cpsHealthTask.check(); mandatory auth checks cannot be disabled in sys_config. */
@Component("cpsHealthTask")
@EnableScheduling
public class CpsHealthTask {
    private final Runnable healthJob;

    public CpsHealthTask(@Qualifier("cpsHealthJob") Runnable healthJob) {
        this.healthJob = healthJob;
    }

    @Scheduled(fixedRate = 600000, initialDelay = 600000)
    public void check() {
        healthJob.run();
    }
}
