package com.ruoyi.web.controller.miniapp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.cps.mapper.OrderReadMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class MiniappOrdersQueryControllerTest {
    @After public void clear() { SecurityContextHolder.clearContext(); }
    @Test public void ignoresForgedUserIdAndQueriesAuthenticatedUser() throws Exception {
        OrderReadMapper mapper = mock(OrderReadMapper.class);
        when(mapper.listForUser(7L)).thenReturn(List.of(Map.of("displayStatus", "待收货")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new LoginUser(7L, 1L, null, null), null, Collections.emptyList()));
        MockMvcBuilders.standaloneSetup(new MiniappOrdersQueryController(mapper)).build()
            .perform(get("/miniapp/orders").param("userId", "99"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].displayStatus").value("待收货"));
        verify(mapper).listForUser(7L);
        verify(mapper, never()).listForUser(99L);
    }
}
