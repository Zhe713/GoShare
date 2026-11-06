package com.goshare.common.exception;

import com.goshare.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一兜底，保证接口永远返回可读的 JSON。
 *
 * <p>本类由 8 个服务共用，其中网关是 WebFlux（classpath 里既没有 spring-webmvc 也没有
 * jakarta.servlet）。因此：</p>
 * <ul>
 *   <li>只在 Servlet 应用中生效（{@code @ConditionalOnWebApplication}），网关直接跳过本 Bean；</li>
 *   <li>方法签名里不引用 spring-webmvc / jakarta.servlet 专有类型
 *       （如 NoResourceFoundException、MissingServletRequestParameterException、
 *       MethodArgumentTypeMismatchException），这些一律在通用分支里按类名判断。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验不通过: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    /**
     * @Valid 校验失败：返回 400 + 具体字段提示。
     * 不加这段会被兜底成 500「系统繁忙」，让人看不出其实是参数问题。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.joining("；"));
        if (detail.isBlank()) {
            detail = "请求参数不合法";
        }
        log.warn("参数校验不通过: {}", detail);
        return Result.error(400, detail);
    }

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        String type = e.getClass().getName();
        // 路径不存在（Spring Boot 3.2+ 静态资源未命中）：按类名判断，避免依赖 spring-webmvc
        if (type.endsWith("NoResourceFoundException")) {
            log.warn("请求路径不存在: {}", e.getMessage());
            return Result.error(404, "请求的接口不存在");
        }
        // 缺少必填参数（继承自 jakarta.servlet.ServletException，不能出现在方法签名里）
        if (type.endsWith("MissingServletRequestParameterException")) {
            log.warn("缺少必填参数: {}", e.getMessage());
            return Result.error(400, "缺少必填参数");
        }
        // 参数类型不匹配（如 id 传了字母）
        if (type.endsWith("MethodArgumentTypeMismatchException")) {
            log.warn("参数类型不匹配: {}", e.getMessage());
            return Result.error(400, "请求参数类型不正确");
        }
        log.error("系统异常", e);
        return Result.error(500, "系统繁忙，请稍后再试");
    }
}
