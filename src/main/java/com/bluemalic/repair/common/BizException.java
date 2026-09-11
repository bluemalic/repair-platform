package com.bluemalic.repair.common;

/**
 * 业务异常。业务规则不满足时直接抛它，由 {@link GlobalExceptionHandler} 统一转成返回体。
 *
 * <p>不要在 Service / Controller 里 try-catch 之后 return 错误码 —— 那会让正常的业务分支
 * 被错误处理代码淹没，也容易漏掉某一个分支。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 需要用更具体的提示覆盖默认文案时使用。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
