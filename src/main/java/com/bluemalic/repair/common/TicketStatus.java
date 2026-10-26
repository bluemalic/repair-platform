package com.bluemalic.repair.common;

import java.util.Map;
import java.util.Set;

/** 工单状态机（与 docs/02-数据库设计.md §5 一致）。 */
public enum TicketStatus {

    TO_DISPATCH(10, "待派单"),
    TO_ACCEPT(20, "待接单"),
    PROCESSING(30, "处理中"),
    /** 师傅在处理中发现缺材料：单还在他手上，材料到了自己恢复（docs/01 §4.7） */
    WAITING_MATERIAL(35, "待料"),
    TO_VERIFY(40, "待验收"),
    /** 师傅判定修不了：等后勤决定重新派单 / 退回继续修 / 关闭（docs/01 §4.7） */
    UNREPAIRABLE(45, "无法维修"),
    FINISHED(50, "已完成"),
    CLOSED(60, "已关闭"),
    CANCELED(70, "已撤单"),
    /** 重复报修被合并进另一张单：终态，只保留指过去的 merged_into_id（docs/01 §4.7） */
    MERGED(75, "已合并"),
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

    /**
     * 合法跃迁表：key = 当前状态，value = 允许到达的状态集合。
     *
     * <p>新增 35 / 45 / 75 的规则见 docs/01 §4.7；几个容易看错的点：
     * <ul>
     *   <li>20 → 20 / 30 → 20 是**转派**（换个师傅但单不退），不是打回待派单；</li>
     *   <li>40 → 30 是**验收不通过**（保留 worker_id，同一师傅返工）；</li>
     *   <li>35 → 30 是待料后恢复，45 → 20/30/60 是后勤对"无法维修"的三种处置；</li>
     *   <li>任何活动状态 → 75 是**合并**，终态；75 不再流转。</li>
     * </ul>
     */
    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            TO_DISPATCH.code, Set.of(TO_ACCEPT.code, CANCELED.code, MERGED.code),
            // 已驳回的工单由后勤重新派单，回到待接单（见 docs/02 §5）；也可以直接关闭/撤单/合并
            REJECTED.code, Set.of(TO_ACCEPT.code, CLOSED.code, CANCELED.code, MERGED.code),
            // 20 → 20 是转派；20 → 70 是学生派单后撤单
            TO_ACCEPT.code, Set.of(PROCESSING.code, REJECTED.code, TO_ACCEPT.code, CANCELED.code, MERGED.code),
            // 30 → 35 待料、30 → 45 无法维修；30 → 70 是学生处理中撤单
            PROCESSING.code, Set.of(TO_VERIFY.code, REJECTED.code, TO_ACCEPT.code,
                    WAITING_MATERIAL.code, UNREPAIRABLE.code, CANCELED.code, MERGED.code),
            WAITING_MATERIAL.code, Set.of(PROCESSING.code, CANCELED.code, MERGED.code),
            // 40 → 30 是"验收不通过、打回重做"：与 40 → 80（驳回，退调度池）不同，它保留 worker_id
            TO_VERIFY.code, Set.of(FINISHED.code, REJECTED.code, PROCESSING.code, MERGED.code),
            // 45 的三种处置：重新派单（20）、退回继续修（30）、后勤关闭（60）；学生也可以撤单
            UNREPAIRABLE.code, Set.of(TO_ACCEPT.code, PROCESSING.code, CLOSED.code, CANCELED.code, MERGED.code),
            FINISHED.code, Set.of(CLOSED.code));

    /** 非法跃迁抛 20002，由全局处理器转成统一返回体。 */
    public static void checkTransition(int from, int to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    of(from).desc + "状态不允许" + (to == REJECTED.code ? "驳回" : "该操作"));
        }
    }
}
