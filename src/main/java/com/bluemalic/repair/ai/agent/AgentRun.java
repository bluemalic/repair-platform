package com.bluemalic.repair.ai.agent;

import java.util.List;

/**
 * 一次编排运行的结果。
 *
 * @param reply            模型最后说的话（步数用尽时可能是空的——调用方据此追问）
 * @param trace            走过的每一步（见 {@link AgentTraceStep}）
 * @param stepLimitReached 是否因为**步数上限**而收尾。这不是错误：模型在有限的步数里没凑齐信息，
 *                         正常做法是把它已有的部分交回、让用户补一句话，而不是报错
 */
public record AgentRun(String reply, List<AgentTraceStep> trace, boolean stepLimitReached) {

    public AgentRun {
        reply = reply == null ? "" : reply;
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
