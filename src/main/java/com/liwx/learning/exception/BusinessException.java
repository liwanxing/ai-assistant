package com.liwx.learning.exception;

import com.liwx.learning.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常：业务逻辑出错时手动抛出，由全局异常处理器统一捕获
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.ERROR.getCode();
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
