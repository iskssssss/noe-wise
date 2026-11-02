package com.skillforge.institution.controller;
import com.skillforge.institution.chat.context.OpenPlatformContextMessageService;
import com.skillforge.institution.chat.context.OpenPlatformContextMessageMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.WorkflowTestRuntimePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OpenPlatformContextMessageServiceTest {
    @Test void oneBackgroundArchiveReplaysButChangedContentConflicts() {
        var f=new Fixture();
        when(f.memory.append(anyString(),anyString(),anyLong(),eq("user"),anyString(),anyMap(),anyString()))
                .thenAnswer(call->{
                    String text=call.getArgument(4);assertTrue(text.contains("不是当前请求"));assertTrue(text.contains("\"context_only\":true"));
                    var record=new ChatMessageRecord(call.getArgument(6),"user",text,Instant.now(),call.getArgument(5),1,List.of());
                    when(f.memory.findByMessageId("7",record.messageId())).thenReturn(new MemoryService.ArchivedChatMessage("session",record));
                    return record;
                });
        var first=f.service.append(f.command("成功"));assertFalse(first.replayed());
        assertTrue(f.service.append(f.command("成功")).replayed());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->f.service.append(f.command("更改"))).getStatus());
        verify(f.memory,times(1)).append(anyString(),anyString(),anyLong(),anyString(),anyString(),anyMap(),anyString());
        var order=inOrder(f.locks,f.memory);order.verify(f.locks).lock(first.messageId());order.verify(f.memory).findByMessageId("7",first.messageId());
    }
    @Test void currentRevocationAndClosedOrGroupSessionsRejectBeforeArchive() {
        var f=new Fixture();var denied=new ApiException(HttpStatus.FORBIDDEN,"revoked");
        when(f.access.require("sessions.write")).thenThrow(denied);
        assertSame(denied,assertThrows(ApiException.class,()->f.service.append(f.command("成功"))));
        doReturn(f.current).when(f.access).require("sessions.write");
        when(f.sessions.isOpenPlatformClosed(f.session)).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->f.service.append(f.command("成功"))).getStatus());
        when(f.access.session(f.current,"session")).thenReturn(f.session.toBuilder().groupBindingId(10L).build());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->f.service.append(f.command("成功"))).getStatus());
        verifyNoInteractions(f.memory,f.locks);
    }
    private static final class Fixture {
        final OpenPlatformSessionAccessService access=mock(OpenPlatformSessionAccessService.class);
        final SessionService sessions=mock(SessionService.class);
        final WorkflowTestRuntimePolicy tests=mock(WorkflowTestRuntimePolicy.class);
        final MemoryService memory=mock(MemoryService.class);
        final OpenPlatformContextMessageMapper locks=mock(OpenPlatformContextMessageMapper.class);
        final ServicePrincipalContext.Identity principal=new ServicePrincipalContext.Identity("sp","7","hxy",1,Set.of("sessions.write"));
        final OpenPlatformAccessService.Access current=new OpenPlatformAccessService.Access(principal);
        final SessionView session=SessionView.builder().sessionId("session").tenantId(7L).agentId(8L).endUserId(9L).build();
        final OpenPlatformContextMessageService service=new OpenPlatformContextMessageService(access,sessions,tests,memory,locks,new ObjectMapper());
        Fixture(){when(access.require("sessions.write")).thenReturn(current);when(access.session(current,"session")).thenReturn(session);when(tests.session(any())).thenReturn(Optional.empty());}
        OpenPlatformContextMessageService.Command command(String answer){return new OpenPlatformContextMessageService.Command("session","business-1","",answer);}
    }
}
