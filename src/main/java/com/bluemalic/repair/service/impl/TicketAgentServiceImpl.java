package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.ai.agent.AgentLoop;
import com.bluemalic.repair.ai.agent.AgentModel;
import com.bluemalic.repair.ai.agent.AgentPrompts;
import com.bluemalic.repair.ai.agent.AgentRun;
import com.bluemalic.repair.ai.agent.AgentSession;
import com.bluemalic.repair.ai.agent.AgentSessionStore;
import com.bluemalic.repair.ai.agent.AgentTraceStep;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.dto.AgentIntakeDTO;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.TicketAgentService;
import com.bluemalic.repair.vo.AgentIntakeVO;
import com.bluemalic.repair.vo.AgentTraceVO;
import com.bluemalic.repair.vo.TriageSuggestionVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 受理与分诊的实现（ADR-017）。
 *
 * <p><b>这个类里所有"要害判断"都由服务端做，不交给模型</b>：
 * <ul>
 *   <li>{@code missing} / {@code ready} 自己算——缺什么决定"能不能落单"，不能采信模型的自述</li>
 *   <li>模型给的 {@code categoryId} / {@code buildingId} **逐个回库核对**：必须是本租户启用中的，
 *       否则一律置空（模型编一个 id 出来并不罕见，而放任它会让用户确认后提交失败）</li>
 *   <li>位置优先用扫码带来的（用户给的比模型猜的可靠）</li>
 * </ul>
 *
 * <p>本类**不写业务数据**：只读写 Redis 会话与几张只读查询。落单仍在
 * {@code TicketFlowService#submit}，那条链上的数据权限与审计一个都不少。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAgentServiceImpl implements TicketAgentService {

    /** 模型没按 JSON 回答时的兜底：不把"解析失败"暴露给用户，让他换个说法再说一遍。 */
    private static final String UNPARSED_REPLY = "我没太理解，能换一种说法再说一遍吗？";

    private final AgentLoop agentLoop;
    private final AgentModel agentModel;
    private final AgentSessionStore sessions;
    private final CurrentTenantService currentTenantService;
    private final RepairCodeMapper repairCodeMapper;
    private final BuildingMapper buildingMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final TicketMapper ticketMapper;
    private final ObjectMapper objectMapper;

    @Override
    public AgentIntakeVO intake(AgentIntakeDTO dto) {
        long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = currentTenantService.requireTenantId();

        AgentSession session = sessions.load(tenantId, userId, dto.getSessionId());
        // 扫码带来的位置优先：用户扫的码，比模型从描述里猜的可靠
        AgentSession.Draft seeded = mergeLocation(session.draft(),
                resolveRepairCode(tenantId, dto.getRepairCode()));

        AgentRun run = agentLoop.run(agentModel, messages(session, seeded, dto.getMessage()));
        ModelAnswer answer = parseIntake(run.reply());

        AgentSession.Draft draft = verifyIds(tenantId, seeded.merge(answer.draft()));
        List<String> missing = missingOf(draft);
        AgentIntakeVO vo = buildIntakeVO(session.sessionId(), answer.reply(), draft, missing, run.trace(),
                dto.getRepairCode());

        sessions.save(tenantId, userId, new AgentSession(session.sessionId(),
                nextHistory(session, dto.getMessage(), answer.reply()), draft));

        // 日志只记"发生了什么"，不记用户原文（AGENTS §5.8：AI prompt 不进日志）
        log.info("受理 Agent 一轮 tenantId={} userId={} tools={} 步数用尽={} ready={}",
                tenantId, userId, toolNames(run.trace()), run.stepLimitReached(), vo.isReady());
        return vo;
    }

    @Override
    public TriageSuggestionVO triage(long ticketId) {
        Long tenantId = currentTenantService.requireTenantId();
        // 数据权限拦截器覆盖 ticket（租户 + 角色），所以这里查不到就是"不存在或不在你范围内"，
        // 与工单详情的对外口径一致
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }

        List<ChatMessage> messages = List.of(
                SystemMessage.from(AgentPrompts.triage()),
                UserMessage.from(triageUserMessage(ticket)));
        AgentRun run = agentLoop.run(agentModel, messages);
        ModelAnswer answer = parseTriage(run.reply());
        AgentSession.Draft draft = verifyIds(tenantId,
                new AgentSession.Draft(answer.draft().categoryId(), answer.draft().urgency(), null, null, null));

        TriageSuggestionVO vo = new TriageSuggestionVO();
        vo.setSuggestedCategoryId(draft.categoryId());
        vo.setSuggestedCategoryName(categoryName(tenantId, draft.categoryId()));
        vo.setSuggestedUrgency(draft.urgency());
        vo.setReason(StringUtils.hasText(answer.reply()) ? answer.reply() : "（模型没给出理由）");
        vo.setTrace(trace(run.trace()));

        log.info("分诊建议 ticketId={} tenantId={} tools={} 建议类别={} 建议紧急度={}",
                ticketId, tenantId, toolNames(run.trace()), vo.getSuggestedCategoryId(), vo.getSuggestedUrgency());
        return vo;
    }

    // ==================== 位置上下文 ====================

    /** 扫码带来的位置（楼栋 + 房间）。码无效就返回 null——不报错，让用户继续用文字说。 */
    private AgentSession.Draft resolveRepairCode(Long tenantId, String repairCode) {
        if (!StringUtils.hasText(repairCode)) {
            return null;
        }
        RepairCode code = repairCodeMapper.selectOne(Wrappers.<RepairCode>lambdaQuery()
                .eq(RepairCode::getTenantId, tenantId)
                .eq(RepairCode::getCode, repairCode)
                .eq(RepairCode::getStatus, 1));
        if (code == null) {
            return null;
        }
        return new AgentSession.Draft(null, null, code.getBuildingId(), code.getRoom(), null);
    }

    private AgentSession.Draft mergeLocation(AgentSession.Draft draft, AgentSession.Draft location) {
        return location == null ? draft : draft.merge(location);
    }

    // ==================== 与服务端的核对 ====================

    /**
     * 核对模型给的 id，并补上展示用的名字。
     *
     * <p>id 不存在、不属于本租户、或已停用 → **置空**（当作没填，进 missing）。置空而不是报错，
     * 是因为"模型编了个 id"属于常见噪声，让用户补一句比抛错友好；而放任它则会走到
     * "用户点了确认、提交时报错"那条更差的路径上。
     */
    private AgentSession.Draft verifyIds(Long tenantId, AgentSession.Draft draft) {
        Long categoryId = draft.categoryId();
        Integer urgency = draft.urgency();
        if (categoryId != null) {
            TicketCategory category = activeCategory(tenantId, categoryId);
            if (category == null) {
                categoryId = null;
            } else if (urgency == null) {
                // 模型没判断紧急度时，用该类别的默认值——这比留空更接近"填全了"
                urgency = category.getDefaultUrgency();
            }
        }
        Long buildingId = null;
        if (draft.buildingId() != null) {
            if (activeBuilding(tenantId, draft.buildingId()) != null) {
                buildingId = draft.buildingId();
            } else {
                log.warn("受理 Agent 给出的 buildingId 不属于本租户或已停用，已置空 buildingId={} tenantId={}",
                        draft.buildingId(), tenantId);
            }
        }
        return new AgentSession.Draft(categoryId, urgency, buildingId, draft.room(), draft.description());
    }

    private TicketCategory activeCategory(Long tenantId, long categoryId) {
        TicketCategory category = ticketCategoryMapper.selectById(categoryId);
        return category != null && tenantId.equals(category.getTenantId())
                && Integer.valueOf(1).equals(category.getStatus()) ? category : null;
    }

    private Building activeBuilding(Long tenantId, long buildingId) {
        Building building = buildingMapper.selectById(buildingId);
        return building != null && tenantId.equals(building.getTenantId())
                && Integer.valueOf(1).equals(building.getStatus()) ? building : null;
    }

    private String categoryName(Long tenantId, Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        TicketCategory category = activeCategory(tenantId, categoryId);
        return category == null ? null : category.getName();
    }

    // ==================== 缺什么 / 够不够 ====================

    /**
     * 还缺哪些字段。**服务端算**，取值与 docs/03 的契约一致：
     * {@code categoryId} / {@code description} / {@code location}。
     * location 的判定是"有楼栋有房间"——位置是派单的前提，缺了这张单没人能上门。
     */
    private List<String> missingOf(AgentSession.Draft draft) {
        List<String> missing = new ArrayList<>();
        if (draft.categoryId() == null) {
            missing.add("categoryId");
        }
        if (!StringUtils.hasText(draft.description())) {
            missing.add("description");
        }
        if (draft.buildingId() == null || !StringUtils.hasText(draft.room())) {
            missing.add("location");
        }
        return missing;
    }

    // ==================== 提示词与历史的组装 ====================

    private List<ChatMessage> messages(AgentSession session, AgentSession.Draft draft, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(AgentPrompts.intake()));
        for (AgentSession.Turn turn : session.history()) {
            messages.add("user".equals(turn.role())
                    ? UserMessage.from(turn.text())
                    : AiMessage.from(turn.text()));
        }
        messages.add(UserMessage.from(intakeUserMessage(userMessage, draft)));
        return messages;
    }

    /** 把"用户这句话"与"已知位置"拼成一条消息：位置作为上下文告诉模型，省掉一轮追问。 */
    private String intakeUserMessage(String userMessage, AgentSession.Draft draft) {
        StringBuilder sb = new StringBuilder(userMessage);
        if (draft.buildingId() != null || StringUtils.hasText(draft.room())) {
            sb.append("\n（位置上下文：");
            sb.append(buildingLabel(draft.buildingId()));
            if (StringUtils.hasText(draft.room())) {
                sb.append(' ').append(draft.room());
            }
            sb.append("）");
        }
        return sb.toString();
    }

    private String buildingLabel(Long buildingId) {
        return buildingId == null ? "（楼栋未知）" : "楼栋 id=" + buildingId;
    }

    private String triageUserMessage(Ticket ticket) {
        return "工单信息：\n"
                + "描述：" + ticket.getDescription() + "\n"
                + "楼栋 id：" + ticket.getBuildingId() + "\n"
                + "房间：" + ticket.getRoom() + "\n"
                + "当前类别 id：" + ticket.getCategoryId() + "\n"
                + "当前紧急度：" + ticket.getUrgency();
    }

    private List<AgentSession.Turn> nextHistory(AgentSession session, String userMessage, String reply) {
        List<AgentSession.Turn> history = new ArrayList<>(session.history());
        history.add(new AgentSession.Turn("user", userMessage));
        if (StringUtils.hasText(reply)) {
            history.add(new AgentSession.Turn("assistant", reply));
        }
        return history;
    }

    // ==================== 模型输出的解析 ====================

    /** 解析结果：给用户看的一句话 + 草稿。模型自报的 missing 被忽略（服务端自己算）。 */
    private record ModelAnswer(String reply, AgentSession.Draft draft) {
    }

    /**
     * 解析模型这一轮的输出。**解析失败不是错误**：把整段回复当作"它只是说了句话"，
     * 草稿留空、让用户再说一轮——比抛 {@code AI_INTENT_UNRESOLVED} 友好得多，
     * 因为受理场景里"模型说了句人话但没给 JSON"是常态，不是故障。
     */
    private ModelAnswer parseIntake(String raw) {
        String json = stripCodeFence(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            String reply = node.path("reply").asText("");
            JsonNode draftNode = node.path("draft");
            AgentSession.Draft draft = new AgentSession.Draft(
                    longOrNull(draftNode, "categoryId"),
                    intOrNull(draftNode, "urgency"),
                    longOrNull(draftNode, "buildingId"),
                    textOrNull(draftNode, "room"),
                    textOrNull(draftNode, "description"));
            return new ModelAnswer(StringUtils.hasText(reply) ? reply : UNPARSED_REPLY, draft);
        } catch (Exception e) {
            log.warn("受理 Agent 的回复不是合法 JSON，按纯文本处理（长度 {}）", raw == null ? 0 : raw.length());
            String reply = StringUtils.hasText(raw) ? raw.trim() : UNPARSED_REPLY;
            return new ModelAnswer(reply, AgentSession.Draft.empty());
        }
    }

    /**
     * 解析分诊的回复。**与受理不是同一套字段**：受理是 {@code {"draft":{...}}}，
     * 分诊是顶层的 {@code suggestedCategoryId} / {@code suggestedUrgency} / {@code reason}。
     * 两者都用同一个解析器会得到"字段全空、建议值为 0"——测试抓过这个 bug。
     */
    private ModelAnswer parseTriage(String raw) {
        String json = stripCodeFence(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            String reason = node.path("reason").asText("");
            AgentSession.Draft draft = new AgentSession.Draft(
                    longOrNull(node, "suggestedCategoryId"),
                    intOrNull(node, "suggestedUrgency"),
                    null, null, null);
            return new ModelAnswer(StringUtils.hasText(reason) ? reason : UNPARSED_REPLY, draft);
        } catch (Exception e) {
            log.warn("分诊 Agent 的回复不是合法 JSON，按纯文本处理（长度 {}）", raw == null ? 0 : raw.length());
            return new ModelAnswer(StringUtils.hasText(raw) ? raw.trim() : UNPARSED_REPLY,
                    AgentSession.Draft.empty());
        }
    }

    /** 模型常把 JSON 包在 markdown 围栏里，剥掉再解析（与问数那条链同一个口径）。 */
    private String stripCodeFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return text;
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private String textOrNull(JsonNode node, String field) {
        String text = node.path(field).asText("");
        return StringUtils.hasText(text) ? text : null;
    }

    // ==================== 出参组装 ====================

    private AgentIntakeVO buildIntakeVO(String sessionId, String reply, AgentSession.Draft draft,
                                        List<String> missing, List<AgentTraceStep> trace, String repairCode) {
        AgentIntakeVO vo = new AgentIntakeVO();
        vo.setSessionId(sessionId);
        vo.setReply(reply);
        vo.setMissing(missing);
        vo.setReady(missing.isEmpty());
        vo.setTrace(trace(trace));
        if (vo.isReady() || draft.categoryId() != null || draft.buildingId() != null) {
            AgentIntakeVO.Draft dto = new AgentIntakeVO.Draft();
            dto.setCategoryId(draft.categoryId());
            dto.setCategoryName(categoryName(currentTenantService.requireTenantId(), draft.categoryId()));
            dto.setBuildingId(draft.buildingId());
            dto.setBuildingName(buildingName(draft.buildingId()));
            dto.setRoom(draft.room());
            dto.setUrgency(draft.urgency());
            dto.setDescription(draft.description());
            dto.setRepairCode(repairCode);
            vo.setDraft(dto);
        }
        return vo;
    }

    private String buildingName(Long buildingId) {
        if (buildingId == null) {
            return null;
        }
        Building building = buildingMapper.selectById(buildingId);
        return building == null ? null : building.getName();
    }

    private List<AgentTraceVO> trace(List<AgentTraceStep> steps) {
        return steps.stream().map(step -> {
            AgentTraceVO vo = new AgentTraceVO();
            vo.setTool(step.tool());
            vo.setMs(step.ms());
            vo.setSummary(step.summary());
            vo.setRejected(step.rejected());
            return vo;
        }).toList();
    }

    private List<String> toolNames(List<AgentTraceStep> steps) {
        return steps.stream().map(AgentTraceStep::tool).distinct().toList();
    }
}
