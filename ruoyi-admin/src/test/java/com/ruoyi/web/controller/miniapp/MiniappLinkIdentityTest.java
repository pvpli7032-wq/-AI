package com.ruoyi.web.controller.miniapp;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.service.IDtkApiService;
import com.ruoyi.system.service.IJdSdkService;
import com.ruoyi.system.service.IPddApiService;
import com.ruoyi.system.service.ISysTbkAgentApplyService;
import com.ruoyi.union.dtk.config.DtkConfig;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class MiniappLinkIdentityTest {
    @After
    public void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void dtkIgnoresForgedIdentityAndPidAndPreservesAnonymousBrowsing() throws Exception {
        MiniappDtkController controller = new MiniappDtkController();
        ISysTbkAgentApplyService applies = mock(ISysTbkAgentApplyService.class);
        IDtkApiService api = mock(IDtkApiService.class);
        DtkConfig config = new DtkConfig();
        config.setAdzoneId("server-pid");
        ReflectionTestUtils.setField(controller, "tbkAgentApplyService", applies);
        ReflectionTestUtils.setField(controller, "dtkApiService", api);
        ReflectionTestUtils.setField(controller, "dtkConfig", config);
        when(api.getPrivilegeLink(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new JSONObject());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(7L, 1L, null, null), null, java.util.Collections.emptyList()));
        mvc.perform(get("/miniapp/dtk/goods/tpwd").param("goodsId", "123")
                .param("userId", "99").param("invitationCode", "forged").param("pid", "attacker"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(applies).selectByUserId(7L);
        verify(applies, never()).selectByUserId(99L);
        verify(applies, never()).selectByInvitationCode(anyString());
        verify(api).getPrivilegeLink("123", null, "server-pid", null, null, null);
        SecurityContextHolder.clearContext();
        mvc.perform(get("/miniapp/dtk/goods/tpwd").param("goodsId", "456").param("userId", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(api).getPrivilegeLink("456", null, "server-pid", null, null, null);
    }

    @Test
    public void jdIgnoresClientPromotionConfiguration() throws Exception {
        MiniappJdController controller = new MiniappJdController();
        IJdSdkService api = mock(IJdSdkService.class);
        ReflectionTestUtils.setField(controller, "jdSdkService", api);
        when(api.getPromotionLink(anyMap())).thenReturn(new JSONObject());
        MockMvcBuilders.standaloneSetup(controller).build().perform(
                get("/miniapp/jd/getPromotionUrl").param("itemId", "123")
                        .param("positionId", "forged-position")
                        .param("siteId", "forged-site").param("sceneId", "forged-scene"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(api).getPromotionLink(java.util.Collections.singletonMap("materialId", "123"));
    }

    @Test
    public void pddPassesAuthenticatedIdentityToPromotionService() throws Exception {
        MiniappPddController controller = new MiniappPddController();
        ISysTbkAgentApplyService applies = mock(ISysTbkAgentApplyService.class);
        IPddApiService api = mock(IPddApiService.class);
        ReflectionTestUtils.setField(controller, "tbkAgentApplyService", applies);
        ReflectionTestUtils.setField(controller, "pddApiService", api);
        when(api.generatePromotionUrl(anyString(), anyLong(), isNull(), isNull())).thenReturn(new JSONObject());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(7L, 1L, null, null), null, java.util.Collections.emptyList()));
        MockMvcBuilders.standaloneSetup(controller).build().perform(
                get("/miniapp/pdd/promotion/url/generate").param("goodsSign", "goods")
                        .param("userId", "99").param("invitationCode", "forged"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(applies).selectByUserId(7L);
        verify(applies, never()).selectByInvitationCode(anyString());
        verify(api).generatePromotionUrl("goods", 7L, null, null);
    }
}
