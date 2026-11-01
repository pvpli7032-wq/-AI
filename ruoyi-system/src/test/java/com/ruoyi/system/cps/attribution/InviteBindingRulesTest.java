package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import org.junit.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.InviteBindingMapper;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.mapper.SysWechatUserMapper;

public class InviteBindingRulesTest {
    private SysWechatUser user(long id, String openid) {
        SysWechatUser result = new SysWechatUser();
        result.setUserId(id);
        result.setOpenid(openid);
        return result;
    }

    @Test public void invalidInvitationDoesNotPreventRegistration() {
        var mapper = mock(InviteBindingMapper.class);
        var users = mock(SysWechatUserMapper.class);
        when(mapper.lockInvitationMutex()).thenReturn(1);
        when(mapper.lockSystemUser(1L)).thenReturn(1L);
        when(mapper.databaseUtcNow()).thenReturn(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(mapper.appendAudit(anyLong(), any(), any(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(users.insertSysWechatUser(any())).thenReturn(1);
        var candidate = user(1, "new-openid");
        assertEquals(1, new InviteBindingService(mapper, users).registerFirstAuthorization(candidate, "9999"));
        assertNull(candidate.getInviterUserId());
        assertTrue(candidate.getInvitationCode().matches("[1-9][0-9]{3}"));
        verify(users).insertSysWechatUser(candidate);
    }

    @Test public void codeSpaceExpandsOnlyAfterFourDigitSpaceIsFull() {
        var mapper = mock(InviteBindingMapper.class);
        when(mapper.lockInvitationMutex()).thenReturn(1);
        when(mapper.lockSystemUser(1L)).thenReturn(1L);
        when(mapper.selectLockedUser(1L)).thenReturn(user(1, "openid"));
        when(mapper.countCodesOfLength(4)).thenReturn(9000L);
        when(mapper.setInvitationCode(eq(1L), anyString())).thenReturn(1);
        String code = new InviteBindingService(mapper, mock(SysWechatUserMapper.class)).ensureInvitationCode(1L);
        assertTrue(code.matches("[1-9][0-9]{4}"));
    }

    @Test public void administrativeRebindRejectsCycles() {
        var mapper = mock(InviteBindingMapper.class);
        when(mapper.lockInvitationMutex()).thenReturn(1);
        when(mapper.lockSystemUser(1L)).thenReturn(1L);
        var first = user(1, "one");
        var second = user(2, "two");
        second.setInvitationCode("2345");
        second.setInviterUserId(1L);
        when(mapper.selectByUserId(1L)).thenReturn(first);
        when(mapper.selectByUserId(2L)).thenReturn(second);
        when(mapper.findInviter("2345")).thenReturn(second);
        var service = new InviteBindingService(mapper, mock(SysWechatUserMapper.class));
        assertThrows(ServiceException.class, () -> service.changeInviter(1L, 2L, 9L, "test"));
        verify(mapper, never()).changeInviter(anyLong(), any());
    }
}
