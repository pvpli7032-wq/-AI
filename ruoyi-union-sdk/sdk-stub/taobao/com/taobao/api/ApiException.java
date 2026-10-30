package com.taobao.api;

/** Compile/test placeholder; replace with the official Taobao SDK before deployment. */
public class ApiException extends Exception {
    public ApiException(String message) {
        super(message);
    }

    public String getErrMsg() {
        return getMessage();
    }
}
