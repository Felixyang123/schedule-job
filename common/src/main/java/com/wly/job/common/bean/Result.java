package com.wly.job.common.bean;

import lombok.Data;

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
