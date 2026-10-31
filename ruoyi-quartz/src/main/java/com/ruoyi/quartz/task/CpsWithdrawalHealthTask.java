package com.ruoyi.quartz.task;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

/** Register cpsWithdrawalHealthTask.queryPending() in the existing Quartz job manager. Never submits. */
@Component("cpsWithdrawalHealthTask")
public class CpsWithdrawalHealthTask {
    private final Runnable query;
    public CpsWithdrawalHealthTask(@Qualifier("cpsWithdrawalQueryJob") Runnable query) { this.query = query; }
    public void queryPending() { query.run(); }
}
