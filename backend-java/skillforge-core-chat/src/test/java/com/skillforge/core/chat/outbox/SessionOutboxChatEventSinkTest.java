package com.skillforge.core.chat.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.constant.ChatConstants;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.shared.tracing.TraceContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 持久事件只有已提交 Outbox 读取路径；在线 sink 只承担连接控制事件。 */
class SessionOutboxChatEventSinkTest {
    @Test void durableOutputIsPersistedOnceWithoutAnotherLiveDeliveryPath() {
        var mailbox=mock(SessionMailboxService.class);var live=mock(ChatEventSink.class);
        var trace=trace("input-1","source-1");
        var persisted=SessionOutboxEntry.builder().sessionSequence(4L);
        when(mailbox.appendOutput(eq("7"),eq("session-1"),any(),eq("message.delta"),any(),eq(trace)))
                .thenAnswer(call -> persisted.eventId(call.getArgument(2)).build());
        var sink=new SessionOutboxChatEventSink(mailbox,"7","session-1",trace,live);
        sink.send("message.delta",Map.of("content","hello"));
        verify(mailbox).appendOutput(eq("7"),eq("session-1"),startsWith("output:"),eq("message.delta"),any(),eq(trace));
        verifyNoInteractions(live);
    }

    @Test void runtimeOutputKeepsExplicitRunAndStableSourceIdentityAcrossSinks() {
        var mailbox=mock(SessionMailboxService.class);
        var left=new SessionOutboxChatEventSink(mailbox,"7","session-1",trace("input-1","source-1"),null);
        var replay=new SessionOutboxChatEventSink(mailbox,"7","session-1",trace("input-1","source-1"),null);
        var other=left.sourceBound(trace("input-2","source-2"));
        left.sendRuntime("execution.completed",Map.of("run_id","untrusted","runtime_event_id","source-1"),"run-1");
        replay.sendRuntime("execution.completed",Map.of("runtime_event_id","source-1"),"run-1");
        other.sendRuntime("execution.completed",Map.of("runtime_event_id","source-2"),"run-1");
        var ids=ArgumentCaptor.forClass(String.class);
        verify(mailbox,times(3)).appendOutput(eq("7"),eq("session-1"),ids.capture(),eq("execution.completed"),any(),any(),eq("run-1"));
        assertEquals(ids.getAllValues().get(0),ids.getAllValues().get(1));
        assertNotEquals(ids.getAllValues().get(0),ids.getAllValues().get(2));
    }

    @Test void connectionErrorAndHistoryControlEventsDoNotConsumeOutboxSequence() {
        var mailbox=mock(SessionMailboxService.class);var live=mock(ChatEventSink.class);
        var sink=new SessionOutboxChatEventSink(mailbox,"7","session-1",trace("input-1","source-1"),live);
        sink.send(ChatConstants.EVENT_CONNECTED,Map.of("session_id","session-1"));
        var retired=Map.<String,Object>of("retired_event_count",12L);
        sink.send("history.retired",retired);sink.error(null,null,true);
        verifyNoInteractions(mailbox);verify(live).send(eq(ChatConstants.EVENT_CONNECTED),any());
        verify(live).send("history.retired",retired);verify(live).send(eq(ChatConstants.EVENT_ERROR),any());
        verify(live).complete();
    }

    @Test void collectedCommandsKeepSingletonIdentityWithoutInventingPersistenceAcknowledgments() {
        var mailbox = mock(SessionMailboxService.class);
        var trace = trace("input-1", "source-1");
        var commands = new java.util.ArrayList<com.skillforge.core.chat.session.SessionOutboxOutput>();
        var collecting = SessionOutboxChatEventSink.collecting("7", "session-1", trace, commands::add);
        collecting.sendRuntime("message.delta", Map.of("content", "hello", "runtime_event_id", "source-1"), "run-1");
        assertEquals(1, commands.size());
        var command = commands.getFirst();
        assertEquals("run-1", command.runtimeRunId());
        assertEquals(trace, command.trace());
        verifyNoInteractions(mailbox);
        new SessionOutboxChatEventSink(mailbox, "7", "session-1", trace, null)
                .sendRuntime("message.delta", Map.of("content", "hello", "runtime_event_id", "source-1"), "run-1");
        verify(mailbox).appendOutput("7", "session-1", command.eventId(), command.eventType(),
                command.payload(), command.trace(), command.runtimeRunId());
    }

    /** 已提交来源的因果字段，后续重放不重新生成身份。 */
    private static TraceContext trace(String input,String source) {
        return new TraceContext(input,"root-1","trace-1","span-1",source,null);
    }
}
