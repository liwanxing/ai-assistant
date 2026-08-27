package com.liwx.aiassistant.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.liwx.aiassistant.common.Result;
import com.liwx.aiassistant.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一捕获所有 Controller 抛出的异常
 * 避免异常信息直接暴露给前端，统一返回 Result 格式
 *
 * Spring 源码调用链路（DispatcherServlet 为核心）：
 *   1. DispatcherServlet.doDispatch() 调用 Controller 方法，外面包了 try-catch
 *   2. Controller 调 Service，Service 抛 BusinessException，Controller 没有 try-catch，异常透传
 *   3. doDispatch 的 catch 接住异常，存入 dispatchException
 *   4. processDispatchResult() 发现 dispatchException 不为 null，调用 processHandlerException()
 *   5. processHandlerException 遍历 HandlerExceptionResolver 列表
 *   6. ExceptionHandlerExceptionResolver 按异常类型匹配 @ExceptionHandler 方法
 *   7. 匹配到后反射调用本类的 handleBusinessException()，返回 Result 给前端
 *
 * 简单理解：框架在调你的 Controller 时包了一层 try-catch，
 *         异常会交给这里的 @ExceptionHandler 统一处理，返回标准 {code, message, data} 格式
 *         而不是走 Spring 默认行为（返回 {timestamp, status, error, message, path} 格式，与项目统一的 Result 不一致）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常（代码里主动抛出的 BusinessException）
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 捕获参数校验异常（@Valid 校验失败时自动抛出）
     * 取第一条校验错误信息返回给前端
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数错误";
        log.warn("参数校验失败: {}", message);
        return Result.error(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 捕获 Sa-Token 未登录异常（拦截器校验未通过时抛出）
     */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        log.warn("未登录访问: {}", e.getMessage());
        return Result.error(401, "未登录或登录已过期，请重新登录");
    }

    /**
     * 捕获 Sa-Token 角色不匹配异常（@SaCheckRole 校验未通过时抛出）
     * 比如普通用户去调用了标了 @SaCheckRole("admin") 的接口
     */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRoleException(NotRoleException e) {
        log.warn("角色不足: 需要角色={}, {}", e.getRole(), e.getMessage());
        return Result.error(403, "权限不足，需要角色: " + e.getRole());
    }

    /**
     * 捕获 Sa-Token 权限不匹配异常（@SaCheckPermission 校验未通过时抛出）
     * 比如访客去调用了标了 @SaCheckPermission("user:delete") 的接口
     */
    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermissionException(NotPermissionException e) {
        log.warn("权限不足: 需要权限={}, {}", e.getCode(), e.getMessage());
        return Result.error(403, "权限不足，需要权限: " + e.getCode());
    }

    /**
     * 捕获所有其他未处理的异常（兜底）
     *
     * 没有这个方法时，Spring 也有默认兜底，返回格式：
     *   {timestamp, status, error, message, path} 甚至 HTML 错误页
     * 这里覆盖它，统一返回 {code, message, data}
     *
     * 注意：以上兜底仅限"同一个系统内"（同一个 DispatcherServlet）
     * 跨系统调用（RestTemplate 等调别人接口）时，对方的兜底格式你管不着，
     * 你收到的只是 HTTP 响应（200 正常 / 4xx 5xx 错误）
     * 你的 HTTP 客户端遇到 4xx 5xx 会自己抛异常，所以调用方要自己 try-catch
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ResultCode.ERROR);
    }
}
