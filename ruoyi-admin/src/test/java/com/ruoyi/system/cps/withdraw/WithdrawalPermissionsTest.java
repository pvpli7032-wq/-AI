package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.security.context.PermissionContextHolder;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.cps.recon.ExposureLimitAction;
import com.ruoyi.system.cps.recon.ExposurePolicy;
import com.ruoyi.system.cps.recon.ExposureService;
import com.ruoyi.system.cps.recon.ReconCoverageRequest;
import com.ruoyi.system.cps.recon.ReconImportRequest;
import com.ruoyi.system.cps.recon.ReconciliationService;
import com.ruoyi.system.cps.risk.HealthMonitorService;
import com.ruoyi.system.cps.risk.HealthObservation;
import com.ruoyi.system.cps.risk.RiskService;
import com.ruoyi.web.controller.cps.CpsExposureController;
import com.ruoyi.web.controller.cps.CpsReconciliationController;
import com.ruoyi.web.controller.cps.CpsRiskController;
import com.ruoyi.web.controller.cps.CpsWithdrawalController;

/** Exercises the real RuoYi permission service through Spring's method-security proxies. */
public class WithdrawalPermissionsTest {
    private static final long OPERATOR = 42L;
    private static final String WITHDRAW_NO = "WITHDRAW-PERMISSION-TEST";
    private static final List<Class<?>> CONTROLLERS = List.of(CpsWithdrawalController.class,
        CpsReconciliationController.class, CpsExposureController.class, CpsRiskController.class);
    private AnnotationConfigApplicationContext context;
    private WithdrawalService withdrawals;
    private Object[] services;
    private List<Endpoint> endpoints;

    @Configuration
    @EnableMethodSecurity
    static class SecurityConfiguration {
        @Bean(name = "ss") PermissionService permissions() { return new PermissionService(); }
        @Bean WithdrawalService withdrawals() { return mock(WithdrawalService.class); }
        @Bean MockWithdrawChannel mockChannel() { return mock(MockWithdrawChannel.class); }
        @Bean ReconciliationService reconciliation() { return mock(ReconciliationService.class); }
        @Bean ExposureService exposure() { return mock(ExposureService.class); }
        @Bean RiskService risk() { return mock(RiskService.class); }
        @Bean HealthMonitorService health() { return mock(HealthMonitorService.class); }
        @Bean CpsWithdrawalController withdrawalController(WithdrawalService service,
                ObjectProvider<MockWithdrawChannel> mock) {
            return new CpsWithdrawalController(service, mock);
        }
        @Bean CpsReconciliationController reconciliationController(ReconciliationService service) {
            return new CpsReconciliationController(service);
        }
        @Bean CpsExposureController exposureController(ExposureService service) {
            return new CpsExposureController(service);
        }
        @Bean CpsRiskController riskController(RiskService risk, HealthMonitorService health) {
            return new CpsRiskController(risk, health);
        }
    }

    @Before
    public void setUp() {
        context = new AnnotationConfigApplicationContext(SecurityConfiguration.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        withdrawals = context.getBean(WithdrawalService.class);
        services = new Object[] {withdrawals, context.getBean(MockWithdrawChannel.class),
            context.getBean(ReconciliationService.class), context.getBean(ExposureService.class),
            context.getBean(RiskService.class), context.getBean(HealthMonitorService.class)};
        when(withdrawals.listAdmin(null, 1, 10)).thenReturn(Map.of("rows", List.of(), "total", 0));
        when(withdrawals.statusOptions()).thenReturn(WithdrawalStatus.options());
        when(withdrawals.configuration()).thenReturn("{\"enabled\":false}");
        clearInvocations(services);
        endpoints = permissionsMatrix();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (context != null) context.close();
    }

    @Test
    public void matrixCoversEveryEndpointAndAllControllersHaveMethodSecurity() {
        Set<Method> mapped = CONTROLLERS.stream().flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
            .collect(Collectors.toSet());
        assertEquals(mapped, endpoints.stream().map(Endpoint::method).collect(Collectors.toSet()));
        assertEquals("Each route must appear exactly once in the authorization matrix", mapped.size(), endpoints.size());
        for (Class<?> controller : CONTROLLERS) assertTrue(AopUtils.isAopProxy(context.getBean(controller)));
    }

    @Test
    public void eachNewPermissionAllowsItsEndpointAndReachesTheService() throws Exception {
        for (Endpoint endpoint : endpoints) {
            clearInvocations(services);
            login(Set.of(endpoint.permission()));
            assertEquals(endpoint.label(), Integer.valueOf(200), endpoint.invoke(context).get(AjaxResult.CODE_TAG));
            assertEquals(endpoint.label(), endpoint.permission(), PermissionContextHolder.getContext());
            assertTrue("Authorized endpoint must execute its service: " + endpoint.label(),
                Arrays.stream(services).anyMatch(service -> !mockingDetails(service).getInvocations().isEmpty()));
        }
    }

    @Test
    public void legacyCpsPermissionsCannotReachAnyEndpoint() {
        login(endpoints.stream().map(Endpoint::legacyPermission).collect(Collectors.toSet()));
        for (Endpoint endpoint : endpoints) assertDenied(endpoint);
        verifyNoInteractions(services);
    }

    @Test
    public void readOnlyPermissionsCannotMutateFundsPolicyEvidenceOrRisk() {
        login(Set.of("system:commission:withdrawal:list", "system:commission:withdrawal:query",
            "system:commission:recon:list", "system:commission:recon:query",
            "system:commission:exposure:list", "system:commission:exposure:query",
            "system:commission:risk:list", "system:commission:risk:query",
            "system:commission:health:list", "system:commission:health:query"));
        for (Endpoint endpoint : endpoints) if (endpoint.writes()) assertDenied(endpoint);
        verifyNoInteractions(services);
    }

    @Test
    public void missingOrUnrelatedPermissionsCannotReachAnyEndpoint() {
        for (Set<String> permissions : List.of(Set.<String>of(), Set.of("system:commission:order:edit"))) {
            login(permissions);
            for (Endpoint endpoint : endpoints) assertDenied(endpoint);
        }
        verifyNoInteractions(services);
    }

    @Test
    public void withdrawalWritesUseAuthenticatedOperatorAfterAuthorization() {
        login(Set.of("system:commission:withdrawal:edit"));
        CpsWithdrawalController controller = context.getBean(CpsWithdrawalController.class);
        controller.audit(WITHDRAW_NO, new CpsWithdrawalController.Review(true, "synthetic review"));
        controller.pay(WITHDRAW_NO);
        controller.policy(Map.of("enabled", false));
        verify(withdrawals).audit(WITHDRAW_NO, true, OPERATOR, "synthetic review");
        verify(withdrawals).pay(WITHDRAW_NO, OPERATOR);
        verify(withdrawals).configure("{\"enabled\":false}", OPERATOR);
    }

    @Test
    public void exposurePolicyUsesAuthenticatedOperatorAndReturnsItsConfiguration() {
        login(Set.of("system:commission:exposure:edit"));
        ExposurePolicy policy = new ExposurePolicy(new BigDecimal("0.00"), ExposureLimitAction.MANUAL_REVIEW, 60);
        ExposureService exposure = context.getBean(ExposureService.class);
        when(exposure.configure(policy, OPERATOR)).thenReturn(policy);
        CpsExposureController controller = context.getBean(CpsExposureController.class);
        assertEquals(policy, controller.policy(policy).get(AjaxResult.DATA_TAG));
        verify(exposure).configure(policy, OPERATOR);
        when(exposure.configuration()).thenReturn(policy);
        login(Set.of("system:commission:exposure:query"));
        assertEquals(policy, controller.policy().get(AjaxResult.DATA_TAG));
        verify(exposure).configuration();
    }

    private void login(Set<String> permissions) {
        SysUser user = new SysUser();
        user.setUserId(OPERATOR);
        user.setUserName("line12-permission-test");
        LoginUser principal = new LoginUser(OPERATOR, 1L, user, permissions);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private void assertDenied(Endpoint endpoint) {
        assertThrows(endpoint.label(), AccessDeniedException.class, () -> endpoint.invoke(context));
    }

    // This explicit policy matrix follows design/admin.md. It does not derive expected keys from annotations.
    private static List<Endpoint> permissionsMatrix() {
        return List.of(
            endpoint(CpsWithdrawalController.class, "list", "withdrawal:list", "withdrawal:list", false, null, 1, 10),
            endpoint(CpsWithdrawalController.class, "statusOptions", "withdrawal:list", "withdrawal:list", false),
            endpoint(CpsWithdrawalController.class, "audit", "withdrawal:edit", "withdrawal:audit", true,
                WITHDRAW_NO, new CpsWithdrawalController.Review(true, "synthetic review")),
            endpoint(CpsWithdrawalController.class, "pay", "withdrawal:edit", "withdrawal:pay", true, WITHDRAW_NO),
            endpoint(CpsWithdrawalController.class, "query", "withdrawal:edit", "withdrawal:query", true, WITHDRAW_NO),
            endpoint(CpsWithdrawalController.class, "policy", "withdrawal:query", "withdrawal:config", false),
            endpoint(CpsWithdrawalController.class, "policy", "withdrawal:edit", "withdrawal:config", true,
                Map.of("enabled", false)),
            endpoint(CpsWithdrawalController.class, "payee", "withdrawal:edit", "withdrawal:recipient", true,
                12001L, new CpsWithdrawalController.Payee("mock", "Synthetic payee", "test-account",
                    true, true, "synthetic-evidence", "test binding")),
            endpoint(CpsWithdrawalController.class, "group", "withdrawal:edit", "withdrawal:config", true,
                12001L, new CpsWithdrawalController.Group("synthetic-group", "test assignment")),
            endpoint(CpsWithdrawalController.class, "mockResult", "withdrawal:edit", "withdrawal:mock", true,
                WITHDRAW_NO, new CpsWithdrawalController.MockResult(PaymentStatus.PROCESSING)),
            endpoint(CpsWithdrawalController.class, "mockCallback", "withdrawal:edit", "withdrawal:mock", true,
                new ChannelNotification("mock", "synthetic-payload", "synthetic-signature")),
            endpoint(CpsReconciliationController.class, "batches", "recon:list", "recon:list", false, "JD", "2026-01"),
            endpoint(CpsReconciliationController.class, "importSource", "recon:add", "recon:import", true,
                new ReconImportRequest("JD", "PAYOUT", "synthetic-source", null, null, WITHDRAW_NO,
                    new BigDecimal("1.00"), null, LocalDate.of(2026, 1, 1))),
            endpoint(CpsReconciliationController.class, "sources", "recon:list", "recon:list", false, "JD", "2026-01"),
            endpoint(CpsReconciliationController.class, "allocate", "recon:add", "recon:allocate", true,
                new ReconCoverageRequest("synthetic-allocation", 1L, new BigDecimal("1.00"), "test coverage")),
            endpoint(CpsReconciliationController.class, "reconcile", "recon:add", "recon:run", true,
                new CpsReconciliationController.RunRequest("JD", "2026-01")),
            endpoint(CpsReconciliationController.class, "differences", "recon:list", "recon:list", false, 1L),
            endpoint(CpsReconciliationController.class, "resolve", "recon:edit", "recon:resolve", true,
                1L, new CpsReconciliationController.ResolveRequest("synthetic resolution")),
            endpoint(CpsExposureController.class, "current", "exposure:list", "exposure:list", false),
            endpoint(CpsExposureController.class, "positions", "exposure:list", "exposure:list", false),
            endpoint(CpsExposureController.class, "snapshot", "exposure:add", "exposure:snapshot", true),
            endpoint(CpsExposureController.class, "policy", "exposure:query", "exposure:config", false),
            endpoint(CpsExposureController.class, "policy", "exposure:edit", "exposure:config", true,
                new ExposurePolicy(new BigDecimal("0.00"), ExposureLimitAction.MANUAL_REVIEW, 60)),
            endpoint(CpsRiskController.class, "user", "risk:query", "risk:query", false, 12001L),
            endpoint(CpsRiskController.class, "signals", "risk:list", "risk:list", false, 12001L),
            endpoint(CpsRiskController.class, "review", "risk:edit", "risk:review", true,
                12001L, new CpsRiskController.ReviewRequest(false, false, true, "synthetic review")),
            endpoint(CpsRiskController.class, "device", "risk:add", "risk:import", true,
                new CpsRiskController.DeviceEvidence(12001L, "synthetic-device", "synthetic-event",
                    LocalDateTime.of(2026, 1, 1, 0, 0))),
            endpoint(CpsRiskController.class, "scan", "risk:edit", "risk:review", true),
            endpoint(CpsRiskController.class, "alerts", "health:list", "health:list", false),
            endpoint(CpsRiskController.class, "observe", "health:add", "health:import", true,
                new HealthObservation("synthetic-health", HealthObservation.Kind.CHANNEL_BALANCE, "mock",
                    LocalDateTime.of(2026, 1, 1, 0, 0), null, new BigDecimal("1.00"), null, null, null, null))
        );
    }

    private static Endpoint endpoint(Class<?> type, String method, String permission, String legacyPermission,
            boolean writes, Object... args) {
        Method target = Arrays.stream(type.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(method) && candidate.getParameterCount() == args.length)
            .findFirst().orElseThrow();
        return new Endpoint(type, target, "system:commission:" + permission, "cps:" + legacyPermission, writes, args);
    }

    private record Endpoint(Class<?> controller, Method method, String permission, String legacyPermission,
                            boolean writes, Object[] arguments) {
        String label() { return controller.getSimpleName() + "." + method.getName() + " requires " + permission; }
        AjaxResult invoke(AnnotationConfigApplicationContext context) throws Exception {
            try {
                return (AjaxResult) method.invoke(context.getBean(controller), arguments);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
    }
}
