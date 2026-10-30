package com.ruoyi.framework.web.service;

import java.util.Collections;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionServiceTest
{
    @After
    public void clearContexts()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void checksPermissionsOfAuthenticatedPrincipal()
    {
        LoginUser user = new LoginUser(1L, 1L, new SysUser(), Set.of("system:user:list"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        PermissionService service = new PermissionService();

        assertTrue(service.hasPermi("system:user:list"));
        assertFalse(service.hasPermi("system:user:remove"));
    }
}
