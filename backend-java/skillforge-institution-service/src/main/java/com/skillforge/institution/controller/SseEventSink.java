package com.skillforge.institution.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.constant.ChatConstants;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 基于 SseEmitter 的对话事件出口。
 *
 * <p>把持久事件按 SSE 线格式写出发射器；容器关闭或写失败后停止访问连接，
 * 业务故障仅在传输仍有效时写出 error 事件。
 */
public class SseEventSink implements ChatEventSink {

    /** SSE 发射器。 */
    private final SseEmitter emitter;

    /** Jackson 对象映射器。 */
    private final ObjectMapper objectMapper;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 构造事件出口。
     *
     * @param emitter      SSE 发射器
     * @param objectMapper Jackson 对象映射器
     */
    public SseEventSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(ignored -> closed.set(true));
        emitter.onTimeout(this::complete);
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void send(String event, Map<String, Object> data) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("SSE 事件序列化失败", error);
        }
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(payload);
        Object eventId = data == null ? null : data.get("event_id");
        if (eventId != null && !String.valueOf(eventId).isBlank()) {
            builder.id(String.valueOf(eventId));
        }
        write(builder);
    }

    /** 心跳是传输注释，没有业务事件身份或序号。 */
    public void heartbeat() {
        write(SseEmitter.event().comment("heartbeat"));
    }

    private void write(SseEmitter.SseEventBuilder event) {
        if (closed.get()) throw new IllegalStateException("SSE 连接已关闭");
        try {
            emitter.send(event);
        } catch (IOException error) {
            // 写失败由容器结束 AsyncContext，不能随后再次 complete 或写 error。
            closed.set(true);
            throw new IllegalStateException("SSE 连接写入失败", error);
        } catch (IllegalStateException error) {
            closed.set(true);
            throw error;
        }
    }

    @Override
    public void error(String message, String errorCode, boolean retryable) {
        if (closed.get()) return;
        try {
            send(ChatConstants.EVENT_ERROR, Map.of(
                    "error_code", errorCode,
                    "message", message == null ? "对话执行失败" : message,
                    "retryable", retryable));
        } catch (Exception ignored) {
            // 连接已断开时忽略
        }
        complete();
    }

    @Override
    public void complete() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            emitter.complete();
        } catch (IllegalStateException alreadyClosed) {
            // 容器可能先结束 AsyncContext、后通知回调；终止通知不再触碰失效上下文。
        }
    }
}
