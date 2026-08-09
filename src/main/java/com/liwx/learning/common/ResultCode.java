package com.liwx.learning.common;

import lombok.Getter;

/**
 * 状态码枚举：集中管理所有接口的状态码和信息
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "success"),
    PARAM_ERROR(400, "参数错误"),
    NOT_FOUND(404, "资源不存在"),
    ERROR(500, "服务器内部错误");

    /** 状态码 */
    private final Integer code;

    /** 提示信息 */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
