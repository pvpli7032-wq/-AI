package com.ruoyi.web.controller.system;

import com.ruoyi.system.domain.SysUnionPlatform;
import com.ruoyi.system.service.ISysUnionPlatformService;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class SysUnionPlatformSecretTest
{
    @RestController
    static class NestedResponseController
    {
        @GetMapping("/test-bound-platforms")
        public Map<String, Object> boundPlatforms()
        {
            SysUnionPlatform platform = new SysUnionPlatform();
            platform.setAppSecret("synthetic-secret");
            platform.setAccessToken("synthetic-token");
            return Collections.singletonMap("boundPlatforms", Collections.singletonList(platform));
        }
    }

    @Test
    public void credentialsAreExcludedFromNestedBoundPlatforms() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NestedResponseController()).build();
        mvc.perform(get("/test-bound-platforms"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.boundPlatforms[0].appSecret").doesNotExist())
            .andExpect(jsonPath("$.boundPlatforms[0].accessToken").doesNotExist());
    }

    @Test
    public void detailNeverReturnsCredentialsButIndicatesPresence() throws Exception
    {
        ISysUnionPlatformService service = mock(ISysUnionPlatformService.class);
        SysUnionPlatform platform = new SysUnionPlatform();
        platform.setId(42L);
        platform.setAppSecret("synthetic-secret");
        platform.setAccessToken("synthetic-token");
        when(service.selectSysUnionPlatformById(42L)).thenReturn(platform);
        SysUnionPlatformController controller = new SysUnionPlatformController();
        ReflectionTestUtils.setField(controller, "unionPlatformService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/system/unionPlatform/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.appSecret").doesNotExist())
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.hasAppSecret").value(true))
            .andExpect(jsonPath("$.data.hasAccessToken").value(true));
    }

    @Test
    public void credentialReplacementRemainsWriteable() throws Exception
    {
        ISysUnionPlatformService service = mock(ISysUnionPlatformService.class);
        SysUnionPlatformController controller = new SysUnionPlatformController();
        ReflectionTestUtils.setField(controller, "unionPlatformService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(put("/system/unionPlatform")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":42,\"appSecret\":\"new-secret\",\"accessToken\":\"new-token\"}"))
            .andExpect(status().isOk());
        verify(service).updateSysUnionPlatform(argThat(value ->
            "new-secret".equals(value.getAppSecret()) && "new-token".equals(value.getAccessToken())));
    }
}
