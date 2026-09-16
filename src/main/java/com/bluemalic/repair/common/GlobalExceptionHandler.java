package com.bluemalic.repair.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。所有异常在这里收敛成统一返回体，业务代码只管抛。
 *
 * <p>HTTP 状态码只在两类场景区分：**鉴权**（401 / 403，便于前端拦截器识别"该跳登录页"）与
 * **访问方式不对**（404 / 405 / 415——这三种请求根本没进到业务逻辑里，用 HTTP 语义表达最准）；
 * 其余业务失败一律 HTTP 200 + body 里的业务码，前端只读 {@code code}。
 *
 * <p>日志只记录必要信息：手机号、密码等敏感字段不落日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        log.warn("业务异常 code={} message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.ok(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    /** {@code @Valid} 作用在 @RequestBody 上，校验失败抛这个。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + defaultMessage(fieldError))
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.ok(Result.fail(ErrorCode.PARAM_INVALID, message));
    }

    /** {@code @Validated} 作用在方法参数上，校验失败抛这个。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.ok(Result.fail(ErrorCode.PARAM_INVALID, message));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        log.warn("未登录或登录已过期: {}", e.getType());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(ErrorCode.NOT_LOGIN));
    }

    /**
     * 访问了不存在的路径。
     *
     * <p>必须单独处理：否则会被下面的兜底处理器接住，变成 500 + 一整段堆栈。
     * 最典型的触发是浏览器自动请求 {@code /favicon.ico} —— 打开一次文档页面就会刷出一段
     * ERROR 日志，真正的问题反而被淹没。这里按 404 返回，并且只在 DEBUG 级别记一行路径。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNoResourceFound(Exception e) {
        if (log.isDebugEnabled()) {
            log.debug("请求的路径不存在: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 请求方式不匹配（405）与请求体类型不支持（415）。
     *
     * <p>和上面的 404 是**同一类问题**：客户端用错了方式访问，不是服务端故障。不单独处理就会被
     * 兜底的 {@code Exception} 处理器接住，变成 500 + 一整段 ERROR 堆栈——最典型的触发是有人把
     * 接口地址粘进浏览器地址栏（浏览器只会发 GET，而登录、派单这些都是 POST），
     * 打开一次刷一段堆栈，真正的问题反而被淹掉。
     *
     * <p>两个状态码都返回 {@code 10006 请求的资源不存在}：对调用方来说"这个路径 + 这个方法"
     * 确实不存在（{@code POST /api/auth/login} 存在，{@code GET} 不存在），与 404 语义一致，
     * 也就不必为它新增错误码。**不返回"该接口支持哪些方法"**——那等于把接口清单送给探测者。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        if (log.isDebugEnabled()) {
            log.debug("请求方式与接口不匹配: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Result.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        if (log.isDebugEnabled()) {
            log.debug("请求体类型不支持: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Result.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<Result<Void>> handleNotPermission(Exception e) {
        log.warn("无权限访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ErrorCode.NO_PERMISSION));
    }

    /** 兜底。打完整堆栈，但不把细节返回给前端。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    private String defaultMessage(FieldError fieldError) {
        return fieldError.getDefaultMessage() == null ? "校验不通过" : fieldError.getDefaultMessage();
    }
}
