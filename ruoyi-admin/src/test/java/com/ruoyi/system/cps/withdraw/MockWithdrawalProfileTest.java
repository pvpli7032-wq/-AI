package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.ledger.LedgerService;
import com.ruoyi.system.cps.mapper.WithdrawalMapper;
import com.ruoyi.system.cps.recon.ExposureService;
import com.ruoyi.system.cps.risk.HealthMonitorService;
import com.ruoyi.system.cps.risk.RiskService;
import com.ruoyi.web.controller.cps.CpsWithdrawalController;

/** Real Spring registration and controller/service calls; no database or channel writes are allowed. */
public class MockWithdrawalProfileTest {
    private static final String WITHDRAW_NO = "WD00000000000000000000000000000001";
    private static final ChannelNotification SUCCESS_CALLBACK = new ChannelNotification("mock",
        "{\"withdrawNo\":\"" + WITHDRAW_NO + "\",\"channelNo\":\"mock-" + WITHDRAW_NO
            + "\",\"amount\":1.00,\"status\":\"SUCCESS\"}", "synthetic-test-token");

    @Test
    public void defaultAndDruidProfilesDisableMockAndBothEndpointsDespiteSuccessConfiguration() {
        assertDisabled(environment());
        assertDisabled(environment("druid"));
    }

    @Test
    public void productionProfilesDisableMockAndBothEndpoints() {
        assertDisabled(environment("prod"));
        assertDisabled(environment("production"));
        assertDisabled(environment("PROD"));
        assertDisabled(environment("Production"));
    }

    @Test
    public void productionOverridesExplicitDevAndTestProfiles() {
        for (String allowed : new String[] {"dev", "test", "DEV", "TeSt"}) {
            for (String denied : new String[] {"prod", "production", "PROD", "Production"}) {
                assertDisabled(environment("druid", allowed, denied));
            }
        }
    }

    @Test
    public void defaultDevAndTestProfilesAreNotExplicitOptIn() {
        for (String profile : new String[] {"dev", "test"}) {
            MockEnvironment environment = environment();
            environment.setDefaultProfiles(profile);
            assertDisabled(environment);
        }
    }

    @Test
    public void explicitlyActiveDevAndTestProfilesRegisterMock() {
        for (String profile : new String[] {"dev", "test", "DEV", "TeSt"}) {
            assertEnabled(environment(profile));
            assertEnabled(environment("druid", profile));
        }
    }

    private static void assertDisabled(MockEnvironment environment) {
        try (AnnotationConfigApplicationContext context = context(environment)) {
            assertTrue(context.getBeansOfType(MockWithdrawChannel.class).isEmpty());
            assertNull(context.getBeanProvider(WithdrawChannel.class).getIfAvailable());
            CpsWithdrawalController controller = context.getBean(CpsWithdrawalController.class);
            ServiceException resultError = assertThrows(ServiceException.class,
                () -> controller.mockResult(WITHDRAW_NO, new CpsWithdrawalController.MockResult(PaymentStatus.SUCCESS)));
            assertEquals("模拟通道未启用", resultError.getMessage());
            ServiceException callbackError = assertThrows(ServiceException.class,
                () -> controller.mockCallback(SUCCESS_CALLBACK));
            assertEquals("通道不可用", callbackError.getMessage());
            // A configured SUCCESS must never reach a mock payment, state transition or ledger posting.
            verifyNoInteractions(context.getBean(WithdrawalMapper.class), context.getBean(LedgerService.class),
                context.getBean(PlatformTransactionManager.class), context.getBean(RiskService.class),
                context.getBean(ExposureService.class), context.getBean(HealthMonitorService.class));
        }
    }

    private static void assertEnabled(MockEnvironment environment) {
        try (AnnotationConfigApplicationContext context = context(environment)) {
            assertEquals(1, context.getBeansOfType(MockWithdrawChannel.class).size());
            assertEquals(1, context.getBeansOfType(WithdrawChannel.class).size());
            assertSame(context.getBean(MockWithdrawChannel.class), context.getBeanProvider(WithdrawChannel.class).getIfAvailable());
        }
    }

    private static MockEnvironment environment(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment().withProperty("cps.withdraw.mock.status", "SUCCESS");
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }

    private static AnnotationConfigApplicationContext context(MockEnvironment environment) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        WithdrawalMapper mapper = mock(WithdrawalMapper.class);
        when(mapper.configuration("cps.withdraw.mock.status")).thenReturn("SUCCESS");
        context.registerBean(WithdrawalMapper.class, () -> mapper);
        context.registerBean(LedgerService.class, () -> mock(LedgerService.class));
        context.registerBean(RiskService.class, () -> mock(RiskService.class));
        context.registerBean(ExposureService.class, () -> mock(ExposureService.class));
        context.registerBean(HealthMonitorService.class, () -> mock(HealthMonitorService.class));
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.register(MockWithdrawChannel.class, WithdrawalService.class, CpsWithdrawalController.class);
        context.refresh();
        return context;
    }
}
