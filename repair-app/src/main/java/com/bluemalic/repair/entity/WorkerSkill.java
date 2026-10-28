package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维修工擅长的报修类别（阶段 3 · ADR-019）。
 *
 * <p>它是派单评分的"技能"输入。形态与 {@link WorkerBuilding} 一致：纯关联表，
 * 取消一个技能就是删掉这行，不设 `deleted`——留痕在日志里，不在这张表里。
 */
@Data
@TableName("worker_skill")
public class WorkerSkill {

    private Long id;

    private Long tenantId;

    private Long workerId;

    private Long categoryId;

    private LocalDateTime createTime;
}
