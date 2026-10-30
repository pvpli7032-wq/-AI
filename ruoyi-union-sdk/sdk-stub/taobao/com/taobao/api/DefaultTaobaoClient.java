package com.taobao.api;

/** Compile/test placeholder; this client never connects to Taobao. */
public class DefaultTaobaoClient implements TaobaoClient {
    public DefaultTaobaoClient(String serverUrl, String appKey, String appSecret) {
        // Construction is intentionally safe for application context initialization.
    }

    @Override
    public <T extends TaobaoResponse> T execute(TaobaoRequest<T> request) throws ApiException {
        throw new ApiException("占位 SDK：淘宝接口不可用，请先安装官方淘宝 SDK");
    }
}
