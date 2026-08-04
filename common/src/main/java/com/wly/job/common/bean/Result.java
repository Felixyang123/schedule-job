package com.wly.job.common.bean;

import lombok.Data;

/**
 * 统一 HTTP 响应包装：Admin 与 Worker 之间注册/心跳等 REST 接口的通用返回结构，
 * 以 {@code code="1"} 表示成功、{@code "-1"} 表示失败，业务数据承载在 {@code data} 中。
 */
@Data
public class Result<T> {
    public static final String SUCCESS_CODE = "1";
    public static final String FAIL_CODE = "-1";

    private String code;

    private String message;

    private Boolean success;

    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(SUCCESS_CODE);
        result.setSuccess(true);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(String message) {
        return fail(FAIL_CODE, message);
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
