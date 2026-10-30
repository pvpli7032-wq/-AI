package com.pdd.pop.sdk.http;

/** Compile-only response shape; placeholder clients never return responses. */
public class PopBaseHttpResponse {
    public ErrorResponse getErrorResponse() {
        return null;
    }

    public static class ErrorResponse {
        public String getErrorMsg() {
            return null;
        }
    }
}
