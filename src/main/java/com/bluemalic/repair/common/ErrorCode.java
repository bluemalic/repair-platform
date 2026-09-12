package com.bluemalic.repair.common;

/**
 * 业务错误码。
 *
 * <p>与 docs/03-接口规范.md 的错误码表一一对应，本枚举是该表的唯一代码落点 ——
 * 新增错误码必须先改文档，再改这里，不允许在业务代码里写裸数字。
 *
 * <p>分段规则：1xxxx 通用、2xxxx 工单、3xxxx 权限与用户、4xxxx AI 与 SQL 安全、5xxxx 文件与存储。
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    // ---------- 1xxxx 通用 ----------
    PARAM_INVALID(10001, "参数校验失败"),
    NOT_LOGIN(10002, "未登录或登录已过期"),
    NO_PERMISSION(10003, "无权限访问该资源"),
    TOO_MANY_REQUESTS(10004, "请求过于频繁"),
    SYSTEM_ERROR(10005, "系统繁忙，请稍后重试"),
    RESOURCE_NOT_FOUND(10006, "请求的资源不存在"),

    // ---------- 2xxxx 工单 ----------
    TICKET_NOT_FOUND(20001, "工单不存在"),
    TICKET_STATUS_NOT_ALLOWED(20002, "工单当前状态不允许该操作"),
    TICKET_ALREADY_ACCEPTED(20003, "该工单已被其他人接单"),
    TICKET_NOT_YOURS(20004, "无权操作他人工单"),
    TICKET_ALREADY_EVALUATED(20005, "工单已评价，不可重复评价"),
    REPAIR_CODE_INVALID(20006, "报修码无效或已失效"),
    REPAIR_CODE_ROOM_MISMATCH(20007, "报修码与工单房间不一致"),

    // ---------- 3xxxx 权限与用户 ----------
    LOGIN_FAILED(30001, "用户名或密码错误"),
    ACCOUNT_DISABLED(30002, "账号已被禁用"),
    TENANT_NOT_FOUND(30003, "租户不存在或已停用"),

    // ---------- 4xxxx AI 与 SQL 安全 ----------
    AI_INTENT_UNRESOLVED(40001, "自然语言无法解析为查询意图"),
    AI_SQL_REJECTED(40002, "生成的 SQL 未通过安全校验"),
    AI_QUERY_TIMEOUT(40003, "查询超时，请缩小查询范围"),
    AI_MODEL_UNAVAILABLE(40004, "模型服务暂时不可用"),

    // ---------- 5xxxx 文件与存储 ----------
    FILE_TYPE_UNSUPPORTED(50001, "文件类型不支持"),
    FILE_SIZE_EXCEEDED(50002, "文件大小超限"),
    FILE_UPLOAD_FAILED(50003, "文件上传失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
