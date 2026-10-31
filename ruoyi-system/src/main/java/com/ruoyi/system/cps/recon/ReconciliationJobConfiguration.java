package com.ruoyi.system.cps.recon;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** JDK task delegates keep ruoyi-quartz independent of the business module. */
@Configuration(proxyBeanMethods = false)
public class ReconciliationJobConfiguration {
    @Bean("cpsReconMonthJob")
    public Consumer<String> month(ReconciliationService service) {
        return period -> {
            for (String platform : List.of("tbk", "jd", "pdd", "PAYOUT")) service.reconcile(platform, period);
        };
    }

    @Bean("cpsReconRecentJob")
    public Runnable recent(@Qualifier("cpsReconMonthJob") Consumer<String> month) {
        return () -> {
            YearMonth current = YearMonth.now(ZoneOffset.UTC);
            month.accept(current.minusMonths(1).toString());
            month.accept(current.toString());
        };
    }

    @Bean("cpsExposureJob")
    public Runnable exposure(ExposureService service) { return service::captureIfDue; }
}
