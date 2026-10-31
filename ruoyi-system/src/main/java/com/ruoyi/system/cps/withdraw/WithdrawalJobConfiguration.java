package com.ruoyi.system.cps.withdraw;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Quartz depends on common only. The named JDK task keeps that module boundary intact. */
@Configuration
public class WithdrawalJobConfiguration {
    @Bean("cpsWithdrawalQueryJob")
    public Runnable withdrawalQuery(WithdrawalService withdrawals) { return withdrawals::queryPending; }

    @Bean("cpsWithdrawalAutomaticJob")
    public Runnable withdrawalAutomatic(WithdrawalService withdrawals) { return withdrawals::processAutomatic; }
}
