package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.ai.ModelReplies;
import com.bluemalic.repair.ai.knowledge.AdviceModel;
import com.bluemalic.repair.ai.knowledge.KnowledgeHit;
import com.bluemalic.repair.ai.knowledge.KnowledgeRetriever;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.KnowledgeAdviceService;
import com.bluemalic.repair.vo.KnowledgeAdviceVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 师傅端「排查建议」的实现（阶段 2 · ADR-018）。
 *
 * <p>链路只有四步，**每一步都有它存在的理由**：
 * <ol>
 *   <li>取工单（走拦截器，看不到的单直接"不存在"）→ 拿到描述与类别</li>
 *   <li>检索本租户知识库（{@link KnowledgeRetriever}，类别优先）</li>
 *   <li>**检索零命中就到此为止**：如实说"知识库里没有"，而且不调模型——没有原料就不生成，
 *       省一次钱，也让"没有依据"这件事对用户可见</li>
 *   <li>有一次生成，然后**服务端核对引用**：模型说引了几号条目，不等于那条真在检索结果里。
 *       引不到的引用一律剔除；剔除后一条都不剩的步骤标 {@code cited=false}——前端据此提示
 *       "这条没有知识库依据"，而不是混在别的步骤里装作有</li>
 * </ol>
 *
 * <p><b>它为什么不用 Agent 循环</b>：受理 Agent 要"多轮决策"（缺字段就追问），
 * 而这里是一步检索 + 一次生成，套循环只会多花钱、还把引用来源的可靠性交给模型的多轮判断。
 * 真需要多轮（"再查查别的可能"）时，把检索包成工具交给 ADR-017 的循环即可——接口已经在那儿了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAdviceServiceImpl implements KnowledgeAdviceService {

    /** 一次给师傅几条参考资料：多了费 token 也容易让模型跑题（与检索实现的上限一致） */
    private static final int MAX_SOURCES = 5;

    /** 检索零命中时的话术：**说清是"没有资料"而不是"修不了"** */
    private static final String NO_KNOWLEDGE = "知识库里还没有这类问题的条目，这次没法给排查建议。"
            + "可以先把现场情况记下来，报修处理完成后由后勤补进知识库，下次就有依据了。";

    /** 模型没按 JSON 答时的兜底：不把解析失败暴露给师傅，让他自己看着办 */
    private static final String UNPARSED = "模型这次没给出可用的建议格式，请稍后再试一次。";

    private final KnowledgeRetriever knowledgeRetriever;
    private final AdviceModel adviceModel;
    private final TicketMapper ticketMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final CurrentTenantService currentTenantService;
    private final ObjectMapper objectMapper;

    @Override
    public KnowledgeAdviceVO advice(long ticketId) {
        long tenantId = currentTenantService.requireTenantId();

        // 1. 工单：ticket 在拦截器覆盖名单里（租户 + 角色两层），查不到就是"不存在或不在你范围内"
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }

        // 2. 检索：用描述找现象，用类别收窄
        List<KnowledgeHit> hits = knowledgeRetriever.search(tenantId, ticket.getDescription(),
                ticket.getCategoryId(), MAX_SOURCES);

        KnowledgeAdviceVO vo = new KnowledgeAdviceVO();
        vo.setRetriever(knowledgeRetriever.name());
        vo.setSources(hits.stream().map(KnowledgeAdviceServiceImpl::toSource).toList());

        // 3. 零命中：不调模型
        if (hits.isEmpty()) {
            vo.setGenerated(false);
            vo.setSuggestion(NO_KNOWLEDGE);
            vo.setSteps(List.of());
            log.info("排查建议（知识库无命中，未调模型）ticketId={} tenantId={} 检索方式={}",
                    ticketId, tenantId, knowledgeRetriever.name());
            return vo;
        }

        // 4. 生成 + 核对引用
        String raw = adviceModel.generate(ticket.getDescription(), categoryName(tenantId, ticket.getCategoryId()), hits);
        ParsedAnswer answer = parse(raw);
        Set<Long> allowed = new HashSet<>(hits.stream().map(KnowledgeHit::getId).toList());
        List<KnowledgeAdviceVO.Step> steps = verifyCitations(answer.steps(), allowed);
        long uncited = steps.stream().filter(step -> !step.isCited()).count();

        vo.setGenerated(true);
        vo.setSuggestion(answer.suggestion());
        vo.setSteps(steps);
        log.info("排查建议 ticketId={} tenantId={} 命中={} 步骤={} 无引用步骤={} 检索方式={}",
                ticketId, tenantId, hits.size(), steps.size(), uncited, knowledgeRetriever.name());
        return vo;
    }

    // ==================== 解析与核对 ====================

    /** 解析结果：一句话 + 步骤（步骤里的引用还没核对过）。 */
    private record ParsedAnswer(String suggestion, List<RawStep> steps) {
    }

    private record RawStep(String text, List<Long> citations) {
    }

    /**
     * 解析模型的 JSON。解析失败**不当错误抛**：给一句"重试"的话术返回，
     * 师傅没花任何操作成本，比抛 {@code 40004}（听着像模型挂了）更准。
     */
    private ParsedAnswer parse(String raw) {
        String json = ModelReplies.stripFence(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            String suggestion = node.path("suggestion").asText("");
            List<RawStep> steps = new ArrayList<>();
            for (JsonNode stepNode : node.path("steps")) {
                String text = stepNode.path("text").asText("");
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                List<Long> citations = new ArrayList<>();
                for (JsonNode cite : stepNode.path("citations")) {
                    if (cite.isNumber()) {
                        citations.add(cite.asLong());
                    } else if (cite.isTextual() && cite.asText().matches("[0-9]+")) {
                        // 模型可能把 id 用引号包起来——与 Agent 那条链踩过的坑同一个形状（docs/07 §5.5）
                        citations.add(Long.parseLong(cite.asText()));
                    }
                }
                steps.add(new RawStep(text.trim(), citations));
            }
            return new ParsedAnswer(StringUtils.hasText(suggestion) ? suggestion.trim() : UNPARSED, steps);
        } catch (Exception e) {
            log.warn("维修建议不是合法 JSON，按纯文本处理（长度 {}）", raw == null ? 0 : raw.length());
            return new ParsedAnswer(UNPARSED, List.of());
        }
    }

    /**
     * 引用核对：只保留"确实在本次检索结果里"的条目 id。
     *
     * <p>为什么必须有这一步：模型给出一个看着像 id 的数字太容易了，而**引用是这套东西可信的前提**。
     * 核对放在服务端，就是让"编造来源"变成可检测、可统计的事（上面那条日志里的"无引用步骤"）。
     */
    private List<KnowledgeAdviceVO.Step> verifyCitations(List<RawStep> rawSteps, Set<Long> allowed) {
        List<KnowledgeAdviceVO.Step> steps = new ArrayList<>(rawSteps.size());
        for (RawStep rawStep : rawSteps) {
            List<Long> valid = rawStep.citations().stream().filter(allowed::contains).distinct().toList();
            KnowledgeAdviceVO.Step step = new KnowledgeAdviceVO.Step();
            step.setText(rawStep.text());
            step.setCitations(valid);
            step.setCited(!valid.isEmpty());
            steps.add(step);
        }
        return steps;
    }

    private static KnowledgeAdviceVO.Source toSource(KnowledgeHit hit) {
        KnowledgeAdviceVO.Source source = new KnowledgeAdviceVO.Source();
        source.setId(hit.getId());
        source.setTitle(hit.getTitle());
        source.setScore(hit.getScore());
        return source;
    }

    private String categoryName(long tenantId, Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        TicketCategory category = ticketCategoryMapper.selectOne(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getId, categoryId)
                .eq(TicketCategory::getTenantId, tenantId));
        return category == null ? null : category.getName();
    }
}
