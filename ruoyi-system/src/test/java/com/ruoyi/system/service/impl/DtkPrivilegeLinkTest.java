package com.ruoyi.system.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.union.dtk.api.client.DtkApiClient;
import com.ruoyi.union.dtk.api.request.mastertool.DtkGetPrivilegeLinkRequest;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkGetPrivilegeLinkResponse;
import com.ruoyi.union.dtk.config.DtkConfig;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DtkPrivilegeLinkTest {
    @Test
    public void failedConversionNeverGeneratesPasswordFromRawGoodsUrl() {
        DtkApiClient client = mock(DtkApiClient.class);
        DtkApiResponse<DtkGetPrivilegeLinkResponse> failure = DtkApiResponse.buildFail(1, "failed");
        when(client.execute(any(DtkGetPrivilegeLinkRequest.class))).thenReturn(failure);
        DtkApiServiceImpl service = new DtkApiServiceImpl();
        ReflectionTestUtils.setField(service, "dtkConfig", new DtkConfig());
        try (MockedStatic<DtkApiClient> factory = mockStatic(DtkApiClient.class)) {
            factory.when(() -> DtkApiClient.getInstance("key", "secret")).thenReturn(client);
            assertThrows(ServiceException.class,
                    () -> service.getPrivilegeLink("123", null, "pid", "name", "key", "secret"));
            verify(client).execute(any(DtkGetPrivilegeLinkRequest.class));
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    public void conversionExceptionNeverGeneratesPasswordFromRawGoodsUrl() {
        DtkApiClient client = mock(DtkApiClient.class);
        when(client.execute(any(DtkGetPrivilegeLinkRequest.class))).thenThrow(new RuntimeException("failed"));
        DtkApiServiceImpl service = new DtkApiServiceImpl();
        ReflectionTestUtils.setField(service, "dtkConfig", new DtkConfig());
        try (MockedStatic<DtkApiClient> factory = mockStatic(DtkApiClient.class)) {
            factory.when(() -> DtkApiClient.getInstance("key", "secret")).thenReturn(client);
            assertThrows(ServiceException.class,
                    () -> service.getPrivilegeLink("123", null, "pid", "name", "key", "secret"));
            verify(client).execute(any(DtkGetPrivilegeLinkRequest.class));
            verifyNoMoreInteractions(client);
        }
    }
}
