package com.ruoyi.web.controller.miniapp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import com.alibaba.fastjson2.JSON;
import com.github.pagehelper.PageHelper;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.FastJson2JsonRedisSerializer;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.domain.SysCommissionFlow;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.service.ISysCommissionFlowService;
import com.ruoyi.system.service.ISysTbkAgentApplyService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.ISysWechatUserService;
import jakarta.servlet.Filter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises login -> serialized session -> signed token -> filter -> flow controller.
 * External WeChat, cache transport and the flow service are test doubles; no SQL is tested here.
 */
public class MiniappLoginUserIsolationTest
{
    private MockMvc mvc;
    private TokenService tokenService;
    private ISysUserService userService;
    private ISysWechatUserService wechatUserService;
    private ISysCommissionFlowService flowService;

    @Before
    public void setUp() throws Exception
    {
        SecurityContextHolder.clearContext();
        WxMaService wx = mock(WxMaService.class, RETURNS_DEEP_STUBS);
        userService = mock(ISysUserService.class);
        wechatUserService = mock(ISysWechatUserService.class);
        flowService = mock(ISysCommissionFlowService.class);
        for (String name : List.of("a", "b", "new"))
        {
            WxMaJscode2SessionResult session = new WxMaJscode2SessionResult();
            session.setOpenid("local-test-openid-" + name);
            when(wx.getUserService().getSessionInfo("code-" + name)).thenReturn(session);
        }

        // Use the production Redis serializer so tests do not reuse the login object by reference.
        Map<String, byte[]> sessions = new HashMap<>();
        FastJson2JsonRedisSerializer<Object> serializer = new FastJson2JsonRedisSerializer<>(Object.class);
        RedisCache cache = mock(RedisCache.class);
        doAnswer(call -> {
            sessions.put(call.getArgument(0), serializer.serialize(call.getArgument(1)));
            return null;
        }).when(cache).setCacheObject(anyString(), any(), anyInt(), eq(TimeUnit.MINUTES));
        when(cache.getCacheObject(anyString())).thenAnswer(call -> serializer.deserialize(sessions.get(call.getArgument(0))));
        tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "header", "Authorization");
        ReflectionTestUtils.setField(tokenService, "secret", "local-test-only-token-secret-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789");
        ReflectionTestUtils.setField(tokenService, "expireTime", 60);
        ReflectionTestUtils.setField(tokenService, "redisCache", cache);

        MiniappAuthController auth = new MiniappAuthController();
        ReflectionTestUtils.setField(auth, "wxMaService", wx);
        ReflectionTestUtils.setField(auth, "userService", userService);
        ReflectionTestUtils.setField(auth, "wechatUserService", wechatUserService);
        ReflectionTestUtils.setField(auth, "tbkAgentApplyService", mock(ISysTbkAgentApplyService.class));
        ReflectionTestUtils.setField(auth, "tokenService", tokenService);
        MiniAppCommissionController commission = new MiniAppCommissionController();
        ReflectionTestUtils.setField(commission, "commissionFlowService", flowService);
        JwtAuthenticationTokenFilter jwt = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(jwt, "tokenService", tokenService);
        // Standalone MVC has no SecurityContext persistence filter; isolate requests as production does.
        Filter isolatedJwt = (request, response, chain) -> {
            SecurityContextHolder.clearContext();
            try
            {
                jwt.doFilter(request, response, chain);
            }
            finally
            {
                SecurityContextHolder.clearContext();
                PageHelper.clearPage();
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(auth, commission).addFilters(isolatedJwt).build();
    }

    @After
    public void cleanUp()
    {
        SecurityContextHolder.clearContext();
        PageHelper.clearPage();
    }

    @Test
    public void existingUsersCannotReadEachOthersFlowsEvenWithForgedUserId() throws Exception
    {
        existingUser("a", 101L, 201L);
        existingUser("b", 102L, 202L);
        SysCommissionFlow flowA = flow(1001L, 101L);
        SysCommissionFlow flowB = flow(1002L, 102L);
        when(flowService.selectSysCommissionFlowList(any(SysCommissionFlow.class))).thenAnswer(call -> {
            SysCommissionFlow query = call.getArgument(0);
            // Mirror the existing service's user filter boundary; a null ID would expose both fixtures.
            return List.of(flowA, flowB).stream()
                    .filter(flow -> query.getUserId() == null || query.getUserId().equals(flow.getUserId()))
                    .collect(Collectors.toList());
        });

        String tokenA = login("a");
        String tokenB = login("b");
        assertNotEquals(tokenA, tokenB);
        assertOwnFlow(tokenA, "102", 1001, 101);
        assertOwnFlow(tokenB, "101", 1002, 102);
        // Reusing A after B catches accidental request/session identity reuse.
        assertOwnFlow(tokenA, null, 1001, 101);
        assertIdentity(tokenA, 101L, 201L);
        assertIdentity(tokenB, 102L, 202L);
        verify(flowService, times(2)).selectSysCommissionFlowList(argThat(query -> Long.valueOf(101).equals(query.getUserId())));
        verify(flowService).selectSysCommissionFlowList(argThat(query -> Long.valueOf(102).equals(query.getUserId())));
        verify(userService, never()).insertUser(any(SysUser.class));
    }

    @Test
    public void firstLoginUsesThePersistedNewUserIdentity() throws Exception
    {
        doAnswer(call -> {
            SysUser user = call.getArgument(0);
            user.setUserId(103L);
            return 1;
        }).when(userService).insertUser(any(SysUser.class));
        String token = login("new");
        assertIdentity(token, 103L, null);
        verify(wechatUserService).insertSysWechatUser(argThat(user -> Long.valueOf(103).equals(user.getUserId())));
    }

    private void existingUser(String name, Long id, Long deptId)
    {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setDeptId(deptId);
        user.setUserName("local-test-" + name);
        SysWechatUser wechatUser = new SysWechatUser();
        wechatUser.setUserId(id);
        when(wechatUserService.selectByOpenid("local-test-openid-" + name)).thenReturn(wechatUser);
        when(userService.selectUserById(id)).thenReturn(user);
    }

    private String login(String name) throws Exception
    {
        String response = mvc.perform(post("/miniapp/auth/login").param("code", "code-" + name))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        String token = JSON.parseObject(response).getString("token");
        assertNotNull(token);
        return token;
    }

    private void assertIdentity(String token, Long userId, Long deptId)
    {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        LoginUser restored = tokenService.getLoginUser(request);
        assertNotNull(restored);
        assertEquals(userId, restored.getUserId());
        assertEquals(deptId, restored.getDeptId());
        assertEquals(userId, restored.getUser().getUserId());
        assertTrue(restored.getPermissions().isEmpty());
    }

    private void assertOwnFlow(String token, String forgedUserId, int flowId, int userId) throws Exception
    {
        var request = get("/miniapp/commission/flow/list").header("Authorization", "Bearer " + token);
        if (forgedUserId != null)
        {
            request.param("userId", forgedUserId);
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(flowId))
                .andExpect(jsonPath("$.rows[0].userId").value(userId));
    }

    private SysCommissionFlow flow(Long id, Long userId)
    {
        SysCommissionFlow flow = new SysCommissionFlow();
        flow.setId(id);
        flow.setUserId(userId);
        return flow;
    }
}
