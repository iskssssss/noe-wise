package com.skillforge.institution.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;

/** 派发资格必须引用原Run已采纳的精确公开final，而不是任意Outbox文本。 */
class GroupHistoryFinalMessagesTest {
    private final ExecutionStore store=mock(ExecutionStore.class);
    private final GroupBindingService groups=mock(GroupBindingService.class);
    private final GroupHistoryMapper mapper=mock(GroupHistoryMapper.class);
    private final GroupHistoryService history=mock(GroupHistoryService.class);
    private final AgentRuntimeExecutionProjection projection=mock(AgentRuntimeExecutionProjection.class);
    private final ExecutionStore.Run run=mock(ExecutionStore.Run.class);
    private final SessionOutboxEntry output=SessionOutboxEntry.builder().build();
    private GroupHistoryFinalMessages finals;
    private RunIdentity identity;

    @BeforeEach void setup(){
        TenantContext.setTenantId("11");var tenant=new TenantId("11");var session=new SessionRef(tenant,"session");var group=new GroupBindingRef(tenant,"41");
        var input=new InputId("input");var reference=new RunRef(tenant,"run");
        identity=new RunIdentity(new SessionOwner(session,SessionSubject.group(group),SessionTarget.group(group)),new AgentId("61"),input,reference,
                new TraceEnvelope("trace","span",input,Optional.empty()),new ResultRef("identity"),new RunRequester.EndUser(new EndUserId(31)),
                Optional.of(new SourceMessageRef(session,"source")),new RunOriginRef(tenant,"group-trigger:trigger"));
        when(run.identity()).thenReturn(identity);when(run.status()).thenReturn(RunStatus.COMPLETED);when(run.phase()).thenReturn(ExecutionStore.Phase.FINISHED);
        when(store.load(reference)).thenReturn(Optional.of(run));
        when(groups.requireActive("11","integration","41")).thenReturn(new GroupContracts.GroupView("41","session","space","external","life","51","active",1,new GroupContracts.Subject("group","41")));
        var now=Instant.parse("2026-10-22T01:00:00Z");
        var snapshot=new GroupHistoryMapper.Snapshot(11L,"session","event","run",1,"message.delta","{}",now,1,now);
        when(mapper.exactMessage(11,"session","message",1)).thenReturn(snapshot);when(history.output(snapshot)).thenReturn(output);
        when(projection.publicMessage(output)).thenReturn(new ChatMessageRecord("message","assistant","正式答复",now,Map.of("state","completed","phase","final"),1,List.of()));
        finals=new GroupHistoryFinalMessages(store,groups,mapper,history,projection);
    }
    @AfterEach void clear(){TenantContext.clear();}

    @Test void returnsOriginalIdentityAndExactAdoptedPublicMessage(){
        var result=finals.requireFinal("11","integration","41","run","message",1);
        assertSame(identity,result.identity());assertEquals("正式答复",result.content());
        verify(groups).requireServiceAgent("11","integration","41",61);
    }
    @Test void waitingFailedAndDifferentMessageVersionsAreNeverFinals(){
        when(run.phase()).thenReturn(ExecutionStore.Phase.WAITING);assertThrows(ApiException.class,()->finals.requireFinal("11","integration","41","run","message",1));
        when(run.phase()).thenReturn(ExecutionStore.Phase.FINISHED);when(run.status()).thenReturn(RunStatus.FAILED);assertThrows(ApiException.class,()->finals.requireFinal("11","integration","41","run","message",1));
        when(run.status()).thenReturn(RunStatus.COMPLETED);assertThrows(ApiException.class,()->finals.requireFinal("11","integration","41","run","message",2));
    }
    @Test void currentPublicationRevocationAndPublicPolicyFailureDenyDelivery(){
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"agent_revoked")).when(groups).requireServiceAgent("11","integration","41",61);
        assertThrows(ApiException.class,()->finals.requireFinal("11","integration","41","run","message",1));
        doReturn(null).when(groups).requireServiceAgent("11","integration","41",61);
        when(projection.publicMessage(output)).thenReturn(new ChatMessageRecord("message","assistant","安全拒绝",Instant.now(),Map.of("state","failed","phase","final"),1,List.of()));
        assertThrows(ApiException.class,()->finals.requireFinal("11","integration","41","run","message",1));
    }
}
