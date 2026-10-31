package com.ruoyi.web.controller.miniapp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.system.service.ISysUserService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.Filter;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class MiniappAuthSecurityTest
{
    private MiniappAuthController controller;
    private TokenService tokens;
    private Map<String, LoginUser> sessions;
    private RedisCache redis;

    @Before
    public void setUp()
    {
        controller = new MiniappAuthController();
        ReflectionTestUtils.setField(controller, "tokenHeader", "Authorization");
        ReflectionTestUtils.setField(controller, "userService", mock(ISysUserService.class));
        redis = mock(RedisCache.class);
        sessions = new HashMap<>();
        doAnswer(call -> {
            sessions.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(redis).setCacheObject(anyString(), any(), anyInt(), eq(TimeUnit.MINUTES));
        when(redis.getCacheObject(anyString())).thenAnswer(call -> sessions.get(call.getArgument(0)));
        tokens = new TokenService();
        ReflectionTestUtils.setField(tokens, "header", "Authorization");
        ReflectionTestUtils.setField(tokens, "secret", "local-test-only-token-secret-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789");
        ReflectionTestUtils.setField(tokens, "expireTime", 60);
        ReflectionTestUtils.setField(tokens, "redisCache", redis);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @After
    public void tearDown()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void generatedPasswordsAreDistinctAndTooLongForBackendLogin()
    {
        java.util.List<String> plaintext = new java.util.ArrayList<>();
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class, CALLS_REAL_METHODS))
        {
            security.when(() -> SecurityUtils.encryptPassword(anyString())).thenAnswer(call -> {
                plaintext.add(call.getArgument(0));
                return call.callRealMethod();
            });
            SysUser first = ReflectionTestUtils.invokeMethod(controller, "createNewUser", "test-openid-12345678");
            SysUser second = ReflectionTestUtils.invokeMethod(controller, "createNewUser", "test-openid-87654321");
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(2, plaintext.size());
            assertNotEquals(plaintext.get(0), plaintext.get(1));
            for (String password : plaintext)
            {
                assertTrue(password.length() > com.ruoyi.common.constant.UserConstants.PASSWORD_MAX_LENGTH);
                assertTrue(password.length() < 72);
            }
            assertTrue(SecurityUtils.matchesPassword(plaintext.get(0), first.getPassword()));
            assertFalse(SecurityUtils.matchesPassword("123456", first.getPassword()));
        }
    }

    @Test
    public void refreshReturnsCurrentJwtAndExtendsOnlyLiveSession()
    {
        SysUser user = new SysUser();
        user.setUserId(42L);
        user.setUserName("wx_refresh_test");
        LoginUser login = new LoginUser(42L, null, user, java.util.Collections.emptySet());
        String jwt = tokens.createToken(login);
        long previousExpiry = System.currentTimeMillis() + 1_000L;
        login.setExpireTime(previousExpiry);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt);
        AjaxResult result = controller.refresh(request);
        assertEquals(200, result.get("code"));
        assertEquals(jwt, ((Map<?, ?>) result.get("data")).get("token"));
        assertTrue(login.getExpireTime() > previousExpiry);
        verify(redis, times(2)).setCacheObject(anyString(), same(login), eq(60), eq(TimeUnit.MINUTES));
        assertEquals(1, sessions.size());
        MockHttpServletRequest invalid = new MockHttpServletRequest();
        invalid.addHeader("Authorization", "Bearer invalid-token");
        assertNotEquals(200, controller.refresh(invalid).get("code"));
        assertEquals(1, sessions.size());
        // 模拟 Redis TTL 到期删除会话。
        sessions.clear();
        assertNotEquals(200, controller.refresh(request).get("code"));
    }

    @Test
    public void refreshRejectsUnauthenticatedRequest()
    {
        assertThrows(ServiceException.class, () -> controller.refresh(new MockHttpServletRequest()));
        assertTrue(sessions.isEmpty());
    }

    @Test
    public void refreshEndpointRequiresLiveJwtAndReturnsTokenForEmptyJson() throws Exception
    {
        SysUser user = new SysUser();
        user.setUserId(42L);
        user.setUserName("wx_refresh_test");
        LoginUser login = new LoginUser(42L, null, user, java.util.Collections.emptySet());
        String jwt = tokens.createToken(login);
        JwtAuthenticationTokenFilter tokenFilter = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(tokenFilter, "tokenService", tokens);
        // 模拟 SecurityConfig 的认证边界，JWT 解析与 Redis 缓存均走真实 TokenService。
        Filter security = (request, response, chain) -> {
            SecurityContextHolder.clearContext();
            try
            {
                tokenFilter.doFilter(request, response, (req, res) -> {
                    if (SecurityContextHolder.getContext().getAuthentication() == null)
                    {
                        ((jakarta.servlet.http.HttpServletResponse) res).sendError(401);
                        return;
                    }
                    chain.doFilter(req, res);
                });
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(security).build();
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}")
                .header("Authorization", "Bearer " + jwt))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.token").value(jwt));
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());
        sessions.clear(); // 模拟 Redis 中的会话 TTL 已到期。
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}")
                .header("Authorization", "Bearer " + jwt))
            .andExpect(status().isUnauthorized());
    }
}
