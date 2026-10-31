package com.ruoyi.system.cps.order;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.mapper.OrderEventMapper;
import com.ruoyi.system.cps.mapper.OrderIngestMapper;
import com.ruoyi.system.cps.mapper.OrderRawMapper;
import com.ruoyi.system.cps.settlement.SettlementService;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class OrderCollectionConfigurationTest {
    private AnnotationConfigApplicationContext context(boolean downstream) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.registerBean(OrderRawMapper.class, () -> mock(OrderRawMapper.class));
        context.registerBean(OrderIngestMapper.class, () -> mock(OrderIngestMapper.class));
        context.registerBean(OrderEventMapper.class, () -> mock(OrderEventMapper.class));
        context.registerBean(OrderStatusMapper.class, () -> mock(OrderStatusMapper.class));
        context.registerBean(OrderTransitionValidator.class, OrderTransitionValidator::new);
        if (downstream) {
            context.registerBean(AttributionService.class, () -> mock(AttributionService.class));
            context.registerBean(SettlementService.class, () -> mock(SettlementService.class));
        }
        context.register(OrderCollectionConfiguration.class);
        context.refresh();
        return context;
    }

    @Test public void missingDownstreamDoesNotPreventIngestionBeanOrCreateFakeDispatcher() {
        try (AnnotationConfigApplicationContext context = context(false)) {
            assertNotNull(context.getBean(OrderIngestionService.class));
            assertTrue(context.getBeansOfType(OrderEventDispatcher.class).isEmpty());
        }
    }

    @Test public void existingDownstreamServicesAreWiredIntoTheDispatcher() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            OrderEventDispatcher dispatcher = context.getBean(OrderEventDispatcher.class);
            assertSame(context.getBean(OrderIngestionService.class), ReflectionTestUtils.getField(dispatcher, "inbox"));
            assertSame(context.getBean(AttributionService.class), ReflectionTestUtils.getField(dispatcher, "attribution"));
            assertSame(context.getBean(SettlementService.class), ReflectionTestUtils.getField(dispatcher, "settlement"));
        }
    }
}
