package com.ruoyi.web.controller.miniapp;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.service.ISysTbkAgentApplyService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.ISysWechatUserService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Invitation input is consumed once, independently of the user's own invitation code or agent status. */
public class MiniappFirstInvitationTest {
    private MockMvc mvc;
    private ISysWechatUserService wechat;
    private ISysUserService users;

    @Before public void setup() throws Exception {
        MiniappAuthController controller = new MiniappAuthController();
        WxMaService wx = mock(WxMaService.class, RETURNS_DEEP_STUBS);
        WxMaJscode2SessionResult session = new WxMaJscode2SessionResult();
        session.setOpenid("local-invitation-openid");
        when(wx.getUserService().getSessionInfo("local-code")).thenReturn(session);
        wechat = mock(ISysWechatUserService.class);
        users = mock(ISysUserService.class);
        doAnswer(call -> { ((SysUser) call.getArgument(0)).setUserId(901L); return 1; }).when(users).insertUser(any(SysUser.class));
        when(wechat.ensureInvitationCode(901L)).thenReturn("own-new-user-code");
        TokenService tokens = mock(TokenService.class);
        when(tokens.createToken(any(LoginUser.class))).thenReturn("local-test-token");
        ReflectionTestUtils.setField(controller, "wxMaService", wx);
        ReflectionTestUtils.setField(controller, "wechatUserService", wechat);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        ReflectionTestUtils.setField(controller, "tbkAgentApplyService", mock(ISysTbkAgentApplyService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test public void newUserSendsInviterCodeOnlyToFirstAuthorization() throws Exception {
        mvc.perform(post("/miniapp/auth/login").param("code", "local-code").param("invitationCode", "inviter-test-code"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.invitationCode").value("own-new-user-code"));
        verify(wechat).insertFirstAuthorizedUser(argThat(user -> Long.valueOf(901L).equals(user.getUserId())), eq("inviter-test-code"));
        verify(wechat, never()).insertSysWechatUser(any());
    }

    @Test public void existingUserCannotBindOrReplaceInviterDuringLogin() throws Exception {
        SysWechatUser existing = new SysWechatUser();
        existing.setUserId(901L);
        existing.setInviterUserId(777L);
        when(wechat.selectByOpenid("local-invitation-openid")).thenReturn(existing);
        SysUser systemUser = new SysUser();
        systemUser.setUserId(901L);
        when(users.selectUserById(901L)).thenReturn(systemUser);
        mvc.perform(post("/miniapp/auth/login").param("code", "local-code").param("invitationCode", "replacement-code"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.wechatUser.inviterUserId").value(777));
        mvc.perform(post("/miniapp/auth/login").param("code", "local-code").param("invitationCode", "another-code"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.wechatUser.inviterUserId").value(777));
        verify(wechat, never()).insertFirstAuthorizedUser(any(), any());
        verify(wechat, never()).insertSysWechatUser(any());
        verify(users, never()).insertUser(any());
    }

    @Test public void existingUserWithoutInviterIgnoresInvitation() throws Exception {
        SysWechatUser existing = new SysWechatUser();
        existing.setUserId(901L);
        when(wechat.selectByOpenid("local-invitation-openid")).thenReturn(existing);
        SysUser systemUser = new SysUser();
        systemUser.setUserId(901L);
        when(users.selectUserById(901L)).thenReturn(systemUser);
        mvc.perform(post("/miniapp/auth/login").param("code", "local-code").param("invitationCode", "1234"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        org.junit.Assert.assertNull(existing.getInviterUserId());
        verify(wechat, never()).insertFirstAuthorizedUser(any(), any());
        verify(wechat, never()).insertSysWechatUser(any());
    }

    @Test public void noInvitationKeepsOriginalFirstLoginEntrance() throws Exception {
        mvc.perform(post("/miniapp/auth/login").param("code", "local-code"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(wechat).insertSysWechatUser(argThat(user -> Long.valueOf(901L).equals(user.getUserId())));
        verify(wechat, never()).insertFirstAuthorizedUser(any(), any());
    }
}
