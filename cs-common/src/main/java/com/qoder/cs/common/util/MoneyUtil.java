package com.qoder.cs.common.util;

import com.qoder.cs.common.exception.BizException;

/** 金额全程 long 型「分」，对齐 ride/payment。 */
public final class MoneyUtil {

    public static void requireValid(long amount) {
        if (amount <= 0 || amount > 1_000_000_000_000L) {
            throw BizException.param("金额越界（分）: " + amount);
        }
    }

    public static String yuan(long cents) {
        return String.format("%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    private MoneyUtil() {
    }
}