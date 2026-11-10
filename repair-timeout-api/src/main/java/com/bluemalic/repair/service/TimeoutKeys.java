package com.bluemalic.repair.service;

/**
 * 超时队列的键与成员格式——**唯一一份**（阶段 3.4）。
 *
 * <p>为什么要收在一起：登记侧（{@code TimeoutRegistrarImpl}）写 key/member，
 * 扫描侧（{@code TimeoutScannerImpl}）读 key/member，两边各写一份必然漂开；
 * 漂开的后果不是报错，是"登记了但永远不消费"——这种问题在日志里看不出来。
 *
 * <p>另外 {@code TicketEvaluationMapper.xml} 里也有一处成员格式（超期验收单的成员拼接），
 * 改格式时要把那处 SQL 一起改，否则兜底扫描会捞不到。
 */
public final class TimeoutKeys {

    /** ZSet key 前缀：{@code ticket:timeout:{节点}}。 */
    public static final String KEY_PREFIX = "ticket:timeout:";

    /** ZSet 成员里"租户 | 工单"的分隔符。 */
    public static final String MEMBER_SEPARATOR = "|";

    public static final String NODE_ACCEPT = "ACCEPT";

    public static final String NODE_PROCESS = "PROCESS";

    public static final String NODE_EVAL = "EVAL";

    private TimeoutKeys() {
    }

    public static String key(String node) {
        return KEY_PREFIX + node;
    }

    public static String member(long tenantId, long ticketId) {
        return tenantId + MEMBER_SEPARATOR + ticketId;
    }
}
