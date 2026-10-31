package com.ruoyi.web.controller.miniapp;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import cn.binarywang.wx.miniapp.api.WxMaService;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.SecurityConfig;
import com.ruoyi.framework.config.properties.PermitAllUrlProperties;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.security.handle.AuthenticationEntryPointImpl;
import com.ruoyi.framework.security.handle.LogoutSuccessHandlerImpl;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = MiniappRefreshSecurityChainTest.TestConfig.class)
@TestPropertySource(properties = {"token.header=Authorization", "token.secret=local-test-only-token-secret-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789", "token.expireTime=60"})
public class MiniappRefreshSecurityChainTest {
    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private TokenService tokens;
    @Autowired private RedisCache redis;
    private final Map<String, LoginUser> sessions = new ConcurrentHashMap<>();
    private MockMvc mvc;

    @Before
    public void setUp() {
        reset(redis);
        sessions.clear();
        doAnswer(call -> { sessions.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(redis).setCacheObject(anyString(), any(), anyInt(), eq(TimeUnit.MINUTES));
        when(redis.getCacheObject(anyString())).thenAnswer(call -> sessions.get(call.getArgument(0)));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    @Test
    public void refreshUsesRealSecurityChainAndRequiresLiveSession() throws Exception {
        SysUser user = new SysUser();
        user.setUserId(42L);
        user.setUserName("wx_refresh_test");
        LoginUser login = new LoginUser(42L, null, user, Collections.emptySet());
        String jwt = tokens.createToken(login);
        long oldExpiry = System.currentTimeMillis() + 1000;
        login.setExpireTime(oldExpiry);
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}")
                .header("Authorization", "Bearer " + jwt))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.token").value(jwt));
        org.junit.Assert.assertTrue(login.getExpireTime() > oldExpiry);
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}"))
            .andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(jsonPath("$.code").value(401));
        sessions.clear();
        mvc.perform(post("/miniapp/auth/refresh").contentType("application/json").content("{}")
                .header("Authorization", "Bearer " + jwt))
            .andExpect(jsonPath("$.code").value(401));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, PermitAllUrlProperties.class})
    static class TestConfig {
        @Bean MiniappAuthController controller() { return new MiniappAuthController(); }
        @Bean WxMaService wxMaService() { return mock(WxMaService.class); }
        @Bean ISysWechatUserService wechatUsers() { return mock(ISysWechatUserService.class); }
        @Bean ISysUserService users() { return mock(ISysUserService.class); }
        @Bean ISysTbkAgentApplyService applies() { return mock(ISysTbkAgentApplyService.class); }
        @Bean ISysUnionPlatformService platforms() { return mock(ISysUnionPlatformService.class); }
        @Bean ISysAgentUnionRelationService relations() { return mock(ISysAgentUnionRelationService.class); }
        @Bean RedisCache redis() { return mock(RedisCache.class); }
        @Bean org.springframework.data.redis.core.RedisTemplate<?, ?> redisTemplate() { return mock(org.springframework.data.redis.core.RedisTemplate.class); }
        @Bean TokenService tokens(RedisCache redis) {
            TokenService service = new TokenService();
            ReflectionTestUtils.setField(service, "header", "Authorization");
            ReflectionTestUtils.setField(service, "secret", "local-test-only-token-secret-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789");
            ReflectionTestUtils.setField(service, "expireTime", 60);
            ReflectionTestUtils.setField(service, "redisCache", redis);
            return service;
        }
        @Bean JwtAuthenticationTokenFilter jwtFilter() { return new JwtAuthenticationTokenFilter(); }
        @Bean AuthenticationEntryPointImpl entryPoint() { return new AuthenticationEntryPointImpl(); }
        @Bean LogoutSuccessHandlerImpl logout() { return new LogoutSuccessHandlerImpl(); }
        @Bean CorsFilter corsFilter() {
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", new CorsConfiguration());
            return new CorsFilter(source);
        }
    }
}
