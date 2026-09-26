package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.NumericLongSerializer;
import com.bluemalic.repair.common.TraceIdFilter;
import com.bluemalic.repair.service.AiQueryProgress;
import com.bluemalic.repair.service.AiQueryService;
import com.bluemalic.repair.vo.AiChartVO;
import com.bluemalic.repair.vo.AiQueryVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 把一次问数变成一条 SSE 流（{@code GET /api/ai/query/stream}，契约见 docs/03 §5.6）。
 *
 * <p><b>流的是"阶段"，不是"字"</b>——这一点是有意选的，别照着"SSE 就该逐字吐 token"去改：
 * <ul>
 *   <li>用户真正难熬的是前十几秒的空白：一次问数要等两次模型调用。把"SQL 已定下来""数据已取到"
 *       这两段提前推出去，用户就能先看到"它打算怎么查"和表格 / 图，而不是等一个转圈；
 *   <li>结论只有一两句话（40 来个字），为它引入 {@code StreamingChatModel} 是**为 40 个字符**
 *       多一条模型调用路径（另一套提示词入口、另一套异常、另一套假实现），收益近乎为零；
 *   <li>ADR-003 那四层闸门是在"拿到完整 SQL"之后才生效的——逐字吐出未校验的 SQL，
 *       等于把没过闸门的东西摆到用户面前。
 * </ul>
 *
 * <p><b>为什么要独立线程池</b>：Controller 返回 {@code SseEmitter} 之后请求线程就释放了，
 * 而这次工作要等模型十几秒——不能占着 Tomcat 的请求线程（池大小与理由见 {@code AiStreamConfig}）。
 * 线程池满时**立刻回一个错误帧**，而不是把请求堆在队列里等到超时：那时用户至少知道"系统忙"。
 *
 * <p>租户与登录态：这个方法不碰 Sa-Token（理由见 {@link AiQueryService#askStreaming}），
 * 只在请求线程上取一次 traceId 带进工作线程——否则异步那半段的日志会掉 traceId，排障时接不上。
 */
@Slf4j
@Component
public class AiQueryStreamer {

    /**
     * 整条流的兜底超时。**必须小于 nginx 的 {@code proxy_read_timeout}**（流式那个 location 是 300s，
     * 见 {@code nginx/nginx.conf}），否则先断开的是 nginx，用户看到 504 而不是我们的文案。
     *
     * <p>正常路径远够不到它：模型客户端自身 30s 超时（{@code LangChainAiAssistant}）、
     * SQL 3s 超时，最坏也就 60 多秒。这里防的是"卡住不动"。
     */
    static final long STREAM_TIMEOUT_MS = 120_000L;

    private final AiQueryService aiQueryService;

    private final ObjectMapper objectMapper;

    private final ThreadPoolTaskExecutor executor;

    public AiQueryStreamer(AiQueryService aiQueryService,
                           ObjectMapper objectMapper,
                           @Qualifier("aiStreamExecutor") ThreadPoolTaskExecutor executor) {
        this.aiQueryService = aiQueryService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * 开一条流并在后台跑问数。**同步的前置检查（登录、权限、限流、参数校验）都在这之前做完了**，
     * 所以调用方拿到 emitter 时，这一条流一定已经"能开始"。
     *
     * @param tenantId 当前租户，必须由调用方在请求线程上解析（工作线程里没有 Sa-Token 上下文）
     */
    public SseEmitter start(String question, long tenantId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);

        emitter.onTimeout(() -> {
            log.warn("AI 流式问数超时（{}ms 未结束）tenantId={}", STREAM_TIMEOUT_MS, tenantId);
            send(emitter, "error",
                    new ErrorPayload(ErrorCode.AI_MODEL_UNAVAILABLE.getCode(), "模型响应超时，请重试", traceId));
            emitter.complete();
        });
        // 客户端断开（关页面 / 切路由）：只记一行，工作线程会把这一轮跑完。
        // 不去中断它——问数已经花掉的钱收不回来，而中断要维护"取消令牌"这套东西，不值得。
        emitter.onError(e -> log.debug("AI 流式问数连接中断: {}", e.getMessage()));

        try {
            executor.execute(() -> run(question, tenantId, traceId, emitter));
        } catch (TaskRejectedException e) {
            log.warn("AI 流式问数被线程池拒绝（队列已满）tenantId={}", tenantId);
            send(emitter, "error", new ErrorPayload(
                    ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage(), traceId));
            emitter.complete();
        }
        return emitter;
    }

    private void run(String question, long tenantId, String traceId, SseEmitter emitter) {
        if (traceId != null) {
            MDC.put(TraceIdFilter.TRACE_ID, traceId);
        }
        try {
            AiQueryVO vo = aiQueryService.askStreaming(question, tenantId, sink(emitter));
            // 结论与总耗时**只在同一时刻才有**（结论要等第二次模型调用），所以合成收尾帧：
            // 拆成"conclusion" + "done" 两帧只是多一次不携带新信息的往返，前端也得多一个分支。
            send(emitter, "done", new DonePayload(vo.getConclusion(), vo.getElapsedMs()));
            emitter.complete();
        } catch (BizException e) {
            // 业务失败（10002 / 40001~40004）与同步路径**用同一个错误码**，只是换了个通道送出去
            log.warn("AI 流式问数失败 code={} message={}", e.getErrorCode().getCode(), e.getMessage());
            send(emitter, "error", new ErrorPayload(e.getErrorCode().getCode(), e.getMessage(), traceId));
            emitter.complete();
        } catch (Exception e) {
            log.error("AI 流式问数未预期异常", e);
            send(emitter, "error", new ErrorPayload(
                    ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage(), traceId));
            emitter.complete();
        } finally {
            // Tomcat 的工作线程是复用的，不清理会把这条流的 traceId 串到下一条上（同 TraceIdFilter）
            MDC.remove(TraceIdFilter.TRACE_ID);
        }
    }

    /**
     * 把回调转成事件帧。帧里的 payload 与 {@code POST /api/ai/query} 的响应字段**同名同形**，
     * 前端两条路可以共用同一套类型（docs/03 §5.6）。
     */
    private AiQueryProgress sink(SseEmitter emitter) {
        return new AiQueryProgress() {

            @Override
            public void sqlReady(String sql, AiChartVO chart) {
                send(emitter, "sql", new SqlPayload(sql, chart));
            }

            @Override
            public void dataReady(AiQueryResult data) {
                send(emitter, "data", new DataPayload(data.columns(), data.rows(), data.rowLimited()));
            }
        };
    }

    /** 推送一帧。IO 失败（客户端已断开）由这里吃掉，不会把编排打断——错误路径尤其需要它。 */
    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json(payload)));
        } catch (Exception e) {
            // 走到这里基本只有一种原因：浏览器已经关了页面。它是**正常现象**，不是故障
            log.debug("推送 SSE 事件失败（客户端可能已断开）event={} err={}", event, e.getMessage());
        }
    }

    /**
     * 自己序列化而不是让 Spring 找转换器：帧里的 JSON 就是这个接口的契约，放在一处看得见；
     * 也省掉"POJO 交给谁序列化"这一层不确定性。
     */
    private String json(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // 自己的 record 序列化失败属于编码错误，不是运行时状况
            throw new IllegalStateException("SSE 事件序列化失败: " + payload.getClass().getSimpleName(), e);
        }
    }

    private record SqlPayload(String sql, AiChartVO chart) {
    }

    private record DataPayload(List<String> columns, List<List<Object>> rows, boolean rowLimited) {
    }

    private record DonePayload(String conclusion,
                               @JsonSerialize(using = NumericLongSerializer.class) Long elapsedMs) {
    }

    private record ErrorPayload(int code, String message, String traceId) {
    }
}
