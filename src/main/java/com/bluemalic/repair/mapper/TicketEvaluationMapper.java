package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.TicketEvaluation;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TicketEvaluationMapper extends BaseMapper<TicketEvaluation> {

    /**
     * 兜底扫描用：找出"评价已超期、且工单仍停在 50 已完成"的工单 ID。
     *
     * <p>必须 JOIN ticket 限定 `status = 50`：否则已关闭/已撤单的工单会被每分钟重复扫到，
     * 结果集只增不减（每分钟 N 次无效查询 + N 行日志）。LIMIT 是单轮上限。
     * SQL 见 `resources/mapper/TicketEvaluationMapper.xml`。
     */
    List<Long> selectOverdueOpenTicketIds(@Param("cutoff") LocalDateTime cutoff,
                                         @Param("limit") int limit);
}
