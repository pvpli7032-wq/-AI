package com.ruoyi.web.controller.system;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.system.service.ISysAgentCommissionAccountService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class SysAgentCommissionAccountStatusControllerTest
{
    private ISysAgentCommissionAccountService service;
    private MockMvc mvc;

    @Before
    public void setUp()
    {
        service = mock(ISysAgentCommissionAccountService.class);
        SysAgentCommissionAccountController controller = new SysAgentCommissionAccountController();
        ReflectionTestUtils.setField(controller, "sysAgentCommissionAccountService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void freezeRouteCallsStatusService() throws Exception
    {
        when(service.freezeAccount(42L)).thenReturn(1);

        mvc.perform(put("/system/commissionAccount/freeze/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(service).freezeAccount(42L);
    }

    @Test
    public void unfreezeRouteCallsStatusService() throws Exception
    {
        when(service.unfreezeAccount(42L)).thenReturn(1);

        mvc.perform(put("/system/commissionAccount/unfreeze/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(service).unfreezeAccount(42L);
    }

    @Test
    public void missingAccountIsReportedAsFailedOperation() throws Exception
    {
        mvc.perform(put("/system/commissionAccount/freeze/404"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(500));

        verify(service).freezeAccount(404L);
    }

    @Test
    public void stateChangesCannotBeTriggeredWithGet() throws Exception
    {
        mvc.perform(get("/system/commissionAccount/freeze/42"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/system/commissionAccount/unfreeze/42"))
            .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }

    @Test
    public void invalidPathIdIsRejectedBeforeServiceCall() throws Exception
    {
        mvc.perform(put("/system/commissionAccount/freeze/not-a-number"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
