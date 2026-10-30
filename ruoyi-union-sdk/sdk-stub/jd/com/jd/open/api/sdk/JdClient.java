package com.jd.open.api.sdk;

import com.jd.open.api.sdk.request.JdRequest;
import com.jd.open.api.sdk.response.AbstractResponse;

/** Compilation-only SDK contract; requests must never return fabricated data. */
public interface JdClient {
    <T extends AbstractResponse> T execute(JdRequest<T> request);
}
