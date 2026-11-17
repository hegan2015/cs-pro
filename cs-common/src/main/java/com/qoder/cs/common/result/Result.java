package com.qoder.cs.common.result;

import lombok.Data;

@Data
public class Result<T> {
    private String code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS;
        r.message = "成功";
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return ResultCode.SUCCESS.equals(code);
    }
}