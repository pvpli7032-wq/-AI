package com.pdd.pop.sdk.http;

/** Development placeholder. Replace it with the official SDK before real use. */
public class PopHttpClient implements PopClient {
    public PopHttpClient(String clientId, String clientSecret) {
        // Construction must remain safe while development services start.
    }

    @Override
    public <T extends PopBaseHttpResponse> T syncInvoke(PopBaseHttpRequest<T> request) {
        throw new UnsupportedOperationException("占位 SDK：请安装拼多多官方 SDK 后再调用真实接口");
    }
}
