package com.ruoyi.web.controller.system;

import java.util.Collections;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.domain.SysCommissionFlow;
import com.ruoyi.system.service.ICommissionCalculateService;
import com.ruoyi.system.service.ISysCommissionFlowService;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class CommissionFlowReversalControllerTest
{
    private AnnotationConfigApplicationContext context;
    private ICommissionCalculateService calculateService;
    private SysCommissionFlowController controller;
    private MockMvc mvc;

    @Configuration
    @EnableMethodSecurity
    static class Config
    {
        @Bean(name = "ss") PermissionService permissionService() { return new PermissionService(); }
        @Bean ICommissionCalculateService calculateService() { return mock(ICommissionCalculateService.class); }
        @Bean ISysCommissionFlowService flowService() { return mock(ISysCommissionFlowService.class); }
        @Bean SysCommissionFlowController controller() { return new SysCommissionFlowController(); }
    }

    @Before
    public void setUp()
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        calculateService = context.getBean(ICommissionCalculateService.class);
        controller = context.getBean(SysCommissionFlowController.class);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @After
    public void tearDown()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    private void login(Set<String> permissions)
    {
        SysUser user = new SysUser();
        user.setUserName("test-operator");
        LoginUser principal = new LoginUser(42L, 1L, user, permissions);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }

    @Test
    public void missingReversalPermissionIsDeniedBeforeTheServiceRuns()
    {
        login(Set.of("system:commission:flow:add"));
        CommissionFlowReversalRequest request = new CommissionFlowReversalRequest();
        request.setOriginalFlowId(12L);
        request.setReason("test correction");
        request.setIdempotencyKey("test-key");
        assertThrows(AccessDeniedException.class, () -> controller.reverse(request));
        verifyNoInteractions(calculateService);
    }

    @Test
    public void authorizedRequestUsesLoggedInOperator() throws Exception
    {
        login(Set.of("system:commission:flow:reversal"));
        when(calculateService.reverseCommissionFlow(12L, "test correction", "test-key", 42L))
            .thenReturn(new SysCommissionFlow());
        mvc.perform(post("/system/commissionFlow/reversal").contentType(MediaType.APPLICATION_JSON)
            .content("{\"originalFlowId\":12,\"reason\":\"test correction\",\"idempotencyKey\":\"test-key\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(calculateService).reverseCommissionFlow(12L, "test correction", "test-key", 42L);
    }

    @Test
    public void invalidAndClientControlledFieldsAreRejected() throws Exception
    {
        login(Set.of("system:commission:flow:reversal"));
        String[] bodies = {
            "{\"reason\":\"test\",\"idempotencyKey\":\"key\"}",
            "{\"originalFlowId\":12,\"reason\":\" \",\"idempotencyKey\":\"key\"}",
            "{\"originalFlowId\":12,\"reason\":\"test\",\"idempotencyKey\":\" \"}",
            "{\"originalFlowId\":12,\"reason\":\"test\",\"idempotencyKey\":\"key\",\"amount\":1}",
            "{\"originalFlowId\":12,\"reason\":\"test\",\"idempotencyKey\":\"key\",\"operatorId\":999}"
        };
        for (String body : bodies)
        {
            mvc.perform(post("/system/commissionFlow/reversal").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(calculateService);
    }
}
