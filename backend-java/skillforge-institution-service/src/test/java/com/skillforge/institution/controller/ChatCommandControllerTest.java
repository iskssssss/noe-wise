package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.skillforge.common.context.ChannelContext;
import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.chat.ChatTeamService;
import com.skillforge.institution.service.ChatChannelAccess;
import com.skillforge.institution.team.TeamService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatCommandControllerTest {
    private final AgentMapper agents = mock(AgentMapper.class);
    private final ChannelAgentBindingMapper bindings = mock(ChannelAgentBindingMapper.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ChatTeamService userTeams = mock(ChatTeamService.class);
    private final TeamService teams = mock(TeamService.class);
    private final ChatCommandController controller = new ChatCommandController(
            new ChatChannelAccess(agents, bindings, sessions, userTeams, teams, mock(com.skillforge.institution.team.TeamMemberProfiles.class), mock(com.skillforge.core.agent.AgentPublicationService.class)));

    @AfterEach void clear() { TenantContext.clear(); ChannelContext.clear(); }

    private void authenticate() {
        TenantContext.setTenantId("1"); ChannelContext.setChannelId("2");
        var agent = new AgentDO(); agent.setId(3L);
        when(agents.selectOne(any())).thenReturn(agent);
        when(bindings.selectCount(any())).thenReturn(1L);
    }

    @Test void exposesReadonlyCatalogBeforeSessionCreation() throws Exception {
        authenticate();
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/v1/chat/commands").param("agent_id", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(7))
                .andExpect(jsonPath("$.data.items[0].command").value("/plan"))
                .andExpect(jsonPath("$.data.items[0].title").value("开启计划模式"))
                .andExpect(jsonPath("$.data.items[1].command").value("/plan off"))
                .andExpect(jsonPath("$.data.items[2].command").value("/goal"))
                .andExpect(jsonPath("$.data.items[3].command").value("/goal status"))
                .andExpect(jsonPath("$.data.items[4].command").value("/goal pause"))
                .andExpect(jsonPath("$.data.items[5].command").value("/goal resume"))
                .andExpect(jsonPath("$.data.items[6].command").value("/delivery"));
        verifyNoInteractions(sessions);
    }

    @Test void rejectsMissingIdentityBindingAndUnpublishedAgent() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> controller.commands("3", null, null)).getStatus());
        authenticate();
        when(bindings.selectCount(any())).thenReturn(0L);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> controller.commands("3", null, null)).getStatus());
        when(bindings.selectCount(any())).thenReturn(1L);
        when(agents.selectOne(any())).thenReturn(null);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> controller.commands("3", null, null)).getStatus());
        verifyNoInteractions(sessions);
    }

    @Test void requiresAgentWithoutUsingSessionAsFallback() throws Exception {
        authenticate();
        Exception failure = assertThrows(Exception.class, () -> MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/v1/chat/commands").param("session_id", "someone-elses-session")));
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertInstanceOf(ApiException.class, root);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ((ApiException) root).getStatus());
        assertThrows(ApiException.class, () -> controller.commands(" ", null, null));
        verifyNoInteractions(agents, bindings, sessions);
    }

    @Test void institutionCatalogRequiresLoginAndPublishedTenantAgent() {
        TenantContext.setTenantId("1");
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::isLogin).thenReturn(false);
            assertEquals(HttpStatus.UNAUTHORIZED,
                    assertThrows(ApiException.class, () -> controller.commands("3", null, null)).getStatus());
            verifyNoInteractions(agents, bindings, sessions);
            login.when(StpUtil::isLogin).thenReturn(true);
            var agent = new AgentDO(); agent.setId(3L);
            when(agents.selectOne(any())).thenReturn(agent);
            assertEquals(7, controller.commands("3", null, null).data().items().size());
            when(agents.selectOne(any())).thenReturn(null);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(ApiException.class, () -> controller.commands("3", null, null)).getStatus());
            verifyNoInteractions(bindings, sessions);
        }
    }

    @Test void teamCatalogOnlyExposesPlanCommands() {
        authenticate();

        var catalog = controller.commands(null, "team-1", "user-1");

        assertEquals(List.of("/plan", "/plan off"), catalog.data().items().stream().map(ChatCommandController.ChatCommand::command).toList());
        verify(userTeams).requireAvailable("team-1", "user-1");
        verifyNoInteractions(agents, bindings, sessions);
    }
}
