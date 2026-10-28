package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维修知识库条目（阶段 2 · ADR-018）：师傅端"排查建议"的原料。
 *
 * <p>检索主字段是 {@link #symptom}（写用户会说的那种话），{@link #tags} 用来补同义词——
 * 关键词检索的同义词召回全靠它。业务上**用 {@link #status} 停用、不用 {@link #deleted}**：
 * 条目会被历史建议引用，删掉就断了线索。
 */
@Data
@TableName("knowledge_entry")
public class KnowledgeEntry {

    private Long id;

    private Long tenantId;

    /** 挂到报修类别；null = 通用条目（任何类别都可能命中） */
    private Long categoryId;

    private String title;

    /** 现象 / 适用场景——检索主字段 */
    private String symptom;

    /** 处理步骤，一行一步 */
    private String solution;

    /** 关键词（空格分隔），用来补同义词 */
    private String tags;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private Integer sort;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
