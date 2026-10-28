package com.bluemalic.repair.ai.agent;

import java.util.List;

/**
 * 受理会话的状态：**已收集的草稿 + 文本轮次**。
 *
 * <p>刻意只存"文本轮次"（用户说了什么、助手答了什么），**不存工具调用与结果**：
 * 上一轮查过的楼栋清单对下一轮判断没有帮助，而它会占掉大量 token（每轮都要重新发一遍）。
 * 需要的时候模型会再调一次工具——那比把历史灌大便宜。
 *
 * @param draft 已收集到的字段（可能部分为空）
 */
public record AgentSession(String sessionId, List<Turn> history, Draft draft) {

    /** 一轮对话。{@code role} 只有 {@code user} 与 {@code assistant} 两种。 */
    public record Turn(String role, String text) {
    }

    /**
     * 工单草稿——**字段与 {@code TicketCreateDTO} 严格对齐**（categoryId / urgency / buildingId /
     * room / description），这样前端把草稿原样交给提交接口即可，不需要"翻译"一层。
     * 提交接口没有 title，所以这里也没有。
     */
    public record Draft(Long categoryId, Integer urgency, Long buildingId, String room, String description) {

        public static Draft empty() {
            return new Draft(null, null, null, null, null);
        }

        /**
         * 用模型这一轮的结果**补空**（而不是覆盖）：模型偶尔会在后一轮漏掉前一轮已确认的字段，
         * 覆盖会把已经问清楚的信息又抹掉，表现为"答过的内容它还在问"。
         */
        public Draft merge(Draft incoming) {
            if (incoming == null) {
                return this;
            }
            return new Draft(
                    incoming.categoryId() != null ? incoming.categoryId() : categoryId,
                    incoming.urgency() != null ? incoming.urgency() : urgency,
                    incoming.buildingId() != null ? incoming.buildingId() : buildingId,
                    hasText(incoming.room) ? incoming.room : room,
                    hasText(incoming.description) ? incoming.description : description);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
