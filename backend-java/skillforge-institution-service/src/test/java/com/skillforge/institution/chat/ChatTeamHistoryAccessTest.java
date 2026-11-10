package com.skillforge.institution.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.team.TeamDefinition;
import com.skillforge.institution.team.TeamService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 停用只放开根会话历史，不能替代当前所属身份或任何发布成员的授权。 */
class ChatTeamHistoryAccessTest {
    private final ChatTeamMapper mapper = mock(ChatTeamMapper.class);
    private final TeamService teams = mock(TeamService.class);
    private final ChatTeamService service = new ChatTeamService(mapper, teams,
            mock(ChatClientProfileMapper.class), mock(EndUserService.class));
    private final TeamDefinition publication = new TeamDefinition("team", "历史团队", "", "published", 4,
            List.of(new TeamDefinition.Member("3", "1", "主助手", "", List.of()),
                    new TeamDefinition.Member("4", "1", "协作者", "", List.of())), "3");

    @BeforeEach void authenticate() {
        TenantContext.setTenantId("1");
        ChannelContext.setChannelId("2");
    }

    @AfterEach void clearContexts() {
        TenantContext.clear();
        ChannelContext.clear();
    }

    @Test void disabledTeamHistoryChecksEveryPublishedMemberButDoesNotGrantExecution() {
        when(mapper.available(1L, 2L, "team", "9")).thenReturn(true);
        when(teams.publishedForHistory("1", "team")).thenReturn(publication);
        when(mapper.authorizedAgent(1L, 2L, "3")).thenReturn(true);
        when(mapper.authorizedAgent(1L, 2L, "4")).thenReturn(true);
        when(teams.published("1", "team"))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "团队尚未发布或已停用"));

        service.requireHistory("team", "9");
        verify(mapper).authorizedAgent(1L, 2L, "3");
        verify(mapper).authorizedAgent(1L, 2L, "4");
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.requireAvailable("team", "9")).getStatus());
    }

    @Test void anotherUserOrChannelCannotReadPublicationMetadata() {
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.requireHistory("team", "10")).getStatus());
        ChannelContext.setChannelId("5");
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.requireHistory("team", "9")).getStatus());
        verify(mapper).available(1L, 2L, "team", "10");
        verify(mapper).available(1L, 5L, "team", "9");
        verifyNoInteractions(teams);
    }

    @Test void revokedCollaboratorStillRejectsHistoryEvenWhenPrimaryRemainsAuthorized() {
        when(mapper.available(1L, 2L, "team", "9")).thenReturn(true);
        when(teams.publishedForHistory("1", "team")).thenReturn(publication);
        when(mapper.authorizedAgent(1L, 2L, "3")).thenReturn(true);
        when(mapper.authorizedAgent(1L, 2L, "4")).thenReturn(false);

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> service.requireHistory("team", "9")).getStatus());
        verify(mapper).authorizedAgent(1L, 2L, "3");
        verify(mapper).authorizedAgent(1L, 2L, "4");
        verify(teams, never()).published("1", "team");
    }
}
