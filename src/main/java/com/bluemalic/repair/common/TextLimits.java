package com.bluemalic.repair.common;

/**
 * 数据库列宽保护：日志与通知的文案由多段拼接而成，用户输入本身可能合法（DTO 最长 255），
 * 拼上前缀后仍会超过列宽。集中截断，避免"状态已改、日志插入失败"这种半截写入。
 *
 * <p>截断只用于展示型文案；业务关键字段（如驳回理由）仍按 DTO 校验后的值落库。
 */
public final class TextLimits {

    /** ticket_log.remark */
    public static final int TICKET_LOG_REMARK = 255;

    /** notification.title */
    public static final int NOTIFICATION_TITLE = 64;

    /** notification.content */
    public static final int NOTIFICATION_CONTENT = 255;

    private TextLimits() {
    }

    /** null 原样返回；超长按 maxLength 截断。 */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
