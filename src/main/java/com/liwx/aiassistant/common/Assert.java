package com.liwx.aiassistant.common;

import com.liwx.aiassistant.exception.BusinessException;

/**
 * 断言工具类：判断条件不满足时直接抛出业务异常
 * 用法：Assert.notNull(user, ResultCode.NOT_FOUND);
 */
public class Assert {

    /**
     * 条件为 true 才通过，否则抛业务异常
     */
    public static void isTrue(boolean condition, ResultCode resultCode) {
        if (!condition) {
            throw new BusinessException(resultCode);
        }
    }

    /**
     * 对象不为 null 才通过，否则抛业务异常
     */
    public static void notNull(Object obj, ResultCode resultCode) {
        if (obj == null) {
            throw new BusinessException(resultCode);
        }
    }

    /**
     * 字符串不为空才通过，否则抛业务异常
     */
    public static void notEmpty(String str, ResultCode resultCode) {
        if (str == null || str.isEmpty()) {
            throw new BusinessException(resultCode);
        }
    }
}
