package com.qoder.cs.common.exception;

import com.qoder.cs.common.result.ResultCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException param(String message) {
        return new BizException(ResultCode.PARAM_ERROR, message);
    }

    public static BizException notFound(String message) {
        return new BizException(ResultCode.NOT_FOUND, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ResultCode.CONFLICT, message);
    }

    public static BizException biz(String message) {
        return new BizException(ResultCode.BIZ_ERROR, message);
    }

    public static BizException downstream(String message) {
        return new BizException(ResultCode.DOWNSTREAM_ERROR, message);
    }
}