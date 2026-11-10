package com.bluemalic.repair.vo;

import lombok.Data;

import java.util.List;

/**
 * 派单候选（阶段 3 · ADR-019）。
 *
 * <p><b>它的重点不是总分，而是明细</b>：五项得分、权重、一句话说明都随响应返回，
 * 后勤看到的是"为什么排第一"，而不是一个黑盒数字。这也是这个功能能讲清楚的前提——
 * 评分模型的价值在于**可解释**，不在于把人的判断换掉（派单动作仍然由人点确认，见 `docs/01` 的"明确不做"）。
 */
@Data
public class DispatchCandidateVO {

    private Long workerId;

    /** 工号 */
    private String username;

    private String workerName;

    /** 负责楼栋（展示用） */
    private List<String> buildingNames;

    /** 擅长类别（展示用） */
    private List<String> skillCategoryNames;

    /** 是否负责工单所在楼栋：前端据此决定要不要弹「跨楼栋强制派单」确认 */
    private boolean responsibleForBuilding;

    /** 当前进行中工单数（20/30/40） */
    private int activeCount;

    /** 历史平均评分（1~5）；无评价为 null */
    private Double avgScore;

    /** 今天是否在班：true 在班 / false 明确请假或休息 / null 今天没有排班记录（按中性分） */
    private Boolean onDutyToday;

    /** 加权总分（0~1，已按权重之和归一化） */
    private double score;

    /** 五项明细：前端直接照着渲染，不必自己再算 */
    private List<ScoreItem> items;

    /** 一句话理由：由五项明细拼成，方便列表里一眼看懂 */
    private String reason;

    @Data
    public static class ScoreItem {

        /** skill / satisfaction / load / distance / shift */
        private String key;

        /** 给人看的中文名 */
        private String label;

        /** 这一项的得分（0~1） */
        private double score;

        /** 这一项的权重（归一化之前的值，与配置一致） */
        private double weight;

        /** 人话说明：如「擅长水电」「同区」「当前 2 单」「暂无评价」 */
        private String detail;
    }
}
