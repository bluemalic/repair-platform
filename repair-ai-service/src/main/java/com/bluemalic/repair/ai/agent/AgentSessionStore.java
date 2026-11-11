package com.bluemalic.repair.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 受理会话的存取（Redis）。**键带 TTL**，遵守 ADR-016 定的约定：除超时队列外，往 Redis 写任何键
 * 都必须设过期时间（Redis 的淘汰策略是 volatile-lru，靠"有没有 TTL"决定谁先被淘汰）。
 *
 * <p>会话丢了（过期 / Redis 重启）表现为"对话要重新开始"——不是数据丢失：草稿本来也只是建议，
 * 没有落库。
 *
 * <p>键里带 tenantId 与 userId：**不同租户、不同用户的会话天然隔离**；再加客户端给的 sessionId，
 * 是为了让"换个页面重新开始"与"同一个会话继续"能区分开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionStore {

    /** 会话多久没动就作废。报修这件事不需要跨天接着聊，30 分钟够用。 */
    private static final Duration TTL = Duration.ofMinutes(30);

    /**
     * 历史最多保留多少轮（一问一答算两轮）。
     * 这是**成本控制**：历史每轮都要随请求重发一遍，留太长会让 token 消耗与费用线性上涨。
     */
    private static final int MAX_TURNS = 12;

    /** sessionId 只允许这几类字符：它会被拼进 Redis 键，不能接受任意字符串（键注入）。 */
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private static final String KEY_PREFIX = "agent:intake:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 取会话；不存在或已过期时返回一个**新的空会话**（不是错误——过期就重新开始聊）。
     * sessionId 不合法或缺失时生成一个新的。
     */
    public AgentSession load(long tenantId, long userId, String sessionId) {
        String id = normalize(sessionId);
        try {
            String json = redis.opsForValue().get(key(tenantId, userId, id));
            if (json != null) {
                AgentSession session = objectMapper.readValue(json, AgentSession.class);
                // 键里的 id 是权威：存的内容对不上说明被复用了，当作新会话
                return session.sessionId().equals(id)
                        ? session
                        : new AgentSession(id, List.of(), AgentSession.Draft.empty());
            }
        } catch (Exception e) {
            // 拿不到旧会话不是致命问题（重新开始聊即可），但日志要留痕
            log.warn("读取受理会话失败，按新会话处理 key={} err={}", key(tenantId, userId, id), e.getMessage());
        }
        return new AgentSession(id, List.of(), AgentSession.Draft.empty());
    }

    /** 落盘并**刷新 TTL**（滚动过期：只要还在聊就不会中途被清）。 */
    public void save(long tenantId, long userId, AgentSession session) {
        List<AgentSession.Turn> trimmed = session.history().size() > MAX_TURNS
                ? session.history().subList(session.history().size() - MAX_TURNS, session.history().size())
                : session.history();
        AgentSession toSave = new AgentSession(session.sessionId(), new ArrayList<>(trimmed), session.draft());
        try {
            redis.opsForValue().set(key(tenantId, userId, session.sessionId()),
                    objectMapper.writeValueAsString(toSave), TTL);
        } catch (Exception e) {
            // 存不上只影响"下一轮记不记得住"，不该让这一轮的回答白算——所以不抛
            log.warn("保存受理会话失败 sessionId={} err={}", session.sessionId(), e.getMessage());
        }
    }

    /** 清空会话（用户主动"重新开始"时用）。 */
    public void clear(long tenantId, long userId, String sessionId) {
        try {
            redis.delete(key(tenantId, userId, normalize(sessionId)));
        } catch (Exception e) {
            log.warn("清空受理会话失败 err={}", e.getMessage());
        }
    }

    private String normalize(String sessionId) {
        return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches()
                ? sessionId
                : UUID.randomUUID().toString();
    }

    private String key(long tenantId, long userId, String sessionId) {
        return KEY_PREFIX + tenantId + ":" + userId + ":" + sessionId;
    }
}
