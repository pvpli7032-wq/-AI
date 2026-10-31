package com.ruoyi.system.cps;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.ledger.LedgerService;
import com.ruoyi.system.cps.order.OrderStatusMapper;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.contract.StatusTransitions;
import com.ruoyi.system.cps.contract.RebateStatus;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.system.cps.settlement.RebateStatusMapper;
import com.ruoyi.system.cps.withdraw.WithdrawChannel;
import com.ruoyi.system.cps.withdraw.PaymentRequest;
import com.ruoyi.system.cps.withdraw.PaymentResult;
import com.ruoyi.system.cps.withdraw.ChannelNotification;
import com.ruoyi.union.adapter.*;

public class ContractInjectionTest {
    @Test public void eachContractAcceptsATestImplementation() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(UnionAdapter.class, () -> new UnionAdapter() {
                public Platform platform() { return Platform.TBK; }
                public AdapterKind kind() { return AdapterKind.MOCK; }
                public ParsedItem parse(String input) { return null; }
                public CommissionQuote quote(String item) { return null; }
                public ConvertedLink convert(String item, String attribution) { return null; }
                public Page<UnionOrderLine> fetchOrders(TimeWindow window, String cursor) { return null; }
                public Page<UnionCase> fetchRefunds(TimeWindow window, String cursor) { return null; }
                public Page<UnionCase> fetchPenalties(TimeWindow window, String cursor) { return null; }
            });
            context.registerBean(OrderStatusMapper.class, () -> (platform, status) -> null);
            context.registerBean(AttributionService.class, () -> (orderLineId, line) -> null);
            context.registerBean(RebateStatusMapper.class, () -> (event, current) -> null);
            context.registerBean(SettlementService.class, () -> event -> {});
            context.registerBean(LedgerService.class, () -> command -> null);
            context.registerBean(WithdrawChannel.class, () -> new WithdrawChannel() {
                public PaymentResult submit(PaymentRequest request) { return null; }
                public PaymentResult query(String withdrawNo) { return null; }
                public PaymentResult handleCallback(ChannelNotification notification) { return null; }
            });
            context.refresh();
            assertNotNull(context.getBean(UnionAdapter.class));
            assertNotNull(context.getBean(OrderStatusMapper.class));
            assertNotNull(context.getBean(AttributionService.class));
            assertNotNull(context.getBean(SettlementService.class));
            assertNotNull(context.getBean(RebateStatusMapper.class));
            assertNotNull(context.getBean(LedgerService.class));
            assertNotNull(context.getBean(WithdrawChannel.class));
            MockEnvironment dev = new MockEnvironment().withProperty("cps.union.adapter.tbk", "mock");
            dev.setActiveProfiles("test");
            UnionAdapterConfiguration config = new UnionAdapterConfiguration(dev);
            assertSame(context.getBean(UnionAdapter.class), config.select(Platform.TBK, List.of(context.getBean(UnionAdapter.class))));
        }
    }

    @Test public void productionCannotEnableMockEvenWithTestProfile() {
        MockEnvironment prod = new MockEnvironment().withProperty("cps.union.adapter.tbk", "mock");
        prod.setActiveProfiles("prod", "test");
        assertThrows(IllegalStateException.class, () -> new UnionAdapterConfiguration(prod));
        MockEnvironment implicit = new MockEnvironment().withProperty("cps.union.adapter.jd", "mock");
        assertThrows(IllegalStateException.class, () -> new UnionAdapterConfiguration(implicit));
    }

    @Test public void productionStartupRejectsMockAndMissingOfficialAdapter() {
        MockEnvironment prodMock = new MockEnvironment().withProperty("cps.union.adapter.tbk", "mock");
        prodMock.setActiveProfiles("prod");
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(UnionAdapterConfiguration.class, () -> new UnionAdapterConfiguration(prodMock));
            assertThrows(Exception.class, context::refresh);
        }
        MockEnvironment prodDefault = new MockEnvironment();
        prodDefault.setActiveProfiles("prod");
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(UnionAdapterConfiguration.class, () -> new UnionAdapterConfiguration(prodDefault));
            assertThrows(IllegalStateException.class, context::refresh);
        }
    }

    @Test public void sharedTransitionsKeepTerminalStatesAndDisputesExplicit() {
        assertTrue(StatusTransitions.ORDER.get(OrderStatus.PAID).contains(OrderStatus.RECEIVED));
        assertTrue(StatusTransitions.ORDER.get(OrderStatus.PAID).contains(OrderStatus.SETTLED));
        assertTrue(StatusTransitions.ORDER.get(OrderStatus.DISPUTED).contains(OrderStatus.FULL_REFUND));
        assertFalse(StatusTransitions.ORDER.containsKey(OrderStatus.INVALID));
        assertTrue(StatusTransitions.REBATE.get(RebateStatus.FROZEN).contains(RebateStatus.PAUSED));
        assertTrue(StatusTransitions.REBATE.get(RebateStatus.PAUSED).contains(RebateStatus.FROZEN));
        assertFalse(StatusTransitions.REBATE.containsKey(RebateStatus.VOID));
        assertTrue(StatusTransitions.REBATE.get(RebateStatus.PARTIALLY_RECOVERED).contains(RebateStatus.FULLY_RECOVERED));
        assertTrue(StatusTransitions.REBATE.get(RebateStatus.PARTIALLY_RECOVERED).contains(RebateStatus.PARTIALLY_RECOVERED));
    }
    @Test public void scannedAdapterConfigurationFailsStartupWhenImplementationMissing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.scan("com.ruoyi.union.adapter");
            assertThrows(IllegalStateException.class, context::refresh);
            assertTrue(context.containsBeanDefinition("unionAdapterConfiguration"));
        }
    }
}
