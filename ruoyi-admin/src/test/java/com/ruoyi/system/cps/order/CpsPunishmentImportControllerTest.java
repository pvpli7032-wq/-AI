package com.ruoyi.system.cps.order;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.web.controller.cps.CpsPunishmentImportController;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CpsPunishmentImportControllerTest {
    @After public void clear() { SecurityContextHolder.clearContext(); }
    @Test public void rejectsUnknownFieldsInsteadOfSilentlyDroppingImportData() throws Exception {
        PunishmentImportService service = mock(PunishmentImportService.class);
        CpsPunishmentImportController controller = new CpsPunishmentImportController(service);
        for (String body : new String[] {
            "{\"platform\":\"tbk\",\"file\":\"test.csv\",\"lines\":[],\"unknown\":1}",
            "{\"platform\":\"tbk\",\"file\":\"test.csv\",\"lines\":[{\"orderNo\":\"1\",\"penaltyAmount\":1,\"reason\":\"ignored\"}]}"
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            AjaxResult result = controller.importLines(request);
            assertEquals(500, result.get("code"));
        }
        verifyNoInteractions(service);
    }
    @Test public void rejectsPayloadExceedingLimit() throws Exception {
        PunishmentImportService service = mock(PunishmentImportService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[256 * 1024 + 1]);
        assertEquals(500, new CpsPunishmentImportController(service).importLines(request).get("code"));
        verifyNoInteractions(service);
    }
    @Test public void authenticatedOperatorIsUsedInsteadOfClientInput() throws Exception {
        PunishmentImportService service = mock(PunishmentImportService.class);
        when(service.importLines(anyString(), anyString(), anyLong(), anyList())).thenReturn(5L);
        CpsPunishmentImportController controller = new CpsPunishmentImportController(service);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new LoginUser(7L, 1L, null, null), null, Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("{\"platform\":\"tbk\",\"file\":\"test.csv\",\"lines\":[{\"orderNo\":\"1\",\"penaltyAmount\":1}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, controller.importLines(request).get("code"));
        verify(service).importLines(eq("tbk"), eq("test.csv"), eq(7L), anyList());
    }
}
