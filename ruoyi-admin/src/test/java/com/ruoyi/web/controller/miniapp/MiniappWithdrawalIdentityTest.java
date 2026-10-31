package com.ruoyi.web.controller.miniapp;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.cps.withdraw.*;

/** HTTP identity boundaries; accounting and SQL are covered by WithdrawalMySqlTest. */
public class MiniappWithdrawalIdentityTest {
    private MockMvc mvc;
    private WithdrawalService service;
    @Before public void setUp() {
        service = mock(WithdrawalService.class);
        when(service.available()).thenReturn(true);
        MiniappUserController legacy = new MiniappUserController();
        ReflectionTestUtils.setField(legacy, "withdrawals", service);
        mvc = MockMvcBuilders.standaloneSetup(legacy, new MiniappWithdrawalsController(service)).build();
        when(service.apply(anyLong(), any())).thenAnswer(call -> view("WD-synthetic-" + call.getArgument(0)));
        when(service.list(anyLong(), anyInt(), anyInt())).thenAnswer(call ->
            Map.of("rows", List.of(view("WD-synthetic-" + call.getArgument(0))), "total", 1));
        login(12001L);
    }
    @After public void tearDown() { SecurityContextHolder.clearContext(); }

    @Test public void legacyApplyIgnoresForgedIdentityAndPayeeAndUsesRequestKey() throws Exception {
        mvc.perform(post("/miniapp/user/withdraw/apply").contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"12.34\",\"requestId\":\"identity-test\",\"userId\":99999,\"pid\":\"forged\","
                + "\"recipientAccount\":\"attacker-account\",\"recipientName\":\"not-the-user\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.withdrawNo").value("WD-synthetic-12001"));
        verify(service).apply(eq(12001L), argThat(r -> r.amount().compareTo(new BigDecimal("12.34")) == 0 && "identity-test".equals(r.requestId())));
        verify(service, never()).apply(eq(99999L), any());
    }

    @Test public void bothListPathsFollowTheCurrentUserAcrossRequests() throws Exception {
        for (String path : List.of("/miniapp/user/withdraw/list", "/miniapp/withdrawals")) {
            login(12001L);
            mvc.perform(get(path).param("userId", "12002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].withdrawNo").value("WD-synthetic-12001"));
            login(12002L);
            mvc.perform(get(path).param("userId", "12001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].withdrawNo").value("WD-synthetic-12002"));
        }
        verify(service, times(2)).list(12001L, 1, 10);
        verify(service, times(2)).list(12002L, 1, 10);
    }

    @Test public void newApplyUsesLoginAndDoesNotReturnSensitiveSnapshots() throws Exception {
        mvc.perform(post("/miniapp/withdrawals").param("userId", "99999").param("pid", "forged")
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"12.34\",\"requestId\":\"new-identity-test\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.withdrawNo").value("WD-synthetic-12001"))
            .andExpect(jsonPath("$.data.recipientAccount").doesNotExist()).andExpect(jsonPath("$.data.recipientName").doesNotExist())
            .andExpect(jsonPath("$.data.recipient").value("****1234")).andExpect(jsonPath("$.data.policySnapshot").doesNotExist());
        verify(service).apply(eq(12001L), any(WithdrawalApplyRequest.class));
    }

    @Test public void noLoginCannotReachService() {
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> new MiniappWithdrawalsController(service).list(1,10));
        verify(service, never()).list(anyLong(), anyInt(), anyInt());
    }

    @Test public void legacyNumericJsonRetainsDecimalPrecisionBeforeValidation() throws Exception {
        mvc.perform(post("/miniapp/user/withdraw/apply").contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1.00499999999999999,\"requestId\":\"decimal-identity-test\"}"))
            .andExpect(status().isOk());
        verify(service).apply(eq(12001L), argThat(r -> r.amount().equals(new BigDecimal("1.00499999999999999"))));
    }

    private static void login(long id) {
        SysUser user = new SysUser(); user.setUserId(id); user.setUserName("synthetic-" + id);
        LoginUser login = new LoginUser(id, null, user, Collections.emptySet());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
    }
    private static WithdrawalView view(String no) {
        return new WithdrawalView(no, new BigDecimal("12.34"), new BigDecimal("0.00"), WithdrawalStatus.APPROVED,
            "mock", "****1234", LocalDateTime.of(2026,10,31,0,0), LocalDateTime.of(2026,10,31,0,0), "已审核，尚未打款");
    }
}
