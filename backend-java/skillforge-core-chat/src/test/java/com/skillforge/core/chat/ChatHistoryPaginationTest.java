package com.skillforge.core.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.skillforge.core.agent.AgentQueryService;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatHistoryPaginationTest {
    private final MemoryService memory=mock(MemoryService.class);
    private final SessionService sessions=mock(SessionService.class);
    private final ChatRuntimeHistory runtime=mock(ChatRuntimeHistory.class);
    private final SessionMailboxService mailbox=mock(SessionMailboxService.class);
    private final SessionView session=mock(SessionView.class);
    private final ChatSessionService service=new ChatSessionService(mock(AgentQueryService.class),memory,sessions,mailbox,runtime,
            mock(ChatTeamGateway.class),new PublicOutputGuard("./data/artifacts"));
    @BeforeEach void setup(){
        when(sessions.require("7","s")).thenReturn(session);
        when(memory.listAll("7","s")).thenReturn(List.of());
        when(runtime.read(session)).thenReturn(new ChatHistoryResponse(
                "s",List.of(),null,List.of(),List.of(), ChatHistoryResponse.Collaboration.empty()));
    }
    private ChatMessageRecord message(int id){return new ChatMessageRecord(String.format("m%03d",id),"user","content",Instant.EPOCH,Map.of());}
    @Test void planSnapshotsFollowOnlyTheCurrentPageInputIds() {
        var old=new ChatMessageRecord("old-user","user","旧轮次",Instant.EPOCH,Map.of("root_input_id","old-input"));
        var latest=new ChatMessageRecord("new-user","user","新轮次",Instant.EPOCH.plusSeconds(1),Map.of("root_input_id","new-input"));
        when(memory.listAll("7","s")).thenReturn(List.of(old,latest));
        when(runtime.workPlans(eq(session),any())).thenAnswer(call->{
            java.util.Set<String> ids=call.getArgument(1);
            return ids.contains("old-input")?List.of(Map.<String,Object>of("work_id","old-input")):List.of(Map.<String,Object>of("work_id","new-input"));
        });
        assertEquals("new-input",service.history("7","s",1,null).workPlans().getFirst().get("work_id"));
        assertEquals("old-input",service.history("7","s",1,"new-user").workPlans().getFirst().get("work_id"));
        verify(runtime).workPlans(session,java.util.Set.of("old-user","old-input"));
        verify(runtime).workPlans(session,java.util.Set.of("new-user","new-input"));
    }

    @Test void latestPageDefaultsToThirtyAndTiedTimestampsHaveStableCursor(){
        var messages=IntStream.range(0,65).mapToObj(this::message).toList();
        when(memory.listAll("7","s")).thenReturn(messages.reversed());
        var first=service.history("7","s");assertEquals(30,first.messages().size());
        assertEquals("m035",first.messages().getFirst().messageId());assertEquals("m064",first.messages().getLast().messageId());
        assertTrue(first.hasMore());assertEquals("m035",first.nextBeforeMessageId());
        var second=service.history("7","s",30,first.nextBeforeMessageId());
        assertEquals("m005",second.messages().getFirst().messageId());assertEquals("m034",second.messages().getLast().messageId());
        var third=service.history("7","s",30,second.nextBeforeMessageId());
        assertEquals(5,third.messages().size());assertFalse(third.hasMore());assertNull(third.nextBeforeMessageId());
        assertTrue(service.history("7","s",30,"m000").messages().isEmpty());
    }
    @Test void runtimeOverridesDuplicateAndRetainsAllProcessMetadata(){
        var duplicate=message(1);when(memory.listAll("7","s")).thenReturn(List.of(duplicate,message(2)));
        var authoritative=new ChatMessageRecord(duplicate.messageId(),"assistant","runtime",Instant.EPOCH,Map.of());
        var process=new ChatHistoryResponse.ProcessItem("p","r","i","a","tool",1,"completed",com.skillforge.core.chat.contract.ProcessPresentation.of("处理任务","完成"),Instant.EPOCH,Instant.EPOCH,"r","i",0,1,"7",null, null);
        var activity=new ChatHistoryResponse.ActivityItem("a","tool","completed",null,null,"m001",Instant.EPOCH);
        var pending=Map.<String,Object>of("execution_id","r");
        when(runtime.read(session)).thenReturn(new ChatHistoryResponse(
                "s",List.of(authoritative),List.of(pending),List.of(activity),List.of(process),
                ChatHistoryResponse.Collaboration.empty()));
        var page=service.history("7","s",1,"m002");
        assertEquals(List.of(authoritative),page.messages());assertEquals(List.of(pending),page.pendingInteractions());
        assertEquals(List.of(process),page.processes());assertEquals(List.of(activity),page.activities());
    }
    @Test void newMessagesDoNotShiftOlderPageAndTimePrecedesId(){
        var messages=new ArrayList<>(List.of(message(0),message(1),message(2)));
        when(memory.listAll("7","s")).thenAnswer(ignored->List.copyOf(messages));
        var first=service.history("7","s",2,null);
        messages.add(new ChatMessageRecord("earlier-id","user","new",Instant.EPOCH.plusSeconds(1),Map.of()));
        assertEquals(List.of(message(0)),service.history("7","s",2,first.nextBeforeMessageId()).messages());
        assertEquals("earlier-id",service.history("7","s",1,null).messages().getFirst().messageId());
    }
    @Test void rejectsInvalidLimitCursorAndUnauthorizedSession(){
        assertThrows(ApiException.class,()->service.history("7","s",0,null));
        assertThrows(ApiException.class,()->service.history("7","s",101,null));
        verifyNoInteractions(sessions);
        assertThrows(ApiException.class,()->service.history("7","s",30,"another-session-message"));
        reset(memory,runtime);when(sessions.require("7","foreign")).thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"拒绝"));
        assertThrows(ApiException.class,()->service.history("7","foreign",30,null));verifyNoInteractions(memory,runtime);
    }
    @Test void emptyPageAndExactLimitHaveNoNextCursor(){
        assertFalse(service.history("7","s").hasMore());
        when(memory.listAll("7","s")).thenReturn(List.of(message(0),message(1)));
        var page=service.history("7","s",2,null);assertFalse(page.hasMore());assertNull(page.nextBeforeMessageId());
    }
    @Test void capturesCommittedCursorAfterAuthorizationBeforeReadingSnapshot(){
        when(mailbox.committedCursor("7","s")).thenReturn("event-17");
        var page=service.history("7","s");
        assertEquals("event-17",page.eventCursor());
        var order=inOrder(sessions,mailbox,runtime,memory);
        order.verify(sessions).require("7","s");
        order.verify(mailbox).committedCursor("7","s");
        order.verify(runtime).read(session);
        order.verify(memory).listAll("7","s");
    }

    @Test void concurrentOutputDuringHistoryReadCannotAdvanceItsResumeCursorPastTheSnapshotStart(){
        var cursor=new java.util.concurrent.atomic.AtomicReference<>("event-17");
        when(mailbox.committedCursor("7","s")).thenAnswer(call->cursor.get());
        when(runtime.read(session)).thenAnswer(call->{
            cursor.set("event-18");
            return new ChatHistoryResponse("s",List.of(),null,List.of(),List.of(),ChatHistoryResponse.Collaboration.empty());
        });
        assertEquals("event-17",service.history("7","s").eventCursor(),"读取期间提交的新事件必须留给订阅从原起点补齐");
        verify(mailbox,times(1)).committedCursor("7","s");
    }

    @Test void proactiveAssistantRemainsVisibleAndUnsafeMemoryIsCheckedWithCurrentPublicPolicy(){
        var safe=new ChatMessageRecord("proactive","assistant","任务已排队",Instant.EPOCH,Map.of("card",Map.of("title","已排队")));
        var unsafe=new ChatMessageRecord("unsafe","assistant","Authorization: Bearer actual-secret-credential",
                Instant.EPOCH.plusSeconds(1),Map.of("root_input_id","root-1","card",Map.of("secret","private")),7,List.of());
        when(memory.listAll("7","s")).thenReturn(List.of(safe,unsafe));
        var history=service.history("7","s");
        assertEquals(safe,history.messages().getFirst());
        var protectedMessage=history.messages().getLast();
        assertEquals("unsafe",protectedMessage.messageId());assertEquals(7,protectedMessage.messageVersion());
        assertEquals(unsafe.createdAt(),protectedMessage.createdAt());
        assertEquals(PublicOutputGuard.SAFE_MESSAGE,protectedMessage.content());
        assertEquals(Map.of("root_input_id","root-1","state","failed"),protectedMessage.metadata());
        verify(memory,never()).upsert(any(),any(),any(),any(),any(),any(),any());
    }
}
