package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 容器断连、写失败与终止回调的确定性验证，不依赖浏览器刷新时序。 */
class SseEventSinkTest {
    @Test
    void servletErrorPreventsAllFurtherTransportAccess() throws Exception {
        var emitter=mock(SseEmitter.class);
        var sink=new SseEventSink(emitter,new ObjectMapper());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> callback=ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(callback.capture());
        callback.getValue().accept(new IOException("client disconnected"));

        assertTrue(sink.isClosed());
        assertDoesNotThrow(()->sink.error("unavailable","EVENT_STREAM_UNAVAILABLE",true));
        sink.complete();
        assertThrows(IllegalStateException.class,sink::heartbeat);
        verify(emitter,never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter,never()).complete();
    }

    @Test
    void writeFailureIsLeftToTheContainerWithoutSecondCompletion() throws Exception {
        var emitter=mock(SseEmitter.class);
        doThrow(new IOException("client disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var sink=new SseEventSink(emitter,new ObjectMapper());

        assertThrows(IllegalStateException.class,()->sink.send("message.delta",Map.of("event_id","frozen-1")));
        assertTrue(sink.isClosed());
        assertDoesNotThrow(()->sink.error("unavailable","EVENT_STREAM_UNAVAILABLE",true));
        sink.complete();
        verify(emitter,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter,never()).complete();
    }

    @Test
    void completionRaceWithClosedAsyncContextDoesNotEscape() {
        var emitter=mock(SseEmitter.class);
        doThrow(new IllegalStateException("AsyncContext already closed")).when(emitter).complete();
        var sink=new SseEventSink(emitter,new ObjectMapper());

        assertDoesNotThrow(sink::complete);
        assertTrue(sink.isClosed());
        sink.complete();
        verify(emitter,times(1)).complete();
    }

    @Test
    void activeTransportReportsBusinessErrorBeforeCompleting() throws Exception {
        var emitter=mock(SseEmitter.class);
        var sink=new SseEventSink(emitter,new ObjectMapper());

        sink.error("unavailable","EVENT_STREAM_UNAVAILABLE",true);

        var order=inOrder(emitter);
        order.verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        order.verify(emitter).complete();
        assertTrue(sink.isClosed());
    }

    @Test
    void timeoutCompletesOnlyOnce() {
        var emitter=mock(SseEmitter.class);
        var sink=new SseEventSink(emitter,new ObjectMapper());
        var callback=ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(callback.capture());
        callback.getValue().run();
        sink.complete();
        assertTrue(sink.isClosed());
        verify(emitter,times(1)).complete();
    }

    @Test
    void activeEventRetainsItsFrozenCursor() throws Exception {
        var emitter=mock(SseEmitter.class);
        var sink=new SseEventSink(emitter,new ObjectMapper());
        sink.send("message.delta",Map.of("event_id","frozen-1","content","hello"));
        var event=ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(event.capture());
        String wire=event.getValue().build().stream().map(item->String.valueOf(item.getData())).reduce("",String::concat);
        assertTrue(wire.contains("id:frozen-1"));
        assertTrue(wire.contains("event:message.delta"));
        assertTrue(wire.contains("\"content\":\"hello\""));
        assertFalse(sink.isClosed());
    }
}
