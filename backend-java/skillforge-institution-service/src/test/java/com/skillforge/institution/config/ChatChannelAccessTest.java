package com.skillforge.institution.config;

import com.skillforge.core.shared.web.CachedBodyHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.chat.ChatTeamService;
import com.skillforge.institution.service.ChatChannelAccess;
import com.skillforge.institution.team.TeamService;
import com.skillforge.institution.team.TeamDefinition;
import com.skillforge.institution.team.TeamMemberProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 渠道目标约束必须先于 SSE 启动，同时覆盖读取和写入。 */
class ChatChannelAccessTest {
    @BeforeAll static void initializeLambdaMappings() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentDO.class);
        TableInfoHelper.initTableInfo(assistant, ChannelAgentBindingDO.class);
    }

    @AfterEach void clear() { ChannelContext.clear(); TenantContext.clear(); }

    @Test void missingBindingAndUnpublishedAgentAreRejected() {
        TenantContext.setTenantId("1"); ChannelContext.setChannelId("2");
        AgentMapper agents = mock(AgentMapper.class);
        ChannelAgentBindingMapper bindings = mock(ChannelAgentBindingMapper.class);
        var access = access(agents, bindings, mock(SessionService.class));
        var agent = new AgentDO(); agent.setId(3L);
        when(agents.selectOne(any())).thenReturn(agent);
        when(bindings.selectCount(any())).thenReturn(1L);
        assertEquals(3L, access.requireAgent("3"));
        when(bindings.selectCount(any())).thenReturn(0L);
        assertThrows(ApiException.class, () -> access.requireAgent("3"));
        when(agents.selectOne(any())).thenReturn(null);
        assertThrows(ApiException.class, () -> access.requireAgent("3"));
        ChannelContext.clear();
        assertThrows(ApiException.class, access::catalog);
    }

    @Test void catalogExposesDelegationTargetsOnlyWhenTheyAreBoundAndPublished() {
        TenantContext.setTenantId("1");
        ChannelContext.setChannelId("2");
        AgentMapper agents = mock(AgentMapper.class);
        ChannelAgentBindingMapper bindings = mock(ChannelAgentBindingMapper.class);
        TeamMemberProfiles profiles = mock(TeamMemberProfiles.class);
        var publications = mock(AgentPublicationService.class);
        AgentDO source = agent(3L, new AgentDelegationConfig(true, List.of("4", "5")));
        AgentDO target = agent(4L, AgentDelegationConfig.disabled());
        when(bindings.selectList(any())).thenReturn(List.of(binding(3L, true), binding(4L, false), binding(3L, false), binding(5L, false)));
        // 数据库返回顺序不得覆盖绑定顺序；未发布目标 5 不在返回集合。
        var sourcePublication = publication(source);
        var targetPublication = publication(target);
        when(publications.currentPublishedAll(1L, List.of(3L,4L,5L))).thenReturn(Map.of(3L,sourcePublication,4L,targetPublication));
        when(profiles.membersFromPublications(eq(1L), anyList(), eq(""))).thenReturn(Map.of(
                3L, new TeamDefinition.Member("3", "", source.getName(), "", List.of()),
                4L, new TeamDefinition.Member("4", "", target.getName(), "", List.of())));
        ChatChannelAccess access = new ChatChannelAccess(agents, bindings, mock(SessionService.class),
                mock(ChatTeamService.class), mock(TeamService.class), profiles, publications);

        ChatChannelAccess.Catalog catalog = access.catalog();

        assertEquals(List.of("3", "4"), catalog.items().stream().map(ChatChannelAccess.ChatAgent::id).toList());
        assertEquals(List.of("4"), catalog.items().getFirst().delegationConfig().targetAgentIds());
        assertTrue(catalog.items().getFirst().delegationConfig().enabled());
        assertTrue(catalog.items().getFirst().isDefault());
        verifyNoInteractions(agents);
        verify(publications).currentPublishedAll(1L,List.of(3L,4L,5L));
        verify(profiles).membersFromPublications(1L, List.of(sourcePublication, targetPublication), "");
    }

    @Test void emptyCatalogDoesNotQueryAgentsOrProfiles() {
        TenantContext.setTenantId("1"); ChannelContext.setChannelId("2");
        var agents = mock(AgentMapper.class);
        var bindings = mock(ChannelAgentBindingMapper.class);
        var profiles = mock(TeamMemberProfiles.class);
        var publications = mock(AgentPublicationService.class);
        when(bindings.selectList(any())).thenReturn(List.of());
        var access = new ChatChannelAccess(agents, bindings, mock(SessionService.class),
                mock(ChatTeamService.class), mock(TeamService.class), profiles, publications);
        assertTrue(access.catalog().items().isEmpty());
        verifyNoInteractions(agents, profiles);
    }

    @Test void onlyRootHistoryReadsUseHistoryAccessWhileOtherRoutesKeepExecutionAccess() throws Exception {
        var access = mock(ChatChannelAccess.class);
        HandlerInterceptor guard = guard(access);
        var response = new MockHttpServletResponse();
        for (String path : java.util.List.of("/api/v1/chat/history", "/api/v1/chat/runs/status",
                "/api/v1/chat/events", "/api/v1/chat/limits", "/api/v1/chat/replay", "/api/v1/chat/sessions/deliverables")) {
            var request = external("GET", path);
            request.setParameter("session_id", "s1");
            guard.preHandle(request, response, null);
        }
        verify(access, times(4)).requireSessionHistory("s1");
        verify(access, times(2)).requireSession("s1");
        var list = external("GET", "/api/v1/chat/sessions");
        list.setParameter("agentId", "3");
        guard.preHandle(list, response, null);
        verify(access).requireHistoryTarget("3", null, null);
        var session = org.mockito.Mockito.mock(SessionView.class); org.mockito.Mockito.when(session.getAgentId()).thenReturn(3L);
        when(access.requireSession("s1")).thenReturn(session);
        when(access.requireAgent("4")).thenReturn(4L);
        var write = external("POST", "/api/v1/chat");
        write.setContent("{\"session_id\":\"s1\",\"agent_id\":\"4\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(ApiException.class, () -> guard.preHandle(new CachedBodyHttpServletRequest(write, 8 * 1024 * 1024), response, null));
        reset(access);
        guard.preHandle(new MockHttpServletRequest("GET", "/api/v1/chat/sessions"), response, null);
        verifyNoInteractions(access);
    }

    @Test void sameAgentOnTwoChannelsCannotShareSession() {
        TenantContext.setTenantId("1");ChannelContext.setChannelId("2");
        var sessions=mock(SessionService.class);var agents=mock(AgentMapper.class);var bindings=mock(ChannelAgentBindingMapper.class);
        var session=org.mockito.Mockito.mock(SessionView.class);org.mockito.Mockito.when(session.getAgentId()).thenReturn(3L);org.mockito.Mockito.when(session.getEndUserId()).thenReturn(9L);org.mockito.Mockito.when(session.getChannelId()).thenReturn(2L);
        var agent=new AgentDO();agent.setId(3L);
        when(sessions.require("1","s")).thenReturn(session);when(agents.selectOne(any())).thenReturn(agent);when(bindings.selectCount(any())).thenReturn(1L);
        var access=access(agents,bindings,sessions);
        assertSame(session,access.requireSession("s"));
        assertSame(session,access.requireSessionHistory("s"));
        ChannelContext.setChannelId("4");assertThrows(ApiException.class,()->access.requireSession("s"));
        assertThrows(ApiException.class,()->access.requireSessionHistory("s"));
        org.mockito.Mockito.when(session.getChannelId()).thenReturn(null);assertThrows(ApiException.class,()->access.requireSession("s"));
        assertThrows(ApiException.class,()->access.requireSessionHistory("s"));
    }

    @Test void collaborationReadAndPlanModeWriteRequireTheTargetSession() throws Exception {
        var access = mock(ChatChannelAccess.class);
        HandlerInterceptor guard = guard(access);
        var response = new MockHttpServletResponse();

        var read = external("GET", "/api/v1/chat/runs/collaboration");
        read.setParameter("session_id", "s1");
        guard.preHandle(read, response, null);

        var write = external("POST", "/api/v1/chat/runs/plan-mode");
        write.setContent("{\"session_id\":\"s1\",\"active\":true,\"command_id\":\"c1\"}"
                .getBytes(StandardCharsets.UTF_8));
        guard.preHandle(new CachedBodyHttpServletRequest(write, 8 * 1024 * 1024), response, null);

        verify(access, times(2)).requireSession("s1");
        verify(access, never()).requireAgent(any());
    }

    /** 工作区接口使用 sessionId 参数，开放鉴权后仍须拒绝其他渠道的会话。 */
    @Test void workspaceStatusRequiresChannelSessionWithCamelCaseQuery() throws Exception {
        TenantContext.setTenantId("1");
        ChannelContext.setChannelId("2");
        var sessions = mock(SessionService.class);
        var agents = mock(AgentMapper.class);
        var bindings = mock(ChannelAgentBindingMapper.class);
        var session = org.mockito.Mockito.mock(SessionView.class); org.mockito.Mockito.when(session.getAgentId()).thenReturn(3L); org.mockito.Mockito.when(session.getEndUserId()).thenReturn(9L); org.mockito.Mockito.when(session.getChannelId()).thenReturn(2L);
        var agent = new AgentDO(); agent.setId(3L);
        when(sessions.require("1", "workspace-session")).thenReturn(session);
        when(agents.selectOne(any())).thenReturn(agent);
        when(bindings.selectCount(any())).thenReturn(1L);
        var guard = guard(access(agents, bindings, sessions));
        var read = external("GET", "/api/v1/chat/sessions/workspace");
        read.setParameter("sessionId", "workspace-session");
        var response = new MockHttpServletResponse();

        assertTrue(guard.preHandle(read, response, null));
        ChannelContext.setChannelId("4");
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> guard.preHandle(read, response, null)).getStatus());
        verify(sessions, times(2)).require("1", "workspace-session");
    }

    @Test void collaborationEndpointsRejectSessionsFromAnotherChannel() throws Exception {
        TenantContext.setTenantId("1");
        ChannelContext.setChannelId("4");
        var sessions = mock(SessionService.class);
        var session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getAgentId()).thenReturn(3L);
        org.mockito.Mockito.when(session.getEndUserId()).thenReturn(9L);
        org.mockito.Mockito.when(session.getChannelId()).thenReturn(2L);
        when(sessions.require("1", "s1")).thenReturn(session);
        var access = access(mock(AgentMapper.class), mock(ChannelAgentBindingMapper.class), sessions);
        HandlerInterceptor guard = guard(access);
        var response = new MockHttpServletResponse();

        var read = external("GET", "/api/v1/chat/runs/collaboration");
        read.setParameter("session_id", "s1");
        ApiException readFailure = assertThrows(ApiException.class,
                () -> guard.preHandle(read, response, null));

        var write = external("POST", "/api/v1/chat/runs/plan-mode");
        write.setContent("{\"session_id\":\"s1\",\"active\":false,\"command_id\":\"c2\"}"
                .getBytes(StandardCharsets.UTF_8));
        ApiException writeFailure = assertThrows(ApiException.class,
                () -> guard.preHandle(new CachedBodyHttpServletRequest(write, 8 * 1024 * 1024), response, null));

        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, readFailure.getStatus());
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, writeFailure.getStatus());
        verify(sessions, times(2)).require("1", "s1");
    }

    @Test void runControlAlwaysChecksChannelSessionAndPublishedBinding() throws Exception {
        TenantContext.setTenantId("1");
        ChannelContext.setChannelId("2");
        var sessions = mock(SessionService.class);
        var agents = mock(AgentMapper.class);
        var bindings = mock(ChannelAgentBindingMapper.class);
        var session = org.mockito.Mockito.mock(SessionView.class); org.mockito.Mockito.when(session.getAgentId()).thenReturn(3L); org.mockito.Mockito.when(session.getEndUserId()).thenReturn(9L); org.mockito.Mockito.when(session.getChannelId()).thenReturn(2L);
        var agent = new AgentDO(); agent.setId(3L);
        when(sessions.require("1", "s1")).thenReturn(session);
        when(agents.selectOne(any())).thenReturn(agent);
        when(bindings.selectCount(any())).thenReturn(1L);
        var guard = guard(access(agents, bindings, sessions));
        var response = new MockHttpServletResponse();
        for (String source : java.util.List.of(ExternalAuthPaths.SOURCE_CHANNEL, ExternalAuthPaths.SOURCE_OPENAPI)) {
            for (String path : java.util.List.of("/api/v1/chat/runs/status", "/api/v1/chat/runs/budget")) {
            var read = external("GET", path);
            read.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE, source);
            read.setParameter("session_id", "s1");
            read.setParameter("run_id", "r1");
            var write = external("POST", "/api/v1/chat/runs/cancel");
            write.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE, source);
            write.setContent("{\"session_id\":\"s1\",\"run_id\":\"r1\"}".getBytes(StandardCharsets.UTF_8));
            var cancel = new CachedBodyHttpServletRequest(write, 8 * 1024 * 1024);
            assertTrue(guard.preHandle(read, response, null));
            assertTrue(guard.preHandle(cancel, response, null));
            ChannelContext.setChannelId("4");
            assertThrows(ApiException.class, () -> guard.preHandle(read, response, null));
            assertThrows(ApiException.class, () -> guard.preHandle(cancel, response, null));
            ChannelContext.setChannelId("2");
            when(bindings.selectCount(any())).thenReturn(0L);
            assertThrows(ApiException.class, () -> guard.preHandle(read, response, null));
            assertThrows(ApiException.class, () -> guard.preHandle(cancel, response, null));
            when(bindings.selectCount(any())).thenReturn(1L);
            when(agents.selectOne(any())).thenReturn(null);
            assertThrows(ApiException.class, () -> guard.preHandle(read, response, null));
            assertThrows(ApiException.class, () -> guard.preHandle(cancel, response, null));
            when(agents.selectOne(any())).thenReturn(agent);
            }
        }
    }

    @Test void teamSessionRequiresAvailableTeamForItsBoundEndUser() {
        TenantContext.setTenantId("1"); ChannelContext.setChannelId("2");
        var sessions = mock(SessionService.class);
        var userTeams = mock(ChatTeamService.class);
        var session = SessionView.builder().sessionId("team-session").tenantId(1L)
                .teamId("team-1").endUserId(9L).channelId(2L).build();
        when(sessions.require("1", "team-session")).thenReturn(session);

        var access = new ChatChannelAccess(mock(AgentMapper.class), mock(ChannelAgentBindingMapper.class),
                sessions, userTeams, mock(TeamService.class), mock(com.skillforge.institution.team.TeamMemberProfiles.class), mock(AgentPublicationService.class));

        assertSame(session, access.requireSession("team-session"));
        verify(userTeams).requireAvailable("team-1", "9");
        assertSame(session, access.requireSessionHistory("team-session"));
        verify(userTeams).requireHistory("team-1", "9");
        assertThrows(ApiException.class, () -> access.requireHistoryTarget("3", "team-1", "9"));
    }

    @Test void existingSessionRejectsTargetAndEndUserChanges() throws Exception {
        var access = mock(ChatChannelAccess.class);
        HandlerInterceptor guard = guard(access);
        var session = org.mockito.Mockito.mock(SessionView.class); org.mockito.Mockito.when(session.getAgentId()).thenReturn(3L); org.mockito.Mockito.when(session.getEndUserId()).thenReturn(9L);
        when(access.requireSession("s1")).thenReturn(session);
        when(access.requireAgent("4")).thenReturn(4L);

        var changedTarget = external("POST", "/api/v1/chat");
        changedTarget.setContent("{\"session_id\":\"s1\",\"agent_id\":\"4\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(ApiException.class,
                () -> guard.preHandle(new CachedBodyHttpServletRequest(changedTarget, 8 * 1024 * 1024), new MockHttpServletResponse(), null));

        var changedUser = external("POST", "/api/v1/chat");
        changedUser.setContent("{\"session_id\":\"s1\",\"context\":{\"end_user_id\":\"10\"}}"
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(ApiException.class,
                () -> guard.preHandle(new CachedBodyHttpServletRequest(changedUser, 8 * 1024 * 1024), new MockHttpServletResponse(), null));
    }

    private ChatChannelAccess access(AgentMapper agents, ChannelAgentBindingMapper bindings, SessionService sessions) {
        return new ChatChannelAccess(agents, bindings, sessions, mock(ChatTeamService.class), mock(TeamService.class), mock(com.skillforge.institution.team.TeamMemberProfiles.class), mock(AgentPublicationService.class));
    }

    private AgentPublicationService.Publication publication(AgentDO agent) {
        return new AgentPublicationService.Publication(agent.getId()+100,1,agent,
                new com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult(1L,agent.getId(),1,List.of()),
                java.time.Instant.EPOCH);
    }
    private AgentDO agent(long id, AgentDelegationConfig delegationConfig) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setTenantId(1L);
        agent.setName("Agent " + id);
        agent.setDelegationConfig(delegationConfig);
        return agent;
    }

    private ChannelAgentBindingDO binding(long agentId, boolean defaultAgent) {
        ChannelAgentBindingDO binding = new ChannelAgentBindingDO();
        binding.setAgentId(agentId);
        binding.setIsDefault(defaultAgent);
        return binding;
    }

    private MockHttpServletRequest external(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE, ExternalAuthPaths.SOURCE_CHANNEL);
        return request;
    }
    private HandlerInterceptor guard(ChatChannelAccess access) {
        var registry = mock(InterceptorRegistry.class);
        var captor = ArgumentCaptor.forClass(HandlerInterceptor.class);
        when(registry.addInterceptor(any())).thenAnswer(call -> new InterceptorRegistration(call.getArgument(0)));
        new ChatChannelAccessConfiguration(access, new ObjectMapper(), mock(com.skillforge.institution.chat.ChatEndUserTokens.class)).addInterceptors(registry);
        verify(registry).addInterceptor(captor.capture());
        return captor.getValue();
    }
}
