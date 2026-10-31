package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.cps.withdraw.WithdrawalStatus.Option;
import com.ruoyi.web.controller.cps.CpsWithdrawalController;
import com.ruoyi.web.controller.miniapp.MiniappUserController;
import com.ruoyi.web.controller.miniapp.MiniappWithdrawalsController;

/** HTTP representation/route compatibility. The permission matrix exercises actual method security. */
public class WithdrawalStatusContractTest {
    private static final long USER = 12001L;
    private static final List<Option> EXPECTED = List.of(
        new Option("MANUAL_REVIEW", "待人工审核", "审核中", false),
        new Option("APPROVED", "已审核", "打款中", false),
        new Option("SUBMITTING", "提交打款中", "打款中", false),
        new Option("PROCESSING", "通道处理中", "打款中", false),
        new Option("UNKNOWN", "结果未知", "打款中", false),
        new Option("AWAITING_CONFIRMATION", "待收款确认", "打款中", false),
        new Option("TIMED_OUT", "确认超时", "打款中", false),
        new Option("SUCCEEDED", "已打款", "已打款", false),
        new Option("FAILED", "打款失败", "打款失败", true),
        new Option("REJECTED", "审核驳回", "审核驳回", true));
    private WithdrawalService service;
    private MockMvc mvc;

    @Before
    public void setUp() {
        service = mock(WithdrawalService.class);
        when(service.statusOptions()).thenReturn(WithdrawalStatus.options());
        MiniappUserController legacy = new MiniappUserController();
        ReflectionTestUtils.setField(legacy, "withdrawals", service);
        CpsWithdrawalController admin = new CpsWithdrawalController(service,
            new StaticListableBeanFactory().getBeanProvider(MockWithdrawChannel.class));
        mvc = MockMvcBuilders.standaloneSetup(admin, legacy, new MiniappWithdrawalsController(service)).build();
        SysUser user = new SysUser();
        user.setUserId(USER);
        user.setUserName("synthetic-status-contract");
        LoginUser principal = new LoginUser(USER, null, user, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @After
    public void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    public void statusOptionsEndpointContainsEveryDistinctBackendFilterAndDisplayLabel() throws Exception {
        ResultActions result = mvc.perform(get("/cps/withdrawals/status-options"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        assertOptions(result, "$.data");
        verify(service).statusOptions();
        verify(service, never()).listAdmin(any(), anyInt(), anyInt());
    }

    @Test
    public void adminListForwardsEveryRawStatusAndIncludesTheSameOptions() throws Exception {
        for (Option option : EXPECTED) {
            WithdrawalStatus state = WithdrawalStatus.valueOf(option.value());
            when(service.listAdmin(option.value(), 2, 7)).thenReturn(Map.of("rows",
                List.of(Map.of("userId", USER, "withdrawal", view(state))), "total", 1));
            ResultActions result = mvc.perform(get("/cps/withdrawals").param("status", option.value())
                .param("page", "2").param("pageSize", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].userId").value(USER))
                .andExpect(jsonPath("$.rows[0].withdrawal.status").value(option.value()))
                .andExpect(jsonPath("$.rows[0].withdrawal.statusText").value(option.miniappLabel()));
            assertOptions(result, "$.statusOptions");
            verify(service).listAdmin(option.value(), 2, 7);
        }
    }

    @Test
    public void bothMiniappListRoutesExposeExactDisplayAndRefundMessageWithoutChangingRawStatus() throws Exception {
        List<WithdrawalView> rows = Arrays.stream(WithdrawalStatus.values()).map(WithdrawalStatusContractTest::view).toList();
        when(service.list(USER, 1, 10)).thenReturn(Map.of("rows", rows, "total", rows.size()));
        for (String path : List.of("/miniapp/user/withdraw/list", "/miniapp/withdrawals")) {
            ResultActions result = mvc.perform(get(path).param("userId", "99999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.total").value(10)).andExpect(jsonPath("$.rows.length()").value(10));
            for (int index = 0; index < EXPECTED.size(); index++) {
                Option expected = EXPECTED.get(index);
                String prefix = "$.rows[" + index + "]";
                result.andExpect(jsonPath(prefix + ".status").value(expected.value()))
                    .andExpect(jsonPath(prefix + ".statusText").value(expected.miniappLabel()))
                    .andExpect(jsonPath(prefix + ".fundsReturned").value(expected.fundsReturned()))
                    .andExpect(jsonPath(prefix + ".recipient").value("****1234"))
                    .andExpect(jsonPath(prefix + ".fee").value(0.0))
                    .andExpect(jsonPath(prefix + ".recipientAccount").doesNotExist());
                if (expected.fundsReturned()) result.andExpect(jsonPath(prefix + ".message")
                    .value(expected.miniappLabel() + "，金额已退回余额"));
            }
        }
        verify(service, times(2)).list(USER, 1, 10);
        verify(service, never()).list(eq(99999L), anyInt(), anyInt());
    }

    private static void assertOptions(ResultActions result, String prefix) throws Exception {
        assertEquals(10, EXPECTED.size());
        result.andExpect(jsonPath(prefix + ".length()").value(10));
        for (int index = 0; index < EXPECTED.size(); index++) {
            Option expected = EXPECTED.get(index);
            String item = prefix + "[" + index + "]";
            result.andExpect(jsonPath(item + ".value").value(expected.value()))
                .andExpect(jsonPath(item + ".label").value(expected.label()))
                .andExpect(jsonPath(item + ".miniappLabel").value(expected.miniappLabel()))
                .andExpect(jsonPath(item + ".fundsReturned").value(expected.fundsReturned()));
        }
    }

    private static WithdrawalView view(WithdrawalStatus status) {
        WithdrawalRecord row = new WithdrawalRecord();
        row.setWithdrawNo("synthetic-http-status-" + status.name());
        row.setAmount(new BigDecimal("12.34"));
        row.setFee(new BigDecimal("0.00"));
        row.setStatus(status);
        row.setChannel("mock");
        row.setRecipientAccount("synthetic-payee-1234");
        row.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        row.setUpdatedAt(row.getCreatedAt());
        return WithdrawalView.of(row);
    }
}
