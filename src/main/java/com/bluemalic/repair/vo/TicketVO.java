package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单列表项 / 通用工单返回体。 */
@Data
@Schema(description = "工单信息")
public class TicketVO {

    @Schema(description = "工单ID")
    private Long id;

    private String ticketNo;

    @Schema(description = "状态：10待派单 20待接单 30处理中 40待验收 50已完成 60已关闭 70已撤单 80已驳回")
    private Integer status;

    @Schema(description = "紧急度 1普通 2紧急 3特急")
    private Integer urgency;

    private Long buildingId;

    @Schema(description = "楼栋名称")
    private String buildingName;

    private String room;

    private Long categoryId;

    @Schema(description = "类别名称")
    private String categoryName;

    private Long studentId;

    private Long workerId;

    private LocalDateTime submitTime;

    private LocalDateTime dispatchTime;

    private LocalDateTime finishTime;

    private Integer arriveMinutes;

    private Integer handleMinutes;

    /**
     * **当前登录人是不是这单的协作者**（`docs/01` §4.5）。
     *
     * <p>只在师傅端「我的任务」里填（那里才需要把"派给我的"与"我协作的"分开显示），
     * 其它列表为 {@code null}——没填的地方别把它当 {@code false} 用。
     *
     * <p>为什么不让前端拿 {@code workerId} 与登录用户自己比：两端的 Long 都序列化成字符串，
     * 类型对不上时这个比较会**静默给出错误结果**——标记不显示不会报错，只会让人以为功能没做。
     */
    @Schema(description = "当前登录人是不是协作者（仅师傅端任务列表填；其它列表为 null）")
    private Boolean collaborative;
}
