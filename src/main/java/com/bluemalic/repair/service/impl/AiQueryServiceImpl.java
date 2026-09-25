package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.ai.AiAssistant;
import com.bluemalic.repair.ai.AiQueryExecutor;
import com.bluemalic.repair.ai.AiQueryResult;
import com.bluemalic.repair.ai.QueryPlan;
import com.bluemalic.repair.ai.SqlSafetyGateway;
import com.bluemalic.repair.ai.SqlSafetyResult;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.service.AiQueryService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.vo.AiChartVO;
import com.bluemalic.repair.vo.AiQueryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 问数的编排：**生成 → 网关校验 → 只读执行 → 出结论**。
 *
 * <p>每一步失败都有明确的错误码（见 docs/03 §5.6 与错误码表）：
 * 模型答不上来 {@code 40001}、SQL 没过闸门 {@code 40002}、查询超时 {@code 40003}、
 * 模型不可用 {@code 40004}。所以这个方法里**没有 try-catch**——异常交给
 * {@code GlobalExceptionHandler}，只有"结论"那一步是例外（见下）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQueryServiceImpl implements AiQueryService {

    private final AiAssistant assistant;

    private final SqlSafetyGateway sqlSafetyGateway;

    private final AiQueryExecutor aiQueryExecutor;

    /** 租户从登录态取，不是从请求参数取——否则就等于"谁都能问别家学校的数据"。 */
    private final CurrentTenantService currentTenantService;

    @Override
    public AiQueryVO ask(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "问题不能为空");
        }
        long startedAt = System.currentTimeMillis();
        long tenantId = currentTenantService.requireTenantId();

        QueryPlan plan = assistant.plan(question);
        if (!StringUtils.hasText(plan.sql())) {
            // "模型没给出 SQL" 属于"问题没能解析成查询"（40001），不是"没通过安全校验"（40002）。
            // 在这一层再判一次，是为了让本服务的错误码**不依赖 assistant 实现的严谨程度**：
            // 换个实现（或假实现）返回空 SQL 时，调用方拿到的仍然是同一个码。
            throw new BizException(ErrorCode.AI_INTENT_UNRESOLVED);
        }
        // 模型输出从这里开始被当作不可信输入：网关决定它能不能跑、跑的时候能不能看到别人的数据
        SqlSafetyResult scoped = sqlSafetyGateway.validateAndScope(plan.sql(), tenantId);
        AiQueryResult data = aiQueryExecutor.execute(scoped.sql());
        String conclusion = summarizeQuietly(question, data);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        // 记 SQL 与耗时（排障与评测都要），不记查询结果内容——那是库里的业务数据
        log.info("AI 问数 tenantId={} 表={} 行数={} 截断={} 耗时={}ms sql={}",
                tenantId, scoped.tables(), data.rows().size(), data.rowLimited(), elapsedMs, scoped.sql());

        return buildVo(question, scoped.sql(), plan, data, conclusion, elapsedMs);
    }

    /**
     * 出结论——**它失败不影响整次问数**。
     *
     * <p>到这一步表格与图已经在手上了，那才是用户要的主体；为了一句文案把整次请求判失败，
     * 是把"锦上添花"当成了"必要路径"（与"通知发送失败不阻塞工单流转"同一取舍）。
     * 降级成一句由数据算得出来的话，比报错有用。
     */
    private String summarizeQuietly(String question, AiQueryResult data) {
        try {
            return assistant.summarize(question, data);
        } catch (Exception e) {
            log.warn("生成结论文案失败，退化为按行数陈述 err={}", e.getMessage());
            return data.rows().isEmpty()
                    ? "这次查询没有数据。"
                    : "共 " + data.rows().size() + " 行结果。";
        }
    }

    private AiQueryVO buildVo(String question, String sql, QueryPlan plan, AiQueryResult data,
                              String conclusion, long elapsedMs) {
        AiChartVO chart = new AiChartVO();
        chart.setType(StringUtils.hasText(plan.chartType()) ? plan.chartType() : "none");
        chart.setX(plan.xColumn());
        chart.setY(plan.yColumn());

        AiQueryVO vo = new AiQueryVO();
        vo.setQuestion(question);
        vo.setSql(sql);
        vo.setColumns(data.columns());
        vo.setRows(data.rows());
        vo.setChart(chart);
        vo.setConclusion(conclusion);
        vo.setRowLimited(data.rowLimited());
        vo.setElapsedMs(elapsedMs);
        return vo;
    }
}
