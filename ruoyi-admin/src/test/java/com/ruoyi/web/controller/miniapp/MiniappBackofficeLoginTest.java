package com.ruoyi.web.controller.miniapp;

import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.framework.web.service.SysPasswordService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.framework.web.service.UserDetailsServiceImpl;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysUserService;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the real creation, login precheck, user lookup and password validation paths. */
public class MiniappBackofficeLoginTest
{
    @Test
    public void createdUserCannotUseEitherFixedOrGeneratedPasswordForBackoffice() throws Exception
    {
        ISysUserService users = mock(ISysUserService.class);
        AtomicReference<SysUser> created = new AtomicReference<>();
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setUserId(1001L);
            created.set(user);
            return 1;
        }).when(users).insertUser(any(SysUser.class));
        MiniappAuthController controller = new MiniappAuthController();
        ReflectionTestUtils.setField(controller, "userService", users);
        AtomicReference<String> raw = new AtomicReference<>();
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class, CALLS_REAL_METHODS))
        {
            security.when(() -> SecurityUtils.encryptPassword(anyString())).thenAnswer(call -> {
                raw.set(call.getArgument(0));
                return call.callRealMethod();
            });
            ReflectionTestUtils.invokeMethod(controller, "createNewUser", "test-openid-12345678");
        }
        SysUser user = created.get();
        assertNotNull(user);
        assertTrue(raw.get().length() > UserConstants.PASSWORD_MAX_LENGTH);
        assertTrue(SecurityUtils.matchesPassword(raw.get(), user.getPassword()));
        assertFalse(SecurityUtils.matchesPassword("123456", user.getPassword()));
        when(users.selectUserByUserName(user.getUserName())).thenReturn(user);

        RedisCache cache = mock(RedisCache.class);
        SysPasswordService passwords = new SysPasswordService();
        ReflectionTestUtils.setField(passwords, "redisCache", cache);
        ReflectionTestUtils.setField(passwords, "maxRetryCount", 5);
        ReflectionTestUtils.setField(passwords, "lockTime", 10);
        SysPermissionService permissions = mock(SysPermissionService.class);
        when(permissions.getMenuPermission(any())).thenReturn(Collections.emptySet());
        UserDetailsServiceImpl details = new UserDetailsServiceImpl();
        ReflectionTestUtils.setField(details, "userService", users);
        ReflectionTestUtils.setField(details, "passwordService", passwords);
        ReflectionTestUtils.setField(details, "permissionService", permissions);
        AuthenticationManager manager = authentication -> new UsernamePasswordAuthenticationToken(
            details.loadUserByUsername(authentication.getName()), authentication.getCredentials());
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectCaptchaEnabled()).thenReturn(false);
        SysLoginService login = new SysLoginService();
        ReflectionTestUtils.setField(login, "authenticationManager", manager);
        ReflectionTestUtils.setField(login, "configService", config);
        ReflectionTestUtils.setField(login, "userService", users);
        ReflectionTestUtils.setField(login, "tokenService", mock(TokenService.class));
        ReflectionTestUtils.setField(login, "redisCache", cache);

        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(), any())).thenReturn("login rejected");
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class))
        {
            spring.when(() -> SpringUtils.getBean("scheduledExecutorService")).thenReturn(executor);
            spring.when(() -> SpringUtils.getBean(MessageSource.class)).thenReturn(messages);
            assertThrows(RuntimeException.class, () -> login.login(user.getUserName(), "123456", null, null));
            assertThrows(RuntimeException.class, () -> login.login(user.getUserName(), raw.get(), null, null));
            verify(users, times(1)).selectUserByUserName(user.getUserName());
        }
        finally
        {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
