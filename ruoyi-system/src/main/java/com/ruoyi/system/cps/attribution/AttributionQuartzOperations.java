package com.ruoyi.system.cps.attribution;

import java.util.function.IntUnaryOperator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes only the two fixed operations needed by the Quartz module. */
@Configuration(proxyBeanMethods = false)
public class AttributionQuartzOperations {
    @Bean("cpsExpireUnclaimedOperation")
    public IntUnaryOperator expireUnclaimedOperation(OrderAttributionService service) {
        return service::expireUnclaimed;
    }

    @Bean("cpsRetryAttributionSettlementOperation")
    public IntUnaryOperator retrySettlementOperation(OrderAttributionService service) {
        return service::retrySettlement;
    }
}
