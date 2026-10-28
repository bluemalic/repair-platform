package com.bluemalic.repair.vo;

import lombok.Data;

import java.util.List;

/**
 * 智能分诊建议。**只给建议，不写库**（ADR-017）：后勤看着理由决定是否采纳。
 */
@Data
public class TriageSuggestionVO {

    private Long suggestedCategoryId;

    private String suggestedCategoryName;

    /** 1 普通 / 2 紧急 / 3 特急。 */
    private Integer suggestedUrgency;

    /** 一句话理由——后勤看的就是它，决定要不要采纳。 */
    private String reason;

    private List<AgentTraceVO> trace;
}
