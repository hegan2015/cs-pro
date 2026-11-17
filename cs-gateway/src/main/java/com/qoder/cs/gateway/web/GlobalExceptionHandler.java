package com.qoder.cs.gateway.web;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.common.result.Result;
import com.qoder.cs.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> biz(BizException e) {
        int http = switch (e.getCode()) {
            case ResultCode.PARAM_ERROR -> 400;
            case ResultCode.NOT_FOUND -> 404;
            case ResultCode.CONFLICT -> 409;
            case ResultCode.BIZ_ERROR -> 400;
            case ResultCode.DOWNSTREAM_ERROR -> 502;
            default -> 500;
        };
        if (http >= 500) {
            log.error("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        }
        return ResponseEntity.status(http).body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500).body(Result.fail(ResultCode.SYSTEM_ERROR, "系统繁忙，请稍后重试"));
    }
}