package com.pdd.pop.sdk.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compile-only placeholder for the request API used by the application. */
public abstract class PopBaseHttpRequest<T extends PopBaseHttpResponse> {
    protected final Map<String, Object> params = new LinkedHashMap<>();

    public Map<String, Object> getParamsMap() {
        return params;
    }
}
