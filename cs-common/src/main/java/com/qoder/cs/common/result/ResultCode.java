package com.qoder.cs.common.result;

/** 错误码三段式（A 用户端 / B 本系统 / C 第三方），对齐 ride/payment 契约。 */
public final class ResultCode {
    public static final String SUCCESS = "00000";
    public static final String PARAM_ERROR = "A0400";
    public static final String NOT_FOUND = "A0404";
    public static final String CONFLICT = "A0409";
    public static final String BIZ_ERROR = "B0001";
    public static final String SYSTEM_ERROR = "B0002";
    public static final String DOWNSTREAM_ERROR = "C0001";

    private ResultCode() {
    }
}