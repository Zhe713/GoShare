package com.goshare.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「内部服务间命令接口」。
 *
 * <p>这类接口不是给用户调用的，而是服务之间协作的命令（如支付回调、券核销、认证审核）。
 * 它们只允许携带 {@code X-Internal-Token} 的服务调用，网关也会从公网路由上把它们挡掉。
 *
 * <p>为什么需要它：这些接口原先和用户 API 混在同一个网关路由下，任何登录用户拿自己的 JWT
 * 就能调用——实测中普通乘客可以把订单直接改成已支付、把券退回后重复使用、自己批自己的车主认证。
 * 加这个注解 + 配套拦截器，等于在"用户面"和"内部命令面"之间划一条线。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalApi {
}
