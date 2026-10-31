package com.ruoyi.system.cps.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.cps.mapper.OrderCollectionPlanMapper;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;

@Configuration(proxyBeanMethods = false)
public class OrderCollectorRuntimeConfiguration {
    @Bean
    public ConfiguredOrderCollectionSettings orderCollectionSettings(ISysConfigService configs, OrderCollectionPlanMapper plans) {
        return new ConfiguredOrderCollectionSettings(configs, plans, java.time.Clock.systemUTC());
    }
    @Bean
    @ConditionalOnProperty(name = "cps.order.dtk.enabled", havingValue = "true")
    public DtkOrderCollectionFeed dtkOrderCollectionFeed(Environment environment, OrderIngestionService ingestion) {
        DtkOrderEvidenceClient client = new DtkOrderEvidenceClient(environment.getRequiredProperty("CPS_ORDER_DTK_APP_KEY"),
            environment.getRequiredProperty("CPS_ORDER_DTK_APP_SECRET"), java.time.Duration.ofSeconds(
                Integer.parseInt(environment.getRequiredProperty("CPS_ORDER_DTK_TIMEOUT_SECONDS"))));
        return new DtkOrderCollectionFeed(client, ingestion, java.time.ZoneId.of(
            environment.getRequiredProperty("CPS_ORDER_DTK_ZONE")),
            Integer.parseInt(environment.getRequiredProperty("CPS_ORDER_TBK_QUERY_TYPE")));
    }
}
