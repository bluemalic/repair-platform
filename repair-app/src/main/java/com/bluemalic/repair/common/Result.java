package com.bluemalic.repair.common;

import lombok.Data;
import org.slf4j.MDC;

/**
 * 统一返回体。所有 Controller 都返回它，前端只需判断 {@code code}。
 *
 * <p>traceId 由 {@link TraceIdFilter} 注入 MDC，这里直接读取，业务代码不需要关心，
 * 也不会因为异常分支而丢失 —— 用户截图报错时凭它就能定位整条链路。
 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;
    private String traceId;

    private Result(ErrorCode errorCode, String message, T data) {
        this.code = errorCode.getCode();
        this.message = message;
        this.data = data;
        this.traceId = MDC.get(TraceIdFilter.TRACE_ID);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS, ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode, errorCode.getMessage(), null);
    }

    /** 需要用更具体的提示覆盖错误码默认文案时使用（例如参数校验的具体字段）。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode, message, null);
    }
}
