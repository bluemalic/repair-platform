package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.KnowledgeAdviceVO;

/**
 * 师傅端的「排查建议」：检索本租户知识库 → 一次生成 → **核对引用**（ADR-018）。
 *
 * <p>边界与两个 Agent 一致：**不写库**。建议只是建议，怎么做仍是师傅的判断。
 */
public interface KnowledgeAdviceService {

    /**
     * 给一张工单出排查建议。
     *
     * <p>工单由数据权限拦截器把关（`ticket` 在覆盖名单里）：**看不到这张单的人拿不到建议**，
     * 与工单详情的对外口径一致（查不到就是"不存在"）。
     */
    KnowledgeAdviceVO advice(long ticketId);
}
