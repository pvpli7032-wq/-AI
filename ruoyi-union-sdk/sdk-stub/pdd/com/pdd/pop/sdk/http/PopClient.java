package com.pdd.pop.sdk.http;

/** Compile-only subset of the PDD client contract. */
public interface PopClient {
    <T extends PopBaseHttpResponse> T syncInvoke(PopBaseHttpRequest<T> request);
}
