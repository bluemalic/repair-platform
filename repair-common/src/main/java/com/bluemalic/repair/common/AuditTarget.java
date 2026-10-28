package com.bluemalic.repair.common;

import java.util.Map;

/**
 * 审计记录里的"目标类型"（`docs/01` §4.4）。与 {@link AuditAction} 一样用字符串码：
 * 目标类型会随功能增加，而历史记录必须永远读得懂（认不出的码原样展示）。
 */
public final class AuditTarget {

    public static final String WORKER = "WORKER";
    public static final String STUDENT = "STUDENT";
    public static final String BUILDING = "BUILDING";
    public static final String CATEGORY = "CATEGORY";
    public static final String REPAIR_CODE = "REPAIR_CODE";
    public static final String TENANT = "TENANT";
    /** 账号级别的操作（重置口令、停用），目标就是那个账号 */
    public static final String ACCOUNT = "ACCOUNT";

    private static final Map<String, String> LABELS = Map.of(
            WORKER, "维修工",
            STUDENT, "学生",
            BUILDING, "楼栋",
            CATEGORY, "报修类别",
            REPAIR_CODE, "报修码",
            TENANT, "学校",
            ACCOUNT, "账号");

    private AuditTarget() {
    }

    /** 中文名；认不出的码回落成码本身。 */
    public static String label(String targetType) {
        return LABELS.getOrDefault(targetType, targetType);
    }
}
