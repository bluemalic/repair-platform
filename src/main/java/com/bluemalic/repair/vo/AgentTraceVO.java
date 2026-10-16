package com.bluemalic.repair.vo;

import lombok.Data;

/**
 * Agent 轨迹里的一步：调了哪个工具、花了多久、拿到什么（摘要）。
 *
 * <p>返回给前端是为了**让"它真的在调工具"看得见**——演示与排查都靠它；用户原文与完整结果不在这里。
 */
@Data
public class AgentTraceVO {

    private String tool;

    private long ms;

    private String summary;

    /** 该工具不在白名单里、被循环拒绝执行。 */
    private boolean rejected;
}
