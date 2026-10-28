package com.bluemalic.repair.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 知识库条目（管理端列表用）。 */
@Data
public class KnowledgeEntryVO {

    private Long id;

    private String title;

    private String symptom;

    private String solution;

    private Long categoryId;

    /** 类别名，仅用于展示（前端不必再查一次字典） */
    private String categoryName;

    private String tags;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private LocalDateTime updateTime;
}
