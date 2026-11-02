package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.ChatService;
import java.time.Instant;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 会话列表按 Agent 过滤集成测试。
 *
 * <p>管理端对话记录：sessions 接口按 agentId 过滤。
 */
class ChatSessionsFilterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private EndUserMapper endUserMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMessageMapper messageMapper;

    /** 测试租户 ID。 */
    private String tenantId;
    private long memberId;

    /** 测试演示用户 ID。 */
    private Long endUserId;

    /** 原 group 场景使用真实 Team 目标，不再构造无目标会话。 */
    private String teamId;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        tenantId = createTenant(tenantMapper, planMapper, "sessions-" + SnowflakeIds.nextId());
        memberId = createTestTenantAdministrator(tenantId, "sessions-admin", "SessionsFixture123");
        EndUserDO demo = new EndUserDO();
        demo.setId(SnowflakeIds.nextId());
        demo.setTenantId(Long.parseLong(tenantId));
        demo.setDisplayName("演示用户");
        demo.setStatus("active");
        demo.setFirstSeenAt(Instant.now());
        demo.setLastActiveAt(Instant.now());
        demo.setCreatedAt(Instant.now());
        endUserMapper.insert(demo);
        endUserId = demo.getId();
        teamId = "team-" + SnowflakeIds.nextId();
        jdbc.update("""
                INSERT INTO nw_agent_teams(tenant_id,id,revision,status,definition)
                VALUES (?, ?, 1, 'draft', '{}'::jsonb)
                """, Long.parseLong(tenantId), teamId);
    }

    /** 插入满足 FK 的最小 Agent 行。 */
    private Long insertAgent() {
        AgentDO agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId());
        agent.setTenantId(Long.parseLong(tenantId));
        agent.setAgentKey("agent-" + agent.getId());
        agent.setName("测试 Agent");
        agent.setTemplateVersion("1.0");
        agent.setSystemPrompt("你是测试助手");
        agent.setRoleDescription("测试");
        agentMapper.insert(agent);
        return agent.getId();
    }

    private SessionDO insertSession(String sessionId, Long agentId) {
        return insertSession(sessionId, agentId, endUserId);
    }

    private SessionDO insertSession(String sessionId, Long agentId, Long userId) {
        SessionDO session = new SessionDO();
        session.setId(SnowflakeIds.nextId());
        // session_id 全局唯一（sessions_session_id_key），测试间复用固定 ID 会冲突，拼雪花后缀
        session.setSessionId(sessionId + "-" + SnowflakeIds.nextId());
        session.setTenantId(Long.parseLong(tenantId));
        session.setEndUserId(userId);
        session.setOriginKind("native");
        session.setActorRef("end_user:" + userId);
        session.setResponsibilityRef("tenant:" + tenantId);
        session.setAgentId(agentId);
        session.setTeamId(agentId == null ? teamId : null);
        session.setMessageCount(1);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        sessionMapper.insert(session);
        return session;
    }

    @Test
    void sessionsFilteredByAgentId() {
        try {
            TenantContext.setTenantId(tenantId);
            Long agentId = insertAgent();
            insertSession("sess-agent", agentId);
            insertSession("sess-team", null);

            var result = withAuthenticatedMember(memberId, () -> chatService.sessions(tenantId, agentId, endUserId));
            assertEquals(1, result.size());
            assertTrue(result.get(0).sessionId().startsWith("sess-agent"));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 无过滤条件返回全部会话。
     */
    @Test
    void sessionsWithoutFilterReturnsAll() {
        try {
            TenantContext.setTenantId(tenantId);
            insertSession("sess-team", null);
            insertSession("sess-agent", insertAgent());
            insertSession("sess-team-second", null);

            var result = withAuthenticatedMember(memberId, () -> chatService.sessions(tenantId, null, endUserId));
            assertEquals(3, result.size());
            assertTrue(result.stream().anyMatch(s -> s.sessionId().startsWith("sess-team-")));
            assertTrue(result.stream().anyMatch(s -> s.sessionId().startsWith("sess-team-second")));
        } finally {
            TenantContext.clear();
        }
    }
    @Test
    void optionalUserReturnsAllSessionsOrderedByLatestMessage() {
        TenantContext.setTenantId(tenantId);
        try {
            var older = insertSession("older", null);
            var secondUser = new EndUserDO();
            secondUser.setId(SnowflakeIds.nextId());
            secondUser.setTenantId(Long.parseLong(tenantId));
            secondUser.setDisplayName("第二用户");
            secondUser.setStatus("active");
            secondUser.setFirstSeenAt(Instant.now());
            secondUser.setLastActiveAt(Instant.now());
            secondUser.setCreatedAt(Instant.now());
            endUserMapper.insert(secondUser);
            var newer = insertSession("newer", null, secondUser.getId());
            insertSession("empty", null);
            newer.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            sessionMapper.updateById(newer);
            addMessage(older, "2026-09-20T00:00:00Z");
            addMessage(newer, "2026-09-19T00:00:00Z");
            addMessage(newer, "2026-09-21T00:00:00Z");
            for (int i = 0; i < 51; i++) insertSession("extra", null);
            var all = withAuthenticatedMember(memberId, () -> chatService.sessions(tenantId, null, null));
            assertEquals(54, all.size());
            assertEquals(List.of(newer.getSessionId(), older.getSessionId()),
                    all.subList(0, 2).stream().map(item -> item.sessionId()).toList());
            assertEquals(53, withAuthenticatedMember(memberId, () -> chatService.sessions(tenantId, null, endUserId)).size());
        } finally {
            TenantContext.clear();
        }
    }

    private void addMessage(SessionDO session, String timestamp) {
        var message = new ChatMessageDO();
        message.setId(SnowflakeIds.nextId());
        message.setTenantId(session.getTenantId());
        message.setEndUserId(session.getEndUserId());
        message.setSessionId(session.getSessionId());
        message.setMessageId("message-" + message.getId());
        message.setRole("user");
        message.setContent("测试消息");
        message.setCreatedAt(Instant.parse(timestamp));
        messageMapper.insert(message);
    }

}
