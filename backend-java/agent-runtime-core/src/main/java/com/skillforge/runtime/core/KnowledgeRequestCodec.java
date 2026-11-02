package com.skillforge.runtime.core;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgeRequest;
import com.skillforge.runtime.api.KnowledgeOperation;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 当前冻结知识请求的有界字段编解码；不持有执行来源、账本或调用许可。 */
final class KnowledgeRequestCodec {
    private KnowledgeRequestCodec() { }

    static void preflightTarget(OutcomeEncodingBudget budget, int depth, FrozenKnowledgeTarget value) {
        budget.object(depth, "handle", "capability", "descriptorId", "descriptorRevision", "sourceVersions",
                "allowedOperators", "timeout");
        budget.string(depth + 1, value.handle().value());
        preflightCapabilityRef(budget, depth + 1, value.capability());
        budget.string(depth + 1, value.descriptorId());
        budget.string(depth + 1, Long.toString(value.descriptorRevision()));
        budget.array(depth + 1, value.sourceVersions().size());
        for (var source : value.sourceVersions()) { preflightSourceVersion(budget, depth + 2, source); }
        budget.array(depth + 1, value.allowedOperators().size());
        for (var operator : value.allowedOperators()) { budget.string(depth + 2, operator.name()); }
        budget.string(depth + 1, value.timeout().toString());
    }

    static void preflightRequest(OutcomeEncodingBudget budget, int depth, KnowledgeRequest value) {
        budget.object(depth, "knowledgeRequestId", "tenantId", "requester", "agentId", "sessionRef", "trace",
                "mode", "intent", "operation", "allowedSourceIds", "allowedSourceVersions", "allowedOperators",
                "budget", "asOf", "freshnessAfter", "citationRequired");
        budget.string(depth + 1, value.knowledgeRequestId()); budget.string(depth + 1, value.tenantId().value());
        budget.requester(depth + 1, value.requester()); budget.string(depth + 1, value.agentId().value());
        preflightSession(budget, depth + 1, value.sessionRef());
        preflightTrace(budget, depth + 1, value.trace());
        budget.string(depth + 1, value.mode().name()); budget.string(depth + 1, value.intent());
        preflightOperation(budget, depth + 1, value.operation());
        budget.array(depth + 1, value.allowedSourceIds().size());
        for (var source : value.allowedSourceIds()) { budget.string(depth + 2, source); }
        budget.array(depth + 1, value.allowedSourceVersions().size());
        for (var source : value.allowedSourceVersions()) { preflightSourceVersion(budget, depth + 2, source); }
        budget.array(depth + 1, value.allowedOperators().size());
        for (var operator : value.allowedOperators()) { budget.string(depth + 2, operator.name()); }
        preflightKnowledgeBudget(budget, depth + 1, value.budget());
        budget.string(depth + 1, value.asOf().toString());
        if (value.freshnessAfter().isPresent()) { budget.string(depth + 1, value.freshnessAfter().orElseThrow().toString()); }
        else { budget.nil(depth + 1); }
        budget.bool(depth + 1, value.citationRequired());
    }

    private static void preflightKnowledgeBudget(OutcomeEncodingBudget budget, int depth, KnowledgeBudget value) {
        budget.object(depth, "maxQueries", "maxItems", "maxTokens", "maxCharacters", "timeoutMs");
        budget.string(depth + 1, Integer.toString(value.maxQueries()));
        budget.string(depth + 1, Integer.toString(value.maxItems()));
        budget.string(depth + 1, Integer.toString(value.maxTokens()));
        budget.string(depth + 1, Integer.toString(value.maxCharacters()));
        budget.string(depth + 1, Long.toString(value.timeoutMs()));
    }

    private static void preflightCapabilityRef(OutcomeEncodingBudget budget, int depth, CapabilityRef value) {
        budget.object(depth, "type", "capabilityId", "versionId");
        budget.string(depth + 1, value.type().name()); budget.string(depth + 1, value.capabilityId());
        budget.string(depth + 1, value.versionId());
    }

    private static void preflightSourceVersion(OutcomeEncodingBudget budget, int depth, KnowledgeSourceVersion value) {
        budget.object(depth, "sourceId", "sourceVersion");
        budget.string(depth + 1, value.sourceId()); budget.string(depth + 1, value.sourceVersion());
    }

    private static void preflightSession(OutcomeEncodingBudget budget, int depth, SessionRef value) {
        budget.object(depth, "tenantId", "sessionId");
        budget.string(depth + 1, value.tenantId().value()); budget.string(depth + 1, value.sessionId());
    }

    private static void preflightTrace(OutcomeEncodingBudget budget, int depth, TraceEnvelope value) {
        budget.object(depth, "traceId", "spanId", "rootInputId", "causedByEventId");
        budget.string(depth + 1, value.traceId()); budget.string(depth + 1, value.spanId());
        budget.string(depth + 1, value.rootInputId().value());
        if (value.causedByEventId().isPresent()) { budget.string(depth + 1, value.causedByEventId().orElseThrow().value()); }
        else { budget.nil(depth + 1); }
    }

    static Value target(FrozenKnowledgeTarget value) {
        return object(text("handle", value.handle().value()), new Field("capability", capabilityRef(value.capability())),
                text("descriptorId", value.descriptorId()), new Field("descriptorRevision", CanonicalJson.integer(value.descriptorRevision())),
                new Field("sourceVersions", sortedSourceVersions(value.sourceVersions())),
                new Field("allowedOperators", sortedOperators(value.allowedOperators())),
                text("timeout", value.timeout().toString()));
    }

    static Value request(KnowledgeRequest value) {
        return object(text("knowledgeRequestId", value.knowledgeRequestId()), text("tenantId", value.tenantId().value()),
                new Field("requester", ModelPreparationIdentity.requester(value.requester())), text("agentId", value.agentId().value()),
                new Field("sessionRef", session(value.sessionRef())), new Field("trace", ModelPreparationIdentity.trace(value.trace())),
                text("mode", value.mode().name()), text("intent", value.intent()), new Field("operation", operation(value.operation())),
                new Field("allowedSourceIds", sortedStrings(value.allowedSourceIds())),
                new Field("allowedSourceVersions", sortedSourceVersions(value.allowedSourceVersions())),
                new Field("allowedOperators", sortedOperators(value.allowedOperators())),
                new Field("budget", knowledgeBudget(value.budget())), text("asOf", value.asOf().toString()),
                new Field("freshnessAfter", value.freshnessAfter().map(time -> CanonicalJson.string(time.toString()))
                        .orElseGet(CanonicalJson::nil)),
                new Field("citationRequired", CanonicalJson.bool(value.citationRequired())));
    }

    static FrozenKnowledgeTarget readTarget(Value value) {
        value.requireFields("handle", "capability", "descriptorId", "descriptorRevision", "sourceVersions",
                "allowedOperators", "timeout");
        return new FrozenKnowledgeTarget(new KnowledgeHandle(value.field("handle").stringValue()),
                readCapabilityRef(value.field("capability")), value.field("descriptorId").stringValue(),
                value.field("descriptorRevision").longValue(), readSourceVersions(value.field("sourceVersions")),
                readOperators(value.field("allowedOperators")), Duration.parse(value.field("timeout").stringValue()));
    }

    static KnowledgeRequest readRequest(Value value) {
        value.requireFields("knowledgeRequestId", "tenantId", "requester", "agentId", "sessionRef", "trace", "mode",
                "intent", "operation", "allowedSourceIds", "allowedSourceVersions", "allowedOperators", "budget", "asOf",
                "freshnessAfter", "citationRequired");
        var freshness = value.field("freshnessAfter");
        return new KnowledgeRequest(value.field("knowledgeRequestId").stringValue(),
                new TenantId(value.field("tenantId").stringValue()), ModelPreparationIdentity.decodeRequester(value.field("requester")),
                new AgentId(value.field("agentId").stringValue()), readSession(value.field("sessionRef")),
                readTrace(value.field("trace")), KnowledgeMode.valueOf(value.field("mode").stringValue()),
                value.field("intent").stringValue(), readOperation(value.field("operation")),
                readStrings(value.field("allowedSourceIds")), readSourceVersions(value.field("allowedSourceVersions")),
                readOperators(value.field("allowedOperators")), readKnowledgeBudget(value.field("budget")),
                Instant.parse(value.field("asOf").stringValue()),
                freshness.isNull() ? Optional.empty() : Optional.of(Instant.parse(freshness.stringValue())),
                value.field("citationRequired").booleanValue());
    }

    private static void preflightOperation(OutcomeEncodingBudget budget, int depth, KnowledgeOperation operation) {
        switch (operation) {
            case KnowledgeOperation.Search search -> {
                budget.object(depth, "type", "query"); budget.string(depth + 1, "search"); budget.string(depth + 1, search.query());
            }
            case KnowledgeOperation.ListDocuments list -> {
                budget.object(depth, "type", "titleFilter", "cursor", "pageSize"); budget.string(depth + 1, "list_documents");
                budget.string(depth + 1, list.titleFilter());
                if (list.cursor().isPresent()) budget.string(depth + 1, list.cursor().orElseThrow()); else budget.nil(depth + 1);
                budget.string(depth + 1, Integer.toString(list.pageSize()));
            }
            case KnowledgeOperation.ReadDocument read -> {
                budget.object(depth, "type", "sourceId", "documentId", "sourceVersion", "offset", "maxCharacters");
                budget.string(depth + 1, "read_document"); budget.string(depth + 1, read.sourceId()); budget.string(depth + 1, read.documentId());
                budget.string(depth + 1, read.sourceVersion()); budget.string(depth + 1, Integer.toString(read.offset())); budget.string(depth + 1, Integer.toString(read.maxCharacters()));
            }
        }
    }
    private static Value operation(KnowledgeOperation operation) {
        return switch (operation) {
            case KnowledgeOperation.Search search -> object(text("type", "search"), text("query", search.query()));
            case KnowledgeOperation.ListDocuments list -> object(text("type", "list_documents"), text("titleFilter", list.titleFilter()),
                    new Field("cursor", list.cursor().map(CanonicalJson::string).orElseGet(CanonicalJson::nil)), new Field("pageSize", CanonicalJson.integer(list.pageSize())));
            case KnowledgeOperation.ReadDocument read -> object(text("type", "read_document"), text("sourceId", read.sourceId()), text("documentId", read.documentId()),
                    text("sourceVersion", read.sourceVersion()), new Field("offset", CanonicalJson.integer(read.offset())), new Field("maxCharacters", CanonicalJson.integer(read.maxCharacters())));
        };
    }
    private static KnowledgeOperation readOperation(Value value) {
        return switch (value.field("type").stringValue()) {
            case "search" -> { value.requireFields("type", "query"); yield new KnowledgeOperation.Search(value.field("query").stringValue()); }
            case "list_documents" -> {
                value.requireFields("type", "titleFilter", "cursor", "pageSize");
                yield new KnowledgeOperation.ListDocuments(value.field("titleFilter").stringValue(), value.field("cursor").isNull() ? Optional.empty() : Optional.of(value.field("cursor").stringValue()), value.field("pageSize").intValue());
            }
            case "read_document" -> {
                value.requireFields("type", "sourceId", "documentId", "sourceVersion", "offset", "maxCharacters");
                yield new KnowledgeOperation.ReadDocument(value.field("sourceId").stringValue(), value.field("documentId").stringValue(), value.field("sourceVersion").stringValue(), value.field("offset").intValue(), value.field("maxCharacters").intValue());
            }
            default -> throw invalid();
        };
    }

    private static CapabilityRef readCapabilityRef(Value value) {
        value.requireFields("type", "capabilityId", "versionId");
        return new CapabilityRef(CapabilityType.valueOf(value.field("type").stringValue()),
                value.field("capabilityId").stringValue(), value.field("versionId").stringValue());
    }

    private static KnowledgeBudget readKnowledgeBudget(Value value) {
        value.requireFields("maxQueries", "maxItems", "maxTokens", "maxCharacters", "timeoutMs");
        return new KnowledgeBudget(value.field("maxQueries").intValue(), value.field("maxItems").intValue(),
                value.field("maxTokens").intValue(), value.field("maxCharacters").intValue(),
                value.field("timeoutMs").longValue());
    }

    private static SessionRef readSession(Value value) {
        value.requireFields("tenantId", "sessionId");
        return new SessionRef(new TenantId(value.field("tenantId").stringValue()), value.field("sessionId").stringValue());
    }

    private static TraceEnvelope readTrace(Value value) {
        value.requireFields("traceId", "spanId", "rootInputId", "causedByEventId");
        var cause = value.field("causedByEventId");
        return new TraceEnvelope(value.field("traceId").stringValue(), value.field("spanId").stringValue(),
                new InputId(value.field("rootInputId").stringValue()),
                cause.isNull() ? Optional.empty() : Optional.of(new EventId(cause.stringValue())));
    }

    private static Set<String> readStrings(Value value) {
        var unique = new HashSet<String>();
        for (var element : value.arrayValue()) { if (!unique.add(element.stringValue())) { throw invalid(); } }
        return Set.copyOf(unique);
    }

    private static Set<KnowledgeSourceVersion> readSourceVersions(Value value) {
        var unique = new HashSet<KnowledgeSourceVersion>();
        for (var element : value.arrayValue()) {
            element.requireFields("sourceId", "sourceVersion");
            var source = new KnowledgeSourceVersion(element.field("sourceId").stringValue(),
                    element.field("sourceVersion").stringValue());
            if (!unique.add(source)) { throw invalid(); }
        }
        return Set.copyOf(unique);
    }

    private static Set<KnowledgeOperatorType> readOperators(Value value) {
        var unique = new HashSet<KnowledgeOperatorType>();
        for (var element : value.arrayValue()) {
            if (!unique.add(KnowledgeOperatorType.valueOf(element.stringValue()))) { throw invalid(); }
        }
        return Set.copyOf(unique);
    }

    private static Value knowledgeBudget(KnowledgeBudget value) {
        return object(new Field("maxQueries", CanonicalJson.integer(value.maxQueries())),
                new Field("maxItems", CanonicalJson.integer(value.maxItems())),
                new Field("maxTokens", CanonicalJson.integer(value.maxTokens())),
                new Field("maxCharacters", CanonicalJson.integer(value.maxCharacters())),
                new Field("timeoutMs", CanonicalJson.integer(value.timeoutMs())));
    }

    private static Value capabilityRef(CapabilityRef value) {
        return object(text("type", value.type().name()), text("capabilityId", value.capabilityId()),
                text("versionId", value.versionId()));
    }

    private static Value session(SessionRef value) {
        return object(text("tenantId", value.tenantId().value()), text("sessionId", value.sessionId()));
    }

    private record Encoded(Value value, byte[] bytes) { }

    private static Value sortedStrings(Set<String> values) {
        return sorted(values.stream().map(CanonicalJson::string).toList());
    }

    private static Value sortedOperators(Set<KnowledgeOperatorType> values) {
        return sorted(values.stream().map(value -> CanonicalJson.string(value.name())).toList());
    }

    private static Value sortedSourceVersions(Set<KnowledgeSourceVersion> values) {
        return sorted(values.stream().map(value -> object(text("sourceId", value.sourceId()),
                text("sourceVersion", value.sourceVersion()))).toList());
    }

    private static Value sorted(List<Value> values) {
        var encoded = values.stream().map(value -> new Encoded(value,
                        CanonicalJson.encode(value).getBytes(StandardCharsets.UTF_8)))
                .sorted((left, right) -> Arrays.compareUnsigned(left.bytes(), right.bytes())).toList();
        return CanonicalJson.array(encoded.stream().map(Encoded::value).toList());
    }

    private static Value object(Field... fields) { return CanonicalJson.object(List.of(fields)); }
    private static Field text(String name, String value) { return new Field(name, CanonicalJson.string(value)); }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid frozen Knowledge request payload");
    }
}
