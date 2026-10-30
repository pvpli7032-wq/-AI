package com.taobao.api;

/** Compile/test placeholder; implementations must reject actual API requests. */
public interface TaobaoClient {
    <T extends TaobaoResponse> T execute(TaobaoRequest<T> request) throws ApiException;
}
