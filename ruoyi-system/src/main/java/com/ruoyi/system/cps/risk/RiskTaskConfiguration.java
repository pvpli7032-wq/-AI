package com.ruoyi.system.cps.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps ruoyi-quartz independent of ruoyi-system without changing module dependencies. */
@Configuration
public class RiskTaskConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(RiskTaskConfiguration.class);

    @Bean("cpsHealthJob")
    public Runnable healthJob(HealthMonitorService health, RiskService risk) {
        return () -> {
            health.check();
            try {
                risk.scan();
            } catch (RuntimeException ex) {
                // The already committed authorization alerts survive an optional scan failure.
                LOG.error("CPS risk scan failed; authorization health check completed", ex);
            }
        };
    }
}
