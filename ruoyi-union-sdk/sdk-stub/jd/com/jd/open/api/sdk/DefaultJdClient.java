package com.jd.open.api.sdk;

import com.jd.open.api.sdk.request.JdRequest;
import com.jd.open.api.sdk.response.AbstractResponse;

/** Compilation-only client. Replace the generated JAR with the official SDK. */
public class DefaultJdClient implements JdClient {
    public DefaultJdClient(String serverUrl, String accessToken, String appKey, String appSecret) {
        // Construction is safe for local application startup and performs no I/O.
    }

    @Override
    public <T extends AbstractResponse> T execute(JdRequest<T> request) {
        throw new UnsupportedOperationException("占位 SDK：京东接口不可调用，请先安装官方 SDK");
    }
}
