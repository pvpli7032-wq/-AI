package com.ruoyi.union.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers DTK only. OFFICIAL remains unavailable until its real SDK is integrated. */
@Configuration
public class DtkCouponAdapterConfiguration {
    @Bean
    @ConditionalOnProperty(name = "cps.union.adapter.tbk", havingValue = "dtk")
    public UnionAdapter dtkCouponTbkAdapter(DtkCouponGateway gateway) {
        return new DtkCouponAdapter(Platform.TBK, gateway);
    }

    @Bean
    @ConditionalOnProperty(name = "cps.union.adapter.jd", havingValue = "dtk")
    public UnionAdapter dtkCouponJdAdapter(DtkCouponGateway gateway) {
        return new DtkCouponAdapter(Platform.JD, gateway);
    }

    @Bean
    @ConditionalOnProperty(name = "cps.union.adapter.pdd", havingValue = "dtk")
    public UnionAdapter dtkCouponPddAdapter(DtkCouponGateway gateway) {
        return new DtkCouponAdapter(Platform.PDD, gateway);
    }
}
