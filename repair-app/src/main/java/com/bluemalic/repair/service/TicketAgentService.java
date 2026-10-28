package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.AgentIntakeDTO;
import com.bluemalic.repair.vo.AgentIntakeVO;
import com.bluemalic.repair.vo.TriageSuggestionVO;

/**
 * AI Agent 的两个场景（ADR-017）。**两个方法都不写库**：受理只产工单草稿、分诊只给建议，
 * 状态变更仍由用户确认后走原有接口。
 */
public interface TicketAgentService {

    /** 报修受理：一轮对话 → 追问 或 工单草稿。 */
    AgentIntakeVO intake(AgentIntakeDTO dto);

    /** 智能分诊：对一张已存在的工单给出类别 / 紧急度建议与理由。 */
    TriageSuggestionVO triage(long ticketId);
}
