package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.AuditLogVO;
import com.bluemalic.repair.vo.PageResult;

import java.time.LocalDate;

/**
 * 操作审计（`docs/01` §4.4）：**账号与基础数据的写操作留痕**。
 *
 * <p>工单流转不在这里（它有 {@code ticket_log}）；读操作也不在（那是访问日志）。
 *
 * <p><b>调用方式是显式的</b>：在各 service 的写方法里调 {@link #record}，不用 AOP 注解——
 * 审计的价值全在"目标是谁、改了什么"这些语义上，切面只能记到"调了哪个接口"，价值有限；
 * 而且显式调用读代码时一眼能看出哪些操作被审计了（清单见 `docs/01` §4.4）。
 * 代价是漏记要靠测试兜住（`AuditLogTest` 逐个动作断言）。
 */
public interface AuditService {

    /**
     * 记一条审计。**在业务事务里调用**（不新开事务）：业务回滚时审计也跟着回滚——
     * "没有记录 = 没有发生"。反过来，审计写不进去会让这次业务操作失败，
     * 这是刻意的：宁可操作失败，也不要"操作成功了但查不到谁干的"。
     *
     * @param action     动作码，见 {@link com.bluemalic.repair.common.AuditAction}
     * @param targetType 目标类型：WORKER / STUDENT / BUILDING / CATEGORY / REPAIR_CODE / TENANT / ACCOUNT
     * @param targetId   目标 ID；批量导入这类没有单一目标时传 null
     * @param targetName 目标名称快照（工号 / 楼栋名 / 学校名…）
     * @param detail     一句人话摘要，**不要带敏感字段原值**（手机号只说"已更新"）
     */
    void record(String action, String targetType, Long targetId, String targetName, String detail);

    /**
     * 审计日志分页（本租户，时间倒序）。
     *
     * @param action          动作码精确筛选，null = 全部
     * @param operatorKeyword 操作人姓名/账号模糊筛选
     * @param startDate       起始日期（含），null = 不限
     * @param endDate         结束日期（含），null = 不限
     */
    PageResult<AuditLogVO> page(long pageNum, long pageSize, String action, String operatorKeyword,
                                LocalDate startDate, LocalDate endDate);
}
