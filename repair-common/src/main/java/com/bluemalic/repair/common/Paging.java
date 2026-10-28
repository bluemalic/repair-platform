package com.bluemalic.repair.common;

/**
 * 分页参数的归一化。
 *
 * <p>这段逻辑之前在**四个** Service 里各复制了一份（工单 / 维修工 / 报修码 / 通知），
 * 而且每个副本旁边都写着同一句话："等第三处出现再抽公共类"——实际早就超过三处了。
 *
 * <p>不抽的代价不是"多几行"，而是将来要调上限（比如开放到 200）时得改四个地方，
 * 漏一个就变成"有的接口能传 200、有的不行"这种说不清的行为差异；而调用方看不出
 * 自己的接口属于哪一类，只能去读每个 Service 的实现。
 *
 * <p>上限 100 是按列表接口定的：管理端的表格一页最多显示几十行，再大只是给数据库添负担。
 * 它同时防住"前端传 pageSize=100000 把整库拉出来"这种情况。
 */
public final class Paging {

    /** 每页条数上限。 */
    public static final long MAX_PAGE_SIZE = 100;

    private Paging() {
    }

    /** 把前端传来的分页参数拉回合法区间：小于 1 视为 1，超过上限的封顶。 */
    public static long clamp(long value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, MAX_PAGE_SIZE);
    }
}
