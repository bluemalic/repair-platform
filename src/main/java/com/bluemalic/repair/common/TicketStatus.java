package com.bluemalic.repair.common;

import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * 工单状态机（与 docs/02-数据库设计.md §5 一致）。
 *
 * <p>所有状态流转必须经 {@link #checkTransition} 校验——散落在各处的 if(status==…) 会随需求失控，
 * 把"合法跃迁"收敛到这一处，非法跃迁统一拒绝并记日志。
 */
public enum TicketStatus {

    TO_DISPATCH(10, "待派单"),
    TO_ACCEPT(20, "待接单"),
    PROCESSING(30, "处理中"),
    TO_VERIFY(40, "待验收"),
    FINISHED(50, "已完成"),
    CLOSED(60, "已关闭"),
    CANCELED(70, "已撤单"),
    REJECTED(80, "已驳回");

    private final int code;
    private final String desc;

    TicketStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TicketStatus of(int code) {
        for (TicketStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知工单状态: " + code);
    }

    /** 合法跃迁表：key = 当前状态，value = 允许到达的状态集合。 */
    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            TO_DISPATCH.code, Set.of(TO_ACCEPT.code, CANCELED.code),
            // 已驳回的工单由后勤重新派单，回到待接单（见 docs/02 §5）
            REJECTED.code, Set.of(TO_ACCEPT.code),
            TO_ACCEPT.code, Set.of(PROCESSING.code, REJECTED.code),
            PROCESSING.code, Set.of(TO_VERIFY.code, REJECTED.code),
            TO_VERIFY.code, Set.of(FINISHED.code, REJECTED.code),
            FINISHED.code, Set.of(CLOSED.code));

    /** 非法跃迁抛 20002，由全局处理器转成统一返回体。 */
    public static void checkTransition(int from, int to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    of(from).desc + "状态不允许" + (to == REJECTED.code ? "驳回" : "该操作"));
        }
    }
}
