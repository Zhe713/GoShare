package com.goshare.common.exception;

import com.goshare.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：业务规则不满足时抛出，由全局异常处理转成统一返回
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BUSINESS_ERROR;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
