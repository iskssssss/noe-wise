package com.skillforge.core.chat.trajectory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.ChatRuntimeHistory;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.knowledge.KnowledgeObservationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCapabilityInspector;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

/** 从已公开的完成事件读取唯一引用片段；引用身份本身不授予来源权限。 */
public class ChatCitationReadService {
    private final TrajectoryRepository repository;
    private final SessionService sessions;
    private final ChatRuntimeHistory history;
    private final SessionObservationAuthorizer authorizer;
    private final KnowledgeObservationAccess knowledge;
    private final ObservationReadAudit audit;
    private final PublicOutputGuard guard;
    private final ObjectMapper json;
    private final ExecutionCapabilityInspector inspector;

    public ChatCitationReadService(TrajectoryRepository repository, SessionService sessions, ChatRuntimeHistory history, SessionObservationAuthorizer authorizer,
            KnowledgeObservationAccess knowledge, ObservationReadAudit audit, PublicOutputGuard guard, ObjectMapper json,
            @Value("${agent-runtime.max-payload-code-units:8388608}") int maxCodeUnits,
            @Value("${agent-runtime.max-payload-nodes:100000}") int maxNodes,
            @Value("${agent-runtime.max-payload-depth:128}") int maxDepth) {
        this.repository = repository; this.sessions = sessions; this.history = history; this.authorizer = authorizer; this.knowledge = knowledge;
        this.audit = audit; this.guard = guard; this.json = json;
        this.inspector = new ExecutionCapabilityInspector(maxCodeUnits, maxNodes, maxDepth);
    }

    public record Citation(String label, @JsonProperty("source_version") String sourceVersion, String text) { }

    public Citation read(String session, String message, String citation) {
        if (ChannelContext.getChannelId() != null || ServicePrincipalContext.get() != null) throw unavailable();
        if (session == null || session.isBlank() || message == null || message.isBlank()
                || citation == null || !citation.matches("k[0-9a-f]{16}")) throw unavailable();
        var scope = authorizer.tenant();
        long tenant = scope.tenantId();
        authorizer.requireSession(scope, tenant, session);
        var current = sessions.require(Long.toString(tenant), session);
        var visible = history.message(current, message).orElseThrow(ChatCitationReadService::unavailable);
        if (!message.equals(visible.messageId()) || !"assistant".equals(visible.role())
                || !"completed".equals(visible.state()) || visible.metadata() == null) throw unavailable();
        var publicCitations = json.valueToTree(visible.metadata().get("citations"));
        if (!publicCitations.isArray()) throw unavailable();
        int publicMatches = 0;
        for (var entry : publicCitations) if (citation.equals(entry.path("citation_id").asText())) publicMatches++;
        if (publicMatches != 1) throw unavailable();
        var event = repository.citationEvent(tenant, session, message);
        if (event == null || !AgentExecutionProgram.finishedEventId(new RunRef(new TenantId(Long.toString(tenant)), event.runId())).value().equals(message)) throw unavailable();
        var completed = parse(event.canonicalJson());
        if (!"COMPLETED".equals(completed.path("status").asText()) || !completed.path("text").isTextual()
                || !visible.content().equals(completed.path("text").textValue())
                || !completed.path("evidence").isArray() || !event.agentId().equals(completed.path("agentId").asText())
                || !event.inputId().equals(completed.path("inputId").asText())
                || !event.rootInputId().equals(completed.path("rootInputId").asText())
                || !event.traceId().equals(completed.path("traceId").asText())
                || !event.spanId().equals(completed.path("spanId").asText())) throw unavailable();
        var selected = new java.util.ArrayList<JsonNode>();
        for (var entry : completed.path("evidence")) if (citation.equals(entry.path("citationId").asText())) selected.add(entry);
        if (selected.size() != 1) throw unavailable();
        var evidence = selected.getFirst();
        String item = required(evidence, "itemId"), source = required(evidence, "sourceId"), version = required(evidence, "sourceVersion");
        var publicCitation = java.util.stream.StreamSupport.stream(publicCitations.spliterator(), false)
                .filter(value -> citation.equals(value.path("citation_id").asText())).findFirst().orElseThrow(ChatCitationReadService::unavailable);
        if (!item.equals(publicCitation.path("item_id").asText()) || !source.equals(publicCitation.path("source_id").asText())
                || !version.equals(publicCitation.path("source_version").asText())) throw unavailable();
        String bundle = required(evidence, "bundleId"), bundleVersion = required(evidence, "version"), resultRef = required(evidence, "resultRef");
        var safe = guard.capture();
        if (safe.rejects(completed.path("text").textValue())) throw unavailable();
        for (var entry : completed.path("evidence")) {
            if (safe.rejects(required(entry, "itemId")) || safe.rejects(required(entry, "sourceId"))
                    || safe.rejects(required(entry, "sourceVersion"))) throw unavailable();
        }
        var attempts = repository.citationAttempts(tenant, event.runId(), resultRef);
        if (attempts.size() != 1) throw unavailable();
        var attempt = attempts.getFirst();
        var request = repository.material(tenant, attempt.requestRef());
        var result = repository.material(tenant, attempt.resultRef());
        if (request == null || result == null) throw unavailable();
        var inspected = inspector.inspect(ExecutionStore.Kind.KNOWLEDGE, request, Optional.of(result));
        var access = inspected.knowledgeAccess().orElseThrow(ChatCitationReadService::unavailable);
        var trace = access.trace();
        if (!("execution-knowledge:" + event.runId() + ":" + attempt.actionOrdinal()).equals(access.requestId())
                || !event.rootInputId().equals(trace.rootInputId().value()) || !event.traceId().equals(trace.traceId())
                || !event.spanId().equals(trace.spanId())
                || !java.util.Objects.equals(event.causedByEventId(), trace.causedByEventId().map(value -> value.value()).orElse(null))
                || !Long.toString(tenant).equals(access.tenantId().value()) || !session.equals(access.sessionRef().sessionId())
                || !event.agentId().equals(access.agentId().value())) throw unavailable();
        var sourceVersion = new KnowledgeSourceVersion(source, version);
        if (!access.sourceVersions().contains(sourceVersion)) throw unavailable();
        knowledge.requireRead(access.tenantId(), access.agentId(), access.sessionRef(), access.target(), Set.of(sourceVersion), false);
        var output = inspected.output().orElseThrow(ChatCitationReadService::unavailable);
        var body = parse(output.canonicalJson());
        if (!bundle.equals(body.path("bundle_id").asText()) || !bundleVersion.equals(body.path("version").asText())
                || !body.path("items").isArray()) throw unavailable();
        var matching = new java.util.ArrayList<JsonNode>();
        for (var entry : body.path("items")) if (item.equals(entry.path("item_id").asText())
                && source.equals(entry.path("source_id").asText()) && version.equals(entry.path("source_version").asText())) matching.add(entry);
        if (matching.size() != 1 || !matching.getFirst().path("text").isTextual()) throw unavailable();
        String text = matching.getFirst().path("text").textValue();
        if (safe.rejects(text)) throw unavailable();
        audit.record(Long.toString(tenant), scope.actorId(), "chat.citation.read", "session", session,
                "读取已授权知识引用片段", Map.of("availability", "FULL"));
        return new Citation("知识依据", version, text);
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (IOException invalid) { throw unavailable(); }
    }
    private static String required(JsonNode value, String name) {
        var field = value.get(name);
        if (field == null || !field.isTextual() || field.textValue().isBlank()) throw unavailable();
        return field.textValue();
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "引用片段不可用");
    }
}
