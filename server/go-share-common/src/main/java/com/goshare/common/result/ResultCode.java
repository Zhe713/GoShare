package com.goshare.common.result;

/**
 * 通用状态码
 */
public final class ResultCode {

    /** 成功 */
    public static final Integer SUCCESS = 200;

    /** 参数错误 */
    public static final Integer PARAM_ERROR = 400;

    /** 未登录或登录失效 */
    public static final Integer UNAUTHORIZED = 401;

    /** 没有权限 */
    public static final Integer FORBIDDEN = 403;

    /** 资源不存在 */
    public static final Integer NOT_FOUND = 404;

    /** 业务失败 */
    public static final Integer BUSINESS_ERROR = 500;

    private ResultCode() {
    }
}
