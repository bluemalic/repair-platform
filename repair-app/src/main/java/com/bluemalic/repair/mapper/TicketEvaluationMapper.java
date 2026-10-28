package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.TicketEvaluation;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TicketEvaluationMapper extends BaseMapper<TicketEvaluation> {

    /**
     * 兜底扫描用：找出"评价已超期、且工单仍停在 50 已完成"的工单，返回 **`租户ID|工单ID`** 成员。
     *
     * <p>必须 JOIN ticket 限定 `status = 50`：否则已关闭/已撤单的工单会被每分钟重复扫到，
     * 结果集只增不减（每分钟 N 次无效查询 + N 行日志）。LIMIT 是单轮上限。
     *
     * <p>为什么返回成员字符串而不是工单 ID（阶段 6 第 3 步 · ADR-021）：定时任务没有登录态，
     * 消费时要知道去哪个库处理这条任务。成员格式与 Redis ZSet 的成员同形，由
     * {@code TimeoutServiceImpl} 统一解析（两处约定，改一边要改另一边）。
     * SQL 见 `resources/mapper/TicketEvaluationMapper.xml`。
     */
    List<String> selectOverdueOpenTicketMembers(@Param("cutoff") LocalDateTime cutoff,
                                                @Param("limit") int limit);
}
