package com.liwx.learning.exception;

import com.liwx.learning.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常：业务逻辑出错时手动抛出，由全局异常处理器统一捕获
 *
 * 为什么继承 RuntimeException 而不是 Exception？
 *   1. Checked Exception（非运行时异常）要求方法签名上声明 throws，
 *      每个调用的方法都要加，侵入性强，代码臃肿
 *   2. RuntimeException 不需要声明 throws，直接 throw 就行，代码干净
 *   所以选 RuntimeException 不是因为"只有它才能被捕获"，
 *   而是为了用起来方便，不用到处写 throws
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
