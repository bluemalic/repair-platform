package com.bluemalic.repair.vo;

import lombok.Data;

import java.util.List;

/**
 * 报修受理 Agent 的一轮响应。
 *
 * <p><b>它不产生任何状态变更</b>（ADR-017）：{@code draft} 只是建议值，用户在前端确认后，
 * 仍由前端调 `POST /api/student/tickets` 落单——那条链上的数据权限、租户校验、审计、限流一个都不少。
 */
@Data
public class AgentIntakeVO {

    /** 下一轮带上它；不传时由服务端新建。 */
    private String sessionId;

    /** 给用户看的一句话：追问，或"信息齐了，请确认"。 */
    private String reply;

    /**
     * 草稿是否已完整到可以落单。
     * **由服务端算**（不信模型自己报的 missing）：缺什么是要害判断，不能交给模型自述。
     */
    private boolean ready;

    /** 还缺哪些字段，取值 {@code categoryId} / {@code description} / {@code location}，供前端高亮。 */
    private List<String> missing;

    /** 信息够时给出；字段与提交接口的入参一一对应，前端可以原样提交。 */
    private Draft draft;

    private List<AgentTraceVO> trace;

    /**
     * 工单草稿。**字段与 {@code TicketCreateDTO} 严格对齐**（多了 id 之外的展示名字段，方便前端直接显示）。
     */
    @Data
    public static class Draft {

        private Long categoryId;

        /** 类别名，仅用于展示（前端不必再查一次字典）。 */
        private String categoryName;

        private Long buildingId;

        /** 楼栋名，仅用于展示。 */
        private String buildingName;

        private String room;

        /** 1 普通 / 2 紧急 / 3 特急。 */
        private Integer urgency;

        private String description;

        /** 位置来自扫码时原样带回；提交时带上它，位置就不会丢。 */
        private String repairCode;
    }
}
