package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志行（`docs/01` §4.4）。
 *
 * <p>{@code operatorName} / {@code targetName} 是**发生当时的名字快照**——账号改名、楼栋被删之后，
 * 这条记录仍然读得懂。
 */
@Data
@Schema(description = "操作审计日志")
public class AuditLogVO {

    private Long id;

    private Long operatorId;

    @Schema(description = "操作人姓名（当时的快照）")
    private String operatorName;

    @Schema(description = "动作码，如 WORKER_CREATE")
    private String action;

    @Schema(description = "动作中文名（服务端拼好，前端不必维护字典）")
    private String actionLabel;

    @Schema(description = "目标类型：WORKER / STUDENT / BUILDING / CATEGORY / REPAIR_CODE / TENANT / ACCOUNT")
    private String targetType;

    private Long targetId;

    @Schema(description = "目标名称（当时的快照）")
    private String targetName;

    @Schema(description = "一句人话摘要（不含敏感字段原值）")
    private String detail;

    @Schema(description = "来源 IP")
    private String ip;

    private LocalDateTime createTime;
}
