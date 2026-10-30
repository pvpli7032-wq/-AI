package com.ruoyi.web.controller.system;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.service.ISysConfigService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class SysConfigControllerTest
{
    @Test
    public void returnsRequestedConfigThroughExistingHttpRoute() throws Exception
    {
        ISysConfigService service = mock(ISysConfigService.class);
        SysConfig config = new SysConfig();
        config.setConfigId(42L);
        config.setConfigValue("test-value");
        when(service.selectConfigById(42L)).thenReturn(config);
        SysConfigController controller = new SysConfigController();
        ReflectionTestUtils.setField(controller, "configService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/system/config/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.configId").value(42))
                .andExpect(jsonPath("$.data.configValue").value("test-value"));
        verify(service).selectConfigById(42L);
    }
}
