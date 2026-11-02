package com.skillforge.core.chat.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.chat.observation.SessionObservationView.Content;
import com.skillforge.core.chat.persistence.SessionTraceMapper;
import com.skillforge.core.chat.tracing.SessionTraceEventRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 已授权会话事件的受控正文读取；执行树统一由 TrajectoryProjectionService 组合。 */
@Service
public class SessionTraceService {
    private static final String FULL = "FULL";
    private static final String REDACTED = "REDACTED";
    private static final String EVENT_REFERENCE_PREFIX = "session-event:";
    private static final String MODEL_REASONING_EVENT = "agent.model-reasoning";
    private static final String MODEL_RESULT_EVENT = "agent.model-result";
    private static final Set<String> MODEL_REASONING_PUBLIC_FIELDS = Set.of(
            "attemptId", "batchOrdinal", "sourceSequenceFrom", "sourceSequenceThrough");
    private static final Set<String> MODEL_RESULT_REASONING_FIELDS = Set.of(
            "reasoningContent", "reasoning_content");

    private final SessionTraceMapper traceMapper;
    private final ObjectMapper objectMapper;

    public SessionTraceService(SessionTraceMapper traceMapper, ObjectMapper objectMapper) {
        this.traceMapper = traceMapper;
        this.objectMapper = objectMapper;
    }

    /** 调用方先校验当前读取权及根输入归属；引用不能跨租户、跨 Session 或读取私有账本。 */
    @Transactional(readOnly = true)
    public Optional<Content> content(String tenantId, String sessionId, String contentRef) {
        requireText(tenantId, "tenantId");
        requireText(sessionId, "sessionId");
        if (contentRef == null || !contentRef.startsWith(EVENT_REFERENCE_PREFIX)) {
            return Optional.empty();
        }
        String eventId = contentRef.substring(EVENT_REFERENCE_PREFIX.length());
        if (eventId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(traceMapper.findEvent(tenantId, sessionId, eventId))
                .map(row -> new Content(contentRef, contentAvailability(row),
                        row.getPayloadSchemaId(), publicPayload(row)));
    }

    /** 上下文、提示词和私有推理不能通过公开正文或查看 JSON 绕过平台独立诊断授权。 */
    private Object publicPayload(SessionTraceEventRow row) {
        if (privateModelContext(row)) {
            return Map.of("redactionReason", "private_model_context");
        }
        if (MODEL_REASONING_EVENT.equals(row.getEventType())) {
            return modelReasoningMetadata(row);
        }
        if (MODEL_RESULT_EVENT.equals(row.getEventType())) {
            return modelResultWithoutReasoning(row);
        }
        try {
            return objectMapper.readValue(row.getPayloadCanonicalJson(), Object.class);
        } catch (JsonProcessingException invalidCanonicalPayload) {
            throw new IllegalStateException("committed session event payload is invalid", invalidCanonicalPayload);
        }
    }

    private Map<String, Object> modelReasoningMetadata(SessionTraceEventRow row) {
        try {
            JsonNode payload = objectMapper.readTree(row.getPayloadCanonicalJson());
            if (payload == null || !payload.isObject()) {
                return Map.of();
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            for (String field : MODEL_REASONING_PUBLIC_FIELDS) {
                JsonNode value = payload.get(field);
                if (value != null && value.isTextual()) {
                    metadata.put(field, value.textValue());
                }
            }
            return metadata;
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private Object modelResultWithoutReasoning(SessionTraceEventRow row) {
        try {
            JsonNode payload = objectMapper.readTree(row.getPayloadCanonicalJson());
            if (payload == null || !payload.isObject()) {
                return Map.of();
            }
            ObjectNode publicPayload = ((ObjectNode) payload).deepCopy();
            MODEL_RESULT_REASONING_FIELDS.forEach(publicPayload::remove);
            return objectMapper.convertValue(publicPayload, Object.class);
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private static String contentAvailability(SessionTraceEventRow event) {
        return privateModelContext(event) || MODEL_REASONING_EVENT.equals(event.getEventType())
                || MODEL_RESULT_EVENT.equals(event.getEventType()) ? REDACTED : FULL;
    }

    private static boolean privateModelContext(SessionTraceEventRow event) {
        return "agent.context-snapshot".equals(event.getEventType())
                || "agent.context-view".equals(event.getEventType())
                || "agent.preparation-instructions".equals(event.getEventType());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
