package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.web.controller.miniapp.CpsWalletController;

/** Controller/principal unit contract, not a full HTTP security-filter-chain test. */
public class WalletIdentityTest {
    @After public void cleanup() {
        SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes();
    }
    @Test public void bothReadsUseLoginIdentityRegardlessOfRequestUserId() {
        WalletReadService wallets = mock(WalletReadService.class);
        CpsWalletController controller = new CpsWalletController(wallets);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("userId", "999"); request.addParameter("requestUserId", "999");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        login(41L);
        controller.account(); controller.flows("withdraw", null, null, 2, 10);
        login(42L);
        controller.account(); controller.flows(null, null, null, 1, 100);
        verify(wallets).account(41L); verify(wallets).account(42L);
        verify(wallets).flows(41L, "withdraw", null, null, 2, 10);
        verify(wallets).flows(42L, null, null, null, 1, 100);
        verifyNoMoreInteractions(wallets);
    }
    @Test public void unauthenticatedReadsNeverReachStorage() {
        SecurityContextHolder.clearContext();
        WalletReadService wallets = mock(WalletReadService.class);
        CpsWalletController controller = new CpsWalletController(wallets);
        assertThrows(ServiceException.class, controller::account);
        assertThrows(ServiceException.class, () -> controller.flows(null, null, null, 1, 10));
        verifyNoInteractions(wallets);
    }
    private static void login(long id) {
        SysUser user = new SysUser(); user.setUserId(id);
        LoginUser principal = new LoginUser(id, 1L, user, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
