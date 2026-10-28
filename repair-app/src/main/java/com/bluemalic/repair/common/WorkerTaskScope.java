package com.bluemalic.repair.common;

/**
 * 维修工任务列表的视图（`docs/01` §4.2）。**它只是业务筛选，不是权限范围**——
 * 真正的可见范围由数据权限拦截器给（负责楼栋 或 派给自己的单），页面越不过它。
 *
 * <ul>
 *   <li>{@link #MINE}（默认）：派给我的单，回答"我现在该干什么"</li>
 *   <li>{@link #BUILDING}：我负责楼栋里的全部工单（含终态、含别人负责的），
 *       回答"这栋楼在修什么"</li>
 * </ul>
 */
public enum WorkerTaskScope {

    MINE,
    BUILDING;

    /**
     * 解析请求里的 {@code scope}。**认不出的值直接报参数错误**，不悄悄回落到默认视图
     * ——静默回落会让"我明明传了 building 却看到我的任务"变成一个说不清的现象
     * （同 {@code StatisticsServiceImpl} 对 dimension 的处理）。
     */
    public static WorkerTaskScope of(String value) {
        if (value == null || value.isBlank()) {
            return MINE;
        }
        for (WorkerTaskScope scope : values()) {
            if (scope.name().equalsIgnoreCase(value)) {
                return scope;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "scope 只支持 mine / building");
    }
}
