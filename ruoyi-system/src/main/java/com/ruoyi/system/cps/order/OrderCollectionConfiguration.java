package com.ruoyi.system.cps.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.mapper.OrderEventMapper;
import com.ruoyi.system.cps.mapper.OrderIngestMapper;
import com.ruoyi.system.cps.mapper.OrderRawMapper;
import com.ruoyi.system.cps.settlement.SettlementService;

/** Wire actual persistence and downstream contracts; never provide fallback downstream implementations. */
@Configuration(proxyBeanMethods = false)
public class OrderCollectionConfiguration {
    @Bean
    public OrderIngestionService orderIngestionService(PlatformTransactionManager transactions, OrderRawMapper raw,
            OrderIngestMapper orders, OrderEventMapper events, OrderStatusMapper statuses,
            OrderTransitionValidator transitions) {
        return new OrderIngestionService(transactions, raw, orders, events, statuses, transitions);
    }

    @Bean
    @ConditionalOnBean({AttributionService.class, SettlementService.class})
    public OrderEventDispatcher orderEventDispatcher(OrderIngestionService ingestion,
            AttributionService attribution, SettlementService settlement) {
        return new OrderEventDispatcher(ingestion, attribution, settlement);
    }
}
