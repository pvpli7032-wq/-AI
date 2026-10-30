package com.jd.open.api.sdk.request;

import com.jd.open.api.sdk.response.AbstractResponse;

/** Compilation-only request marker preserving the response type. */
public interface JdRequest<T extends AbstractResponse> {
}
