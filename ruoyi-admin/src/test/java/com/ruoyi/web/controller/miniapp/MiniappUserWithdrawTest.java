package com.ruoyi.web.controller.miniapp;

import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class MiniappUserWithdrawTest
{
    @Test
    public void withdrawalRequestsAlwaysReportUnavailable() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MiniappUserController()).build();
        for (String body : new String[] {"{}", "{\"amount\":\"1.00\",\"withdrawType\":\"wechat\"}"})
        {
            mvc.perform(post("/miniapp/user/withdraw/apply")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("提现申请暂未开放"));
        }
    }
}
