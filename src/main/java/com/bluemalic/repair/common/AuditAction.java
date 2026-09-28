package com.bluemalic.repair.common;

import java.util.Map;

/**
 * 审计动作码（`docs/01` §4.4）。
 *
 * <p><b>为什么用字符串码而不是 tinyint 枚举</b>（与 {@code TicketStatus} 的做法相反）：
 * 状态是**封闭集合**（8 个，不会再长），而审计动作是**开放集合**——每加一个可审计的写操作就多一个，
 * 而且历史记录必须永远能读。用一个常量的中文名映射表就够了，加动作不用动状态机那种强约束。
 *
 * <p>码一旦写进库就不再改：审计记录是只读历史，改码等于让老记录失去含义。
 */
public final class AuditAction {

    // ---------- 账号 ----------
    public static final String WORKER_CREATE = "WORKER_CREATE";
    public static final String WORKER_UPDATE = "WORKER_UPDATE";
    public static final String WORKER_BUILDINGS = "WORKER_BUILDINGS";
    public static final String STUDENT_CREATE = "STUDENT_CREATE";
    public static final String STUDENT_UPDATE = "STUDENT_UPDATE";
    public static final String STUDENT_IMPORT = "STUDENT_IMPORT";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PASSWORD_CHANGE_SELF = "PASSWORD_CHANGE_SELF";

    // ---------- 基础数据 ----------
    public static final String BUILDING_CREATE = "BUILDING_CREATE";
    public static final String BUILDING_UPDATE = "BUILDING_UPDATE";
    public static final String BUILDING_DELETE = "BUILDING_DELETE";
    public static final String CATEGORY_CREATE = "CATEGORY_CREATE";
    public static final String CATEGORY_UPDATE = "CATEGORY_UPDATE";
    public static final String CATEGORY_DELETE = "CATEGORY_DELETE";
    public static final String REPAIR_CODE_CREATE = "REPAIR_CODE_CREATE";
    public static final String REPAIR_CODE_UPDATE = "REPAIR_CODE_UPDATE";

    // ---------- 平台运营 ----------
    public static final String TENANT_PROVISION = "TENANT_PROVISION";
    public static final String TENANT_UPDATE = "TENANT_UPDATE";
    public static final String TENANT_STATUS = "TENANT_STATUS";
    public static final String TENANT_ADMIN_ADD = "TENANT_ADMIN_ADD";
    public static final String TENANT_ADMIN_PASSWORD_RESET = "TENANT_ADMIN_PASSWORD_RESET";

    /** 动作码 → 中文名。**只用于展示**，不参与任何判断（判断一律用上面的码）。 */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(WORKER_CREATE, "新增维修工"),
            Map.entry(WORKER_UPDATE, "修改维修工"),
            Map.entry(WORKER_BUILDINGS, "设置负责楼栋"),
            Map.entry(STUDENT_CREATE, "新增学生"),
            Map.entry(STUDENT_UPDATE, "修改学生"),
            Map.entry(STUDENT_IMPORT, "批量导入学生"),
            Map.entry(PASSWORD_RESET, "重置口令"),
            Map.entry(PASSWORD_CHANGE_SELF, "本人修改口令"),
            Map.entry(BUILDING_CREATE, "新增楼栋"),
            Map.entry(BUILDING_UPDATE, "修改楼栋"),
            Map.entry(BUILDING_DELETE, "删除楼栋"),
            Map.entry(CATEGORY_CREATE, "新增报修类别"),
            Map.entry(CATEGORY_UPDATE, "修改报修类别"),
            Map.entry(CATEGORY_DELETE, "删除报修类别"),
            Map.entry(REPAIR_CODE_CREATE, "新增报修码"),
            Map.entry(REPAIR_CODE_UPDATE, "修改报修码"),
            Map.entry(TENANT_PROVISION, "开通学校"),
            Map.entry(TENANT_UPDATE, "修改学校"),
            Map.entry(TENANT_STATUS, "启停学校"),
            Map.entry(TENANT_ADMIN_ADD, "新增学校管理员"),
            Map.entry(TENANT_ADMIN_PASSWORD_RESET, "重置学校管理员口令"));

    private AuditAction() {
    }

    /** 中文名；认不出的码回落成码本身（老记录不会因为新代码而显示成空白）。 */
    public static String label(String action) {
        return LABELS.getOrDefault(action, action);
    }
}
