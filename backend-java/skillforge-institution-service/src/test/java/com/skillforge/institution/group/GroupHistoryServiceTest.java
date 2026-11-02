package com.skillforge.institution.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;

/** 固定公开水位与当前群资格分别校验，游标不成为持续授权。 */
class GroupHistoryServiceTest {
    private final GroupBindingService groups=mock(GroupBindingService.class);
    private final InstitutionIntegrationService integrations=mock(InstitutionIntegrationService.class);
    private final SessionService sessions=mock(SessionService.class);
    private final GroupHistoryMapper mapper=mock(GroupHistoryMapper.class);
    private final SessionMailboxService mailbox=mock(SessionMailboxService.class);
    private final GroupHistorySourcePublisher sources=mock(GroupHistorySourcePublisher.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private GroupHistoryService history;

    @BeforeEach void setup() {
        TenantContext.setTenantId("11");ServicePrincipalContext.set(new ServicePrincipalContext.Identity("principal","11","integration",1,Set.of(GroupHistoryService.READ_SCOPE)));
        var projection=spy(new AgentRuntimeExecutionProjection(mock(ExecutionStore.class),mailbox,new PublicOutputGuard("./not-public-artifacts"),json,100_000,10_000,32));
        doNothing().when(projection).reconcile(any(SessionView.class));
        history=new GroupHistoryService(groups,integrations,sessions,mapper,sources,projection,mailbox,json,
                new GroupHistoryProperties(Duration.ofSeconds(60),Duration.ofSeconds(15),Duration.ofSeconds(5)),new PublicOutputGuard("./not-public-artifacts"));
        bind("41","session");when(mailbox.committedCursor("11","session")).thenReturn("output:5");
        when(mailbox.resolveOutputSequence("11","session","output:5")).thenReturn(5L);
    }
    @AfterEach void clear(){ServicePrincipalContext.clear();TenantContext.clear();}

    private void bind(String group,String sessionId) {
        when(groups.requireActive("11","integration",group)).thenReturn(new GroupContracts.GroupView(group,sessionId,"space","external","life","51","active",1,new GroupContracts.Subject("group",group)));
        var session=org.mockito.Mockito.mock(SessionView.class);org.mockito.Mockito.when(session.getTenantId()).thenReturn(11L);org.mockito.Mockito.when(session.getSessionId()).thenReturn(sessionId);org.mockito.Mockito.when(session.getGroupBindingId()).thenReturn(Long.parseLong(group));org.mockito.Mockito.when(session.getIntegrationId()).thenReturn("integration");org.mockito.Mockito.when(session.getBusinessScopeId()).thenReturn("51");
        when(sessions.requireForSystem("11",sessionId)).thenReturn(session);
    }
    private GroupHistoryMapper.Snapshot snapshot(long sequence) throws Exception {
        var now=Instant.parse("2026-10-22T01:00:00Z");
        var data=Map.of("message_id","source:"+sequence,"message_version",1,"content","公开消息"+sequence,"role","user","created_at",now.toString(),"references",List.of());
        return new GroupHistoryMapper.Snapshot(11L,"session","output:"+sequence,null,sequence,"message.delta",json.writeValueAsString(Map.of("event","message.delta","data",data)),now,sequence,now);
    }

    @Test void unsafeInputIdentityIsNotDisclosedAndStreamPreflightStopsEventSource() {
        Instant now=Instant.parse("2026-10-22T01:00:00Z");String sensitive="password=syntheticsecret";
        var output=com.skillforge.core.chat.session.SessionOutboxEntry.builder().tenantId(11L).sessionId("session")
                .eventId("safe-event-id").sessionSequence(2L).inputId(sensitive).rootInputId("safe-root").createdAt(now)
                .payload(Map.of("event","message.delta","data",Map.of("message_id",sensitive,"message_version",1,
                        "role","user","content","公开回复"))).build();
        when(mailbox.resolveOutputSequence("11","session","before")).thenReturn(1L);
        when(mailbox.listOutputsAfterSequence("11","session",1,3)).thenReturn(List.of(output));
        when(mailbox.listOutputsAfterSequence("11","session",1,1)).thenReturn(List.of(output));
        var failure=assertThrows(ApiException.class,()->history.events("41","31","before",2));
        assertEquals(GroupHistoryService.IDENTITY_UNDISCLOSABLE,failure.getCode());assertFalse(failure.isRetryable());
        assertFalse(failure.getMessage().contains(sensitive));
        assertEquals(HttpStatus.NO_CONTENT,history.stream("41","31","before",null).getStatusCode());
        verify(mailbox,never()).observeOutputs(anyString(),anyString());
        assertEquals(sensitive,output.getInputId());assertEquals("safe-event-id",output.getEventId());
    }

    @Test void revokedMemberCannotObserveIdentityBlockDuringStreamPreflight() {
        var output=com.skillforge.core.chat.session.SessionOutboxEntry.builder().tenantId(11L).sessionId("session")
                .eventId("safe-event").sessionSequence(2L).inputId("password=syntheticsecret")
                .createdAt(Instant.parse("2026-10-22T01:00:00Z"))
                .payload(Map.of("event","message.delta","data",Map.of("message_id","safe","message_version",1,
                        "role","user","content","公开"))).build();
        when(mailbox.resolveOutputSequence("11","session","before")).thenReturn(1L);
        when(mailbox.listOutputsAfterSequence("11","session",1,1)).thenReturn(List.of(output));
        when(groups.requireMember("11","integration","41",31,false)).thenReturn(null)
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN,"member_left"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->history.stream("41","31","before",null)).getStatus());
        verify(mailbox,never()).observeOutputs(anyString(),anyString());
    }

    @Test void nestedAndArrayIdentitiesAreCheckedBeforeEventDisclosure() {
        for(var extra:List.<Map<String,Object>>of(
                Map.of("attachment_ids",List.of("safe","password=syntheticsecret")),
                Map.of("tool_wait",Map.of("action_id","password=syntheticsecret")),
                Map.of("author",Map.of("source_ref","password=syntheticsecret")),
                Map.of("card",Map.of("sourceId","password=syntheticsecret")))) {
            var data=new java.util.LinkedHashMap<String,Object>(extra);
            data.put("message_id","safe");data.put("message_version",1);data.put("role","user");data.put("content","公开");
            var output=com.skillforge.core.chat.session.SessionOutboxEntry.builder().tenantId(11L).sessionId("session")
                    .eventId("safe-event").sessionSequence(2L).createdAt(Instant.parse("2026-10-22T01:00:00Z"))
                    .payload(Map.of("event","message.delta","data",data)).build();
            var failure=assertThrows(ApiException.class,()->history.event(output));
            assertEquals(GroupHistoryService.IDENTITY_UNDISCLOSABLE,failure.getCode());
        }
    }

    @Test void historyRejectsSensitiveMessageIdentityWithoutChangingSnapshotCursor() throws Exception {
        var now=Instant.parse("2026-10-22T01:00:00Z");String secret="password=syntheticsecret";
        var data=Map.of("message_id",secret,"message_version",1,"content","公开回复","role","user","references",List.of());
        var row=new GroupHistoryMapper.Snapshot(11L,"session","output:3",null,3L,"message.delta",
                json.writeValueAsString(Map.of("event","message.delta","data",data)),now,3L,now);
        when(mapper.messagePage(11,"session",5,Long.MAX_VALUE,2)).thenReturn(List.of(row));
        var failure=assertThrows(ApiException.class,()->history.history("41","31",null,1));
        assertEquals(GroupHistoryService.IDENTITY_UNDISCLOSABLE,failure.getCode());assertFalse(failure.isRetryable());
        assertFalse(failure.getMessage().contains(secret));
        verify(mailbox).resolveOutputSequence("11","session","output:5");
        verify(groups,atLeast(2)).requireMember("11","integration","41",31,false);
    }

    @Test void paginationKeepsOriginalThroughEvenAfterNewOutputsCommit() throws Exception {
        when(mapper.messagePage(11,"session",5,Long.MAX_VALUE,2)).thenReturn(List.of(snapshot(3),snapshot(2)));
        var first=history.history("41","31",null,1);assertTrue(first.hasMore());assertEquals("source:3",first.items().getFirst().messageId());
        when(mailbox.committedCursor("11","session")).thenReturn("output:9");
        when(mapper.messagePage(11,"session",5,3,2)).thenReturn(List.of(snapshot(2)));
        var next=history.history("41","31",first.nextCursor(),1);
        assertEquals("output:5",next.eventCursor());assertEquals("source:2",next.items().getFirst().messageId());assertFalse(next.hasMore());
        verify(mapper).messagePage(11,"session",5,3,2);verify(mailbox,times(1)).committedCursor("11","session");
    }

    @Test void historyCursorIsBoundToGroupReaderAndPageSize() throws Exception {
        when(mapper.messagePage(11,"session",5,Long.MAX_VALUE,2)).thenReturn(List.of(snapshot(3),snapshot(2)));
        var first=history.history("41","31",null,1);bind("42","other-session");
        assertEquals(409,assertThrows(ApiException.class,()->history.history("42","31",first.nextCursor(),1)).getStatus().value());
        assertEquals(409,assertThrows(ApiException.class,()->history.history("41","32",first.nextCursor(),1)).getStatus().value());
        assertEquals(409,assertThrows(ApiException.class,()->history.history("41","31",first.nextCursor(),2)).getStatus().value());
    }

    @Test void membershipRevocationRejectsOldHistoryCursorAndExistingSubscriptionAccess() throws Exception {
        when(mapper.messagePage(11,"session",5,Long.MAX_VALUE,2)).thenReturn(List.of(snapshot(3),snapshot(2)));
        var first=history.history("41","31",null,1);var access=history.authorize(ServicePrincipalContext.get(),"41","31");
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"member_left")).when(groups).requireMember("11","integration","41",31,false);
        assertEquals(403,assertThrows(ApiException.class,()->history.history("41","31",first.nextCursor(),1)).getStatus().value());
        assertEquals(403,assertThrows(ApiException.class,()->history.recheck(access)).getStatus().value());
    }

    @Test void credentialRevocationIsRecheckedForCapturedStreamIdentity() {
        var access=history.authorize(ServicePrincipalContext.get(),"41","31");
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"principal_revoked")).when(integrations).requirePrincipal(access.principal(),GroupHistoryService.READ_SCOPE);
        assertThrows(ApiException.class,()->history.recheck(access));
    }

    @Test void pageSizeOnlyDefaultsWhenAbsentAndNeverClamps() {
        assertEquals(20,GroupHistoryService.parsePageSize(null));assertEquals(200,GroupHistoryService.parsePageSize("200"));
        for(String invalid:List.of("","0","-1","201","1.0","01"," 1","999999999999"))
            assertEquals(400,assertThrows(ApiException.class,()->GroupHistoryService.parsePageSize(invalid)).getStatus().value());
    }

    @Test void malformedHistoryCursorAndConflictingStreamCursorsFailBeforeStreaming() {
        assertEquals(409,assertThrows(ApiException.class,()->history.history("41","31","not-base64",20)).getStatus().value());
        assertEquals(409,assertThrows(ApiException.class,()->history.history("41","31","bnVsbA",20)).getStatus().value());
        assertEquals(400,assertThrows(ApiException.class,()->history.stream("41","31","one","two")).getStatus().value());
        verify(mailbox,never()).observeOutputs(anyString(),anyString());
    }
}
