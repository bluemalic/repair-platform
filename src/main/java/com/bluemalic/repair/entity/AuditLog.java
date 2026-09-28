package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志（`docs/01` §4.4）：**账号与基础数据的写操作**留痕。
 *
 * <p>与 {@link TicketLog} 一样是**追加型表**：只有 {@code createTime}，写入后不修改、不删除
 * （所以这张表没有 {@code updateTime} / {@code deleted}——能改的记录不是审计记录）。
 *
 * <p>工单流转**不记在这里**：{@code ticket_log} 比它更细（from/to 状态 + 动作），重复记两份
 * 等于两份可能不一致的真相。
 *
 * <p>{@code operatorName} / {@code targetName} 是**当时的名字快照**：账号改名、楼栋被删之后，
 * 光看 id 什么也读不出来——审计表是只读的历史，不能跟着主数据一起变。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    private Long id;

    /** 租户；平台运营自己的操作是 0 */
    private Long tenantId;

    private Long operatorId;

    /** 操作人姓名快照 */
    private String operatorName;

    /** 动作码，见 {@link com.bluemalic.repair.common.AuditAction} */
    private String action;

    /** 目标类型：WORKER / STUDENT / BUILDING / CATEGORY / REPAIR_CODE / TENANT / ACCOUNT */
    private String targetType;

    /** 批量导入这类没有单一目标时为 null */
    private Long targetId;

    /** 目标名称快照：工号 / 楼栋名 / 学校名… */
    private String targetName;

    /** 一句人话摘要（**不含敏感字段原值**，如手机号只说"已更新"） */
    private String detail;

    /** 来源 IP（IPv6 最长 45） */
    private String ip;

    private LocalDateTime createTime;
}
