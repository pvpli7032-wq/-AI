package com.ruoyi.web.controller.miniapp;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.domain.SysTbkAgentApply;
import com.ruoyi.system.service.IDtkApiService;
import com.ruoyi.system.service.ISysAgentUnionRelationService;
import com.ruoyi.system.service.ISysTbkAgentApplyService;
import com.ruoyi.union.dtk.config.DtkConfig;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

public class MiniappLegacyCouponBrowseTest {
    @After public void logout() { SecurityContextHolder.clearContext(); }

    @Test public void loggedInAgentWithoutAssignedCredentialsCanBrowseAndConvert() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new LoginUser(7L, 1L, null, null), null, List.of()));
        ISysTbkAgentApplyService applies = mock(ISysTbkAgentApplyService.class);
        ISysAgentUnionRelationService relations = mock(ISysAgentUnionRelationService.class);
        IDtkApiService api = mock(IDtkApiService.class);
        SysTbkAgentApply apply = new SysTbkAgentApply(); apply.setId(70L); apply.setUserId(7L); apply.setStatus("1");
        when(applies.selectByUserId(7L)).thenReturn(apply);
        when(relations.selectByApplyId(70L)).thenReturn(List.of());
        when(api.getGoodsDetail("123", null, null)).thenReturn(JSONObject.of("goodsId", "123"));
        when(api.getPrivilegeLink("123", null, "server-pid", null, null, null)).thenReturn(new JSONObject());
        DtkConfig config = new DtkConfig(); config.setAdzoneId("server-pid");
        MiniappDtkController controller = new MiniappDtkController();
        ReflectionTestUtils.setField(controller, "tbkAgentApplyService", applies);
        ReflectionTestUtils.setField(controller, "agentUnionRelationService", relations);
        ReflectionTestUtils.setField(controller, "dtkApiService", api);
        ReflectionTestUtils.setField(controller, "dtkConfig", config);
        assertEquals(200, controller.getGoodsDetail("123", "forged", 99L).get("code"));
        assertEquals(200, controller.getPrivilegeLink("123", null, "forged-pid", null, "forged", 99L).get("code"));
        verify(api).getGoodsDetail("123", null, null);
        verify(api).getPrivilegeLink("123", null, "server-pid", null, null, null);
        verify(applies, never()).selectByUserId(99L);
        verify(applies, never()).selectByInvitationCode("forged");
    }
}
