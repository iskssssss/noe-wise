package com.skillforge.runtime.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalTrace;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 独立冻结 Memory 检索事件的规范字节、严格解码与摘要域。 */
class MemoryEventCodecTest {
    private static final String QUERY_ID = "query-\"雪\"";
    private static final Instant AS_OF = Instant.parse("2026-09-08T01:02:03Z");
    private static final Instant EVALUATED_AT = Instant.parse("2026-09-08T01:02:03.123456789Z");

    private static final String QUERY_JSON =
            "{\"asOf\":\"2026-09-08T01:02:03Z\",\"characterBudget\":\"456\",\"intent\":\"记住 \\\"A\\\"\\\\B\\n下一行\",\"maxItems\":\"3\",\"owner\":{\"scope\":\"SESSION\",\"sessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"}},\"profile\":\"DEFAULT\",\"queryId\":\"query-\\\"雪\\\"\",\"requireProvenance\":true,\"retrievalPolicy\":{\"analyzerVersion\":\"lucene-cjk-9.12.3-v1\",\"candidateLimit\":\"64\",\"embeddingModel\":null,\"id\":\"memory-lexical\",\"minimumCosineSimilarity\":\"0.7\",\"projectionVersion\":\"memory-body-v1\",\"rrfRankConstant\":\"60\",\"vectorFailurePolicy\":\"FAIL\",\"version\":\"1\"},\"scopes\":[\"SESSION\",\"USER_AGENT\"],\"subjectEndUserId\":null,\"taskConstraint\":null,\"tokenBudget\":\"123\",\"types\":[\"alpha\",\"偏好\\\"\\\\\"]}";
    private static final String FACT_JSON =
            "{\"confidence\":\"0.875\",\"createdAt\":\"2026-09-08T00:59:59.123456789Z\",\"decayScore\":\"0.125\",\"derivedFrom\":{\"factId\":\"fact-来源\",\"tenantId\":\"tenant-甲\"},\"expiresAt\":\"2027-09-08T00:00:00Z\",\"exportPath\":\"$.facts[\\\"偏好\\\"]\",\"extractorVersion\":\"extractor-一\",\"importance\":\"0.625\",\"object\":{\"canonicalJson\":\"{\\\"a\\\":\\\"雪\\\",\\\"z\\\":\\\"line\\\\nnext\\\"}\",\"schemaId\":\"fact.payload.v1\"},\"owner\":{\"scope\":\"SESSION\",\"sessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"}},\"predicate\":\"predicate-\\\\path\",\"ref\":{\"factId\":\"fact-完整\",\"tenantId\":\"tenant-甲\"},\"retentionClass\":\"retention-长\",\"reviewRequired\":true,\"revision\":\"9007199254740993\",\"schemaVersion\":\"v1\",\"sourceEventIds\":[\"source-一\",\"source-二\"],\"sourceExecutionRef\":{\"executionId\":\"execution-一\",\"tenantId\":\"tenant-甲\"},\"sourceSessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"},\"status\":\"ACTIVE\",\"subject\":\"subject-\\\"雪\\\"\",\"supersededBy\":{\"factId\":\"fact-后继\",\"tenantId\":\"tenant-甲\"},\"type\":\"偏好\\\"\\\\\",\"validFrom\":\"2026-09-08T01:00:00Z\",\"validTo\":\"2026-09-08T02:00:00Z\"}";
    private static final String DRAFT_JSON =
            "{\"confidence\":\"0.875\",\"derivedFrom\":{\"factId\":\"fact-来源\",\"tenantId\":\"tenant-甲\"},\"expiresAt\":\"2027-09-08T00:00:00Z\",\"exportPath\":\"$.facts[\\\"偏好\\\"]\",\"extractorVersion\":\"extractor-一\",\"importance\":\"0.625\",\"initialStatus\":\"PENDING_CONFIRM\",\"object\":{\"canonicalJson\":\"{\\\"a\\\":\\\"雪\\\",\\\"z\\\":\\\"line\\\\nnext\\\"}\",\"schemaId\":\"fact.payload.v1\"},\"owner\":{\"scope\":\"SESSION\",\"sessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"}},\"predicate\":\"predicate-\\\\path\",\"retentionClass\":\"retention-长\",\"reviewRequired\":true,\"schemaVersion\":\"v1\",\"sourceEventIds\":[\"source-一\",\"source-二\"],\"sourceExecutionRef\":{\"executionId\":\"execution-一\",\"tenantId\":\"tenant-甲\"},\"sourceSessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"},\"subject\":\"subject-\\\"雪\\\"\",\"type\":\"偏好\\\"\\\\\",\"validFrom\":\"2026-09-08T01:00:00Z\",\"validTo\":\"2026-09-08T02:00:00Z\"}";
    private static final String RETRIEVAL_GOLDEN =
            "{\"accessAuditRef\":\"audit-检索\",\"diagnostics\":{\"budgetSelectedIds\":[],\"candidates\":[],\"degradationReason\":null,\"mode\":\"LEXICAL\",\"readyVectorCount\":\"0\",\"visibleDocumentCount\":\"0\"},\"evaluatedAt\":\"2026-09-08T01:02:03.123456789Z\",\"facts\":[],\"phase\":\"CANDIDATE\",\"query\":{\"asOf\":\"2026-09-08T01:02:03Z\",\"characterBudget\":\"456\",\"intent\":\"记住 \\\"A\\\"\\\\B\\n下一行\",\"maxItems\":\"3\",\"owner\":{\"scope\":\"SESSION\",\"sessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"}},\"profile\":\"DEFAULT\",\"queryId\":\"query-\\\"雪\\\"\",\"requireProvenance\":true,\"retrievalPolicy\":{\"analyzerVersion\":\"lucene-cjk-9.12.3-v1\",\"candidateLimit\":\"64\",\"embeddingModel\":null,\"id\":\"memory-lexical\",\"minimumCosineSimilarity\":\"0.7\",\"projectionVersion\":\"memory-body-v1\",\"rrfRankConstant\":\"60\",\"vectorFailurePolicy\":\"FAIL\",\"version\":\"1\"},\"scopes\":[\"SESSION\",\"USER_AGENT\"],\"subjectEndUserId\":null,\"taskConstraint\":null,\"tokenBudget\":\"123\",\"types\":[\"alpha\",\"偏好\\\"\\\\\"]},\"resultRef\":\"result-检索\",\"trace\":{\"candidateIds\":[],\"injectedIds\":[],\"queryId\":\"query-\\\"雪\\\"\",\"referencedIds\":[],\"selectedIds\":[]}}";
    private static final String INJECTED_RETRIEVAL_GOLDEN =
            "{\"accessAuditRef\":\"audit-检索\",\"diagnostics\":{\"budgetSelectedIds\":[\"fact-完整\"],\"candidates\":[{\"cosineSimilarity\":null,\"fact\":{\"factId\":\"fact-完整\",\"tenantId\":\"tenant-甲\"},\"fusedScore\":\"0.01639344262295082\",\"lexicalRank\":\"1\",\"lexicalScore\":\"1\",\"revision\":\"9007199254740993\",\"vectorRank\":null}],\"degradationReason\":null,\"mode\":\"LEXICAL\",\"readyVectorCount\":\"0\",\"visibleDocumentCount\":\"1\"},\"evaluatedAt\":\"2026-09-08T01:02:03.123456789Z\",\"facts\":[{\"confidence\":\"0.875\",\"createdAt\":\"2026-09-08T00:59:59.123456789Z\",\"decayScore\":\"0.125\",\"derivedFrom\":{\"factId\":\"fact-来源\",\"tenantId\":\"tenant-甲\"},\"expiresAt\":\"2027-09-08T00:00:00Z\",\"exportPath\":\"$.facts[\\\"偏好\\\"]\",\"extractorVersion\":\"extractor-一\",\"importance\":\"0.625\",\"object\":{\"canonicalJson\":\"{\\\"a\\\":\\\"雪\\\",\\\"z\\\":\\\"line\\\\nnext\\\"}\",\"schemaId\":\"fact.payload.v1\"},\"owner\":{\"scope\":\"SESSION\",\"sessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"}},\"predicate\":\"predicate-\\\\path\",\"ref\":{\"factId\":\"fact-完整\",\"tenantId\":\"tenant-甲\"},\"retentionClass\":\"retention-长\",\"reviewRequired\":true,\"revision\":\"9007199254740993\",\"schemaVersion\":\"v1\",\"sourceEventIds\":[\"source-一\",\"source-二\"],\"sourceExecutionRef\":{\"executionId\":\"execution-一\",\"tenantId\":\"tenant-甲\"},\"sourceSessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"},\"status\":\"ACTIVE\",\"subject\":\"subject-\\\"雪\\\"\",\"supersededBy\":{\"factId\":\"fact-后继\",\"tenantId\":\"tenant-甲\"},\"type\":\"偏好\\\"\\\\\",\"validFrom\":\"2026-09-08T01:00:00Z\",\"validTo\":\"2026-09-08T02:00:00Z\"}],\"phase\":\"INJECTED\",\"query\":{\"asOf\":\"2026-09-08T01:02:03Z\",\"characterBudget\":\"456\",\"intent\":\"记住 \\\"A\\\"\\\\B\\n下一行\",\"maxItems\":\"3\",\"owner\":{\"scope\":\"SESSION\",\"sessionRef\":{\"sessionId\":\"session-一\",\"tenantId\":\"tenant-甲\"}},\"profile\":\"DEFAULT\",\"queryId\":\"query-\\\"雪\\\"\",\"requireProvenance\":true,\"retrievalPolicy\":{\"analyzerVersion\":\"lucene-cjk-9.12.3-v1\",\"candidateLimit\":\"64\",\"embeddingModel\":null,\"id\":\"memory-lexical\",\"minimumCosineSimilarity\":\"0.7\",\"projectionVersion\":\"memory-body-v1\",\"rrfRankConstant\":\"60\",\"vectorFailurePolicy\":\"FAIL\",\"version\":\"1\"},\"scopes\":[\"SESSION\",\"USER_AGENT\"],\"subjectEndUserId\":null,\"taskConstraint\":null,\"tokenBudget\":\"123\",\"types\":[\"alpha\",\"偏好\\\"\\\\\"]},\"resultRef\":\"result-检索\",\"trace\":{\"candidateIds\":[\"fact-完整\"],\"injectedIds\":[\"fact-完整\"],\"queryId\":\"query-\\\"雪\\\"\",\"referencedIds\":[],\"selectedIds\":[\"fact-完整\"]}}";
    private static final String CREATE_GOLDEN =
            "{\"accessAuditRef\":\"audit-写\",\"before\":null,"
                    + "\"command\":{\"draft\":" + DRAFT_JSON + "},"
                    + "\"fact\":" + FACT_JSON + ","
                    + "\"idempotencyKey\":\"write-key-创建\",\"operation\":\"CREATE\","
                    + "\"sourceAuditRef\":\"audit-源\"}";
    private static final String SUPERSEDE_GOLDEN =
            "{\"accessAuditRef\":\"audit-写\",\"before\":" + FACT_JSON + ","
                    + "\"command\":{\"current\":{\"factId\":\"fact-完整\",\"tenantId\":\"tenant-甲\"},"
                    + "\"expectedRevision\":\"9007199254740993\",\"replacement\":" + DRAFT_JSON + "},"
                    + "\"fact\":" + FACT_JSON + ","
                    + "\"idempotencyKey\":\"write-key-取代\",\"operation\":\"SUPERSEDE\","
                    + "\"sourceAuditRef\":\"audit-源\"}";
    private static final String TRANSITION_GOLDEN =
            "{\"accessAuditRef\":\"audit-写\",\"before\":" + FACT_JSON + ","
                    + "\"command\":{\"expectedRevision\":\"9007199254740993\","
                    + "\"fact\":{\"factId\":\"fact-完整\",\"tenantId\":\"tenant-甲\"},"
                    + "\"reasonCode\":\"review-approved\",\"target\":\"ACTIVE\"},"
                    + "\"fact\":" + FACT_JSON + ","
                    + "\"idempotencyKey\":\"write-key-迁移\",\"operation\":\"TRANSITION\","
                    + "\"sourceAuditRef\":null}";

    @Test
    void observationMetadataGroupsRetrievalPhasesAndKeepsWrittenOperationsWithoutBodies() {
        String operationId = null;
        for (var phase : MemoryRuntime.Phase.values()) {
            var payload = new TypedPayload(MemoryEventCodec.RETRIEVAL_SCHEMA,
                    RETRIEVAL_GOLDEN.replace("\"phase\":\"CANDIDATE\"", "\"phase\":\"" + phase.name() + "\""));
            var metadata = MemoryObservation.read(MemoryEventCodec.RETRIEVAL_EVENT, payload);
            if (operationId == null) operationId = metadata.operationId();
            assertEquals(operationId, metadata.operationId());
            assertEquals(phase.name(), metadata.phase());
            assertEquals(MemoryObservation.Kind.RETRIEVAL, metadata.kind());
            assertEquals("RECORDED", metadata.status());
            assertFalse(metadata.toString().contains("intent"));
        }
        for (var entry : java.util.Map.of("CREATE", CREATE_GOLDEN, "SUPERSEDE", SUPERSEDE_GOLDEN, "TRANSITION", TRANSITION_GOLDEN).entrySet()) {
            var metadata = MemoryObservation.read(MemoryEventCodec.WRITTEN_EVENT,
                    new TypedPayload(MemoryEventCodec.WRITTEN_SCHEMA, entry.getValue()));
            assertEquals(entry.getKey(), metadata.operation());
            assertEquals(MemoryObservation.Kind.MEMORY, metadata.kind());
            assertTrue(metadata.operationId().startsWith("memory-write:"));
            assertFalse(metadata.toString().contains("write-key"));
            assertFalse(metadata.toString().contains("predicate"));
        }
        assertThrows(RuntimeException.class, () -> MemoryObservation.read("unknown", new TypedPayload(MemoryEventCodec.RETRIEVAL_SCHEMA, RETRIEVAL_GOLDEN)));
        assertThrows(RuntimeException.class, () -> MemoryObservation.read(MemoryEventCodec.WRITTEN_EVENT,
                new TypedPayload(MemoryEventCodec.WRITTEN_SCHEMA, CREATE_GOLDEN.replace("\"operation\":\"CREATE\"", "\"operation\":\"UNKNOWN\""))));
    }

    @Test
    void retrievalPayloadMatchesIndependentGoldenAndSortsSetsByUnsignedUtf8() {
        MemoryEventCodec.RetrievalSnapshot first = snapshot(
                orderedSet(MemoryScope.USER_AGENT, MemoryScope.SESSION),
                orderedSet(new MemoryTypeRef("偏好\"\\"), new MemoryTypeRef("alpha")));
        MemoryEventCodec.RetrievalSnapshot reversed = snapshot(
                orderedSet(MemoryScope.SESSION, MemoryScope.USER_AGENT),
                orderedSet(new MemoryTypeRef("alpha"), new MemoryTypeRef("偏好\"\\")));

        TypedPayload firstPayload = MemoryEventCodec.retrievalPayload(first);
        TypedPayload reversedPayload = MemoryEventCodec.retrievalPayload(reversed);

        assertEquals(MemoryEventCodec.RETRIEVAL_SCHEMA, firstPayload.schemaId());
        assertEquals(RETRIEVAL_GOLDEN, firstPayload.canonicalJson());
        assertEquals(firstPayload, reversedPayload);
    }

    @Test
    void retrievalDecodeEncodeIsByteExact() {
        TypedPayload payload = new TypedPayload(MemoryEventCodec.RETRIEVAL_SCHEMA, RETRIEVAL_GOLDEN);

        MemoryEventCodec.RetrievalSnapshot decoded = MemoryEventCodec.decodeRetrieval(payload);

        assertEquals(payload, MemoryEventCodec.retrievalPayload(decoded));
        assertEquals(QUERY_ID, decoded.query().queryId());
        assertEquals("\u8bb0\u4f4f \"A\"\\B\n\u4e0b\u4e00\u884c", decoded.query().intent());
        assertEquals(Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT), decoded.query().scopes());
        assertEquals(Set.of(new MemoryTypeRef("alpha"), new MemoryTypeRef("\u504f\u597d\"\\")), decoded.query().types());
    }

    @Test
    void fullInjectedRetrievalMatchesGoldenAndRoundTripsEveryFactField() {
        MemoryEventCodec.RetrievalSnapshot snapshot = fullInjectedSnapshot();

        TypedPayload payload = MemoryEventCodec.retrievalPayload(snapshot);
        MemoryEventCodec.RetrievalSnapshot decoded = MemoryEventCodec.decodeRetrieval(payload);

        assertEquals(MemoryEventCodec.RETRIEVAL_SCHEMA, payload.schemaId());
        assertEquals(INJECTED_RETRIEVAL_GOLDEN, payload.canonicalJson());
        assertEquals(payload, MemoryEventCodec.retrievalPayload(decoded));
        assertEquals(List.of(fullFact()), decoded.facts());
        assertEquals(MemoryRuntime.Phase.INJECTED, decoded.phase());
    }

    @Test
    void everyWrittenCommandShapeMatchesItsIndependentGolden() {
        MemoryFact fact = fullFact();
        MemoryFactDraft draft = fullDraft();
        ResultRef accessAudit = new ResultRef("audit-写");
        Optional<ResultRef> sourceAudit = Optional.of(new ResultRef("audit-源"));

        var create = new MemoryRuntime.Mutation.Create(
                new IdempotencyKey("write-key-创建"), draft);
        var supersede = new MemoryRuntime.Mutation.Supersede(
                new IdempotencyKey("write-key-取代"), fact.ref(), fact.revision(), draft);
        var transition = new MemoryRuntime.Mutation.Transition(
                new IdempotencyKey("write-key-迁移"), fact.ref(), fact.revision(),
                fact.scope(), fact.type(), FactStatus.ACTIVE, "review-approved");

        assertEquals(new TypedPayload(MemoryEventCodec.WRITTEN_SCHEMA, CREATE_GOLDEN),
                MemoryEventCodec.writtenPayload(
                        create, Optional.empty(), fact, accessAudit, sourceAudit));
        assertEquals(new TypedPayload(MemoryEventCodec.WRITTEN_SCHEMA, SUPERSEDE_GOLDEN),
                MemoryEventCodec.writtenPayload(
                        supersede, Optional.of(fact), fact, accessAudit, sourceAudit));
        assertEquals(new TypedPayload(MemoryEventCodec.WRITTEN_SCHEMA, TRANSITION_GOLDEN),
                MemoryEventCodec.writtenPayload(
                        transition, Optional.of(fact), fact, accessAudit, Optional.empty()));
    }

    /** Python 独立重建 owner targetKind/targetId 与实际 agentId，冻结身份及完整 envelope 的域分离摘要。 */
    @Test
    void eventIdsAndFullEnvelopeFingerprintMatchIndependentVectors() {
        RunIdentity identity = fullRunIdentity();
        MemoryEventCodec.RetrievalSnapshot snapshot = fullInjectedSnapshot();
        EventId retrievalId = MemoryEventCodec.retrievalEventId(identity, snapshot);
        EventId writeId = MemoryEventCodec.writtenEventId(
                identity.owner(), new IdempotencyKey("write-key-创建"));
        SessionProjectionTarget target = new SessionProjectionTarget(
                identity.owner(), identity.causationInputId(), Optional.of(identity.runRef()),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(
                new EventId("memory-retrieval:5a645052eca09b8f76765db03cb3cd895cce37b4a707293e9595bf5be9269df5"),
                retrievalId);
        assertEquals(
                new EventId("memory-write:49740ba9b52bf16051349289a09a48166faf27a3e4ed30f7e38e71af3b7be204"),
                writeId);
        assertEquals("afe584594d6bf779c7bbbe2c1be2f9c3c2f5dab6eee2c72a30a20139bf252624",
                MemoryEventCodec.eventFingerprint(
                        target, retrievalId, MemoryEventCodec.RETRIEVAL_EVENT,
                        new TypedPayload(MemoryEventCodec.RETRIEVAL_SCHEMA, INJECTED_RETRIEVAL_GOLDEN),
                        identity.trace(), Instant.parse("2026-09-08T01:02:04.987654321Z")));
    }

    @Test
    void retrievalDecoderRejectsSchemaDriftAndNonCanonicalJson() {
        assertThrows(IllegalStateException.class, () -> MemoryEventCodec.decodeRetrieval(
                new TypedPayload("wrong.schema", RETRIEVAL_GOLDEN)));

        assertRejected("{\"aaaUnknown\":\"x\"," + RETRIEVAL_GOLDEN.substring(1));
        assertRejected(RETRIEVAL_GOLDEN.replace("\"facts\":[],", ""));
        assertRejected(" " + RETRIEVAL_GOLDEN);
        assertRejected(RETRIEVAL_GOLDEN.replace("\"maxItems\":\"3\"", "\"maxItems\":3"));
        assertRejected(RETRIEVAL_GOLDEN.replaceFirst(
                "\\{\\\"accessAuditRef\\\":\\\"audit-检索\\\",",
                "{\\\"accessAuditRef\\\":\\\"audit-检索\\\",\\\"accessAuditRef\\\":\\\"audit-检索\\\","));
        assertRejected(RETRIEVAL_GOLDEN + "null");
    }

    @Test
    void memoryJsonRejectsNumbersAtEveryPositionAndKeepsExactBudgets() {
        var limits = new MemoryJson.Limits(100, 10, 3);
        for (String input : List.of("1", "1.25", "[1]", "{\"a\":1}")) {
            assertThrows(IllegalArgumentException.class, () -> MemoryJson.parse(input, limits));
        }
        String input = "{\"a\":[null]}";
        assertEquals(input, MemoryJson.encode(MemoryJson.parse(input,
                new MemoryJson.Limits(input.length(), 4, 3))));
        assertThrows(IllegalArgumentException.class, () -> MemoryJson.parse(input,
                new MemoryJson.Limits(input.length(), 3, 3)));
    }

    @Test
    void strictJsonRejectsInvalidUnicodeAndEveryResourceLimit() {
        String unpairedSurrogate = "{\"a\":\"" + '\\' + "ud800\"}";
        assertThrows(IllegalArgumentException.class, () -> MemoryJson.parse(
                unpairedSurrogate, new MemoryJson.Limits(100, 10, 10)));

        assertThrows(IllegalArgumentException.class, () -> MemoryJson.parse(
                "\"1234\"", new MemoryJson.Limits(5, 10, 10)));
        assertThrows(IllegalArgumentException.class, () -> MemoryJson.parse(
                "[null,null]", new MemoryJson.Limits(100, 2, 10)));
        assertThrows(IllegalArgumentException.class, () -> MemoryJson.parse(
                "[[null]]", new MemoryJson.Limits(100, 10, 2)));

        String oversized = "\"" + "x".repeat(2_000_000) + "\"";
        assertRejected(oversized);
        assertEquals("MemoryJson.Value[REDACTED]", MemoryJson.string("private-body").toString());
    }

    @Test
    void encodersAcceptTheExactCodeUnitLimitAndRejectOneUnitOverBeforeBuildingOutput() {
        MemoryEventCodec.RetrievalSnapshot smallRetrieval = snapshotWithIntent("x");
        int retrievalFixed = MemoryEventCodec.retrievalPayload(smallRetrieval)
                .canonicalJson().length() - 1;
        int exactIntentLength = MemoryEventCodec.LIMITS.maxCodeUnits() - retrievalFixed;
        MemoryEventCodec.RetrievalSnapshot exactRetrieval = snapshotWithIntent(
                "x".repeat(exactIntentLength));

        assertEquals(MemoryEventCodec.LIMITS.maxCodeUnits(),
                MemoryEventCodec.retrievalPayload(exactRetrieval).canonicalJson().length());
        assertThrows(IllegalArgumentException.class, () -> MemoryEventCodec.retrievalPayload(
                snapshotWithIntent("x".repeat(exactIntentLength + 1))));

        MemoryFact fact = fullFact();
        MemoryFactDraft draft = fullDraft();
        var mutation = new MemoryRuntime.Mutation.Create(new IdempotencyKey("bounded-write"), draft);
        ResultRef smallAudit = new ResultRef("x");
        int writtenFixed = MemoryEventCodec.writtenPayload(
                mutation, Optional.empty(), fact, smallAudit, Optional.of(new ResultRef("audit-源")))
                .canonicalJson().length() - 1;
        int exactAuditLength = MemoryEventCodec.LIMITS.maxCodeUnits() - writtenFixed;

        assertEquals(MemoryEventCodec.LIMITS.maxCodeUnits(), MemoryEventCodec.writtenPayload(
                mutation,
                Optional.empty(),
                fact,
                new ResultRef("x".repeat(exactAuditLength)),
                Optional.of(new ResultRef("audit-源"))).canonicalJson().length());
        assertThrows(IllegalArgumentException.class, () -> MemoryEventCodec.writtenPayload(
                mutation,
                Optional.empty(),
                fact,
                new ResultRef("x".repeat(exactAuditLength + 1)),
                Optional.of(new ResultRef("audit-源"))));
    }

    @Test
    void hashUsesSha256WithANulDomainSeparator() {
        String actual = MemoryJson.hash("skillforge.test.memory.v1", MemoryJson.string("雪"));

        assertEquals("7d511a588bdf473cc1feebe893950f1958ccc9dcba01cd55d2d69c9fcb63ee74", actual);
        assertNotEquals("2df0ae19f09a787f8371914b606d79e98d4461cca943ea0e5d31cc0d211ea239", actual);
        assertThrows(IllegalArgumentException.class, () -> MemoryJson.hash(
                "bad" + '\0' + "schema", MemoryJson.nil()));
    }

    @Test
    void everyTypedOwnerRoundTripsAndGroupEnvelopeUsesRealSubject() {
        TenantId tenant = new TenantId("tenant-甲");
        SessionRef session = new SessionRef(tenant, "session-一");
        var group = new com.skillforge.runtime.api.GroupBindingRef(tenant, "group-一");
        for (MemoryOwner owner : List.of(new MemoryOwner.Session(session),
                new MemoryOwner.UserAgent(tenant, new EndUserId(7), new AgentId("agent-猫")),
                new MemoryOwner.User(tenant, new EndUserId(7)), new MemoryOwner.Group(group),
                new MemoryOwner.GroupMember(group, new EndUserId(8)))) {
            MemoryFact template = fullFact();
            MemoryFact fact = new MemoryFact(template.ref(), owner, template.type(), template.retentionClass(),
                    template.schemaVersion(), template.subject(), template.predicate(), template.object(),
                    template.sourceEventIds(), template.sourceSessionRef(), template.sourceExecutionRef(),
                    template.exportPath(), template.extractorVersion(), template.revision(), template.createdAt(),
                    template.validFrom(), template.validTo(), template.expiresAt(), template.confidence(),
                    template.importance(), template.reviewRequired(), template.derivedFrom(), template.supersededBy(),
                    template.decayScore(), template.status());
            MemoryQuery query = new MemoryQuery(QUERY_ID,
                owner,
                Optional.empty(),
                "read",
                Optional.empty(),
                Set.of(owner.scope()),
                Set.of(template.type()),
                MemoryQueryProfile.DEFAULT,
                AS_OF,
                MemoryRetrievalPolicy.lexicalV1(),
                3,
                123,
                456,
                true);
            var snapshot = new MemoryEventCodec.RetrievalSnapshot(query,
                List.of(fact),
                new ResultRef("owner-result"),
                EVALUATED_AT,
                MemoryRuntime.Phase.CANDIDATE,
                new MemoryRetrievalTrace(QUERY_ID, List.of(fact.ref().factId()), List.of(), List.of(), List.of()),
                new ResultRef("owner-audit"),
                testDiagnostics(List.of(fact)));
            TypedPayload encoded = MemoryEventCodec.retrievalPayload(snapshot);
            assertEquals(snapshot, MemoryEventCodec.decodeRetrieval(encoded));
        }
        SessionOwner groupOwner = new SessionOwner(session, com.skillforge.runtime.api.SessionSubject.group(group),
                com.skillforge.runtime.api.SessionTarget.group(group));
        assertNotEquals(MemoryEventCodec.writtenEventId(fullRunIdentity().owner(), new IdempotencyKey("group-write")),
                MemoryEventCodec.writtenEventId(groupOwner, new IdempotencyKey("group-write")));
    }

    @Test
    void freezesHybridPolicyTaskConstraintAndRawDiagnosticsWithoutUsingOldSchema() {
        var base = fullInjectedSnapshot();
        var query = base.query();
        var policy = new MemoryRetrievalPolicy("hybrid", "7", "memory-body-v1", "lucene-cjk-9.12.3-v1", 32, 60, 0.72,
                Optional.of(new MemoryRetrievalPolicy.EmbeddingModel("embedding-profile", 4, 3)),
                MemoryRetrievalPolicy.VectorFailurePolicy.LEXICAL_FALLBACK);
        var frozenQuery = new MemoryQuery(query.queryId(), query.owner(), query.subjectEndUserId(), query.intent(),
                Optional.of(new com.skillforge.runtime.api.MemoryTaskConstraint("task-actual")), query.scopes(), query.types(),
                query.profile(), query.asOf(), policy, query.maxItems(), query.tokenBudget(), query.characterBudget(), query.requireProvenance());
        var fact = base.facts().getFirst();
        var diagnostics = new com.skillforge.runtime.api.MemoryRetrievalDiagnostics(
                com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Mode.HYBRID, Optional.empty(), 4, 4,
                List.of(new com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Candidate(fact.ref(), fact.revision(),
                        Optional.of(2.5), Optional.of(2), Optional.of(0.93), Optional.of(1), 1d / 62 + 1d / 61)),
                List.of(fact.ref().factId()));
        var snapshot = new MemoryEventCodec.RetrievalSnapshot(frozenQuery, base.facts(), base.resultRef(), base.evaluatedAt(),
                base.phase(), base.trace(), base.accessAuditRef(), diagnostics);
        TypedPayload payload = MemoryEventCodec.retrievalPayload(snapshot);
        assertEquals("skillforge.runtime.memory.retrieval-performed.v3", payload.schemaId());
        assertEquals(snapshot, MemoryEventCodec.decodeRetrieval(payload));
        assertThrows(IllegalStateException.class, () -> MemoryEventCodec.decodeRetrieval(
                new TypedPayload("skillforge.runtime.memory.retrieval-performed.v2", payload.canonicalJson())));
    }

    @Test
    void acceptanceSnapshotRetainsBothFactReferencesAndCasVersions() {
        MemoryFact before = fullFact();
        MemoryFact proposal = fullFact();
        var proposalRef = new MemoryFactRef(before.ref().tenantId(), "existing-proposal");
        var mutation = new MemoryRuntime.Mutation.AcceptRevision(new IdempotencyKey("accept-stored-proposal"),
                before.ref(), 7, proposalRef, 2, before.scope(), before.type());
        TypedPayload payload = MemoryEventCodec.writtenPayload(mutation, Optional.of(before), proposal,
                new ResultRef("accept-audit"), Optional.of(new ResultRef("accept-source-audit")));
        MemoryJson.Value root = MemoryJson.parse(payload.canonicalJson(), MemoryEventCodec.LIMITS);
        assertEquals("ACCEPT_REVISION", root.field("operation").stringValue());
        MemoryJson.Value command = root.field("command").requireFields("current", "expectedRevision", "proposal", "expectedProposalRevision");
        assertEquals(before.ref().factId(), command.field("current").field("factId").stringValue());
        assertEquals("7", command.field("expectedRevision").stringValue());
        assertEquals(proposalRef.factId(), command.field("proposal").field("factId").stringValue());
        assertEquals("2", command.field("expectedProposalRevision").stringValue());
        assertEquals(true, MemoryEventCodec.matchesWritten(payload, mutation, Optional.of(before), proposal));
    }

    private static MemoryEventCodec.RetrievalSnapshot snapshot(
            Set<MemoryScope> scopes,
            Set<MemoryTypeRef> types) {
        MemoryQuery query = new MemoryQuery(QUERY_ID,
                testQueryOwner(new TenantId("tenant-甲"), new EndUserId(7), new AgentId("agent-猫"), java.util.Optional.of(new SessionRef(new TenantId("tenant-甲"), "session-一"))),
                Optional.empty(),
                "记住 \"A\"\\B\n下一行",
                Optional.empty(),
                scopes,
                types,
                MemoryQueryProfile.DEFAULT,
                AS_OF,
                MemoryRetrievalPolicy.lexicalV1(),
                3,
                123,
                456,
                true);
        return new MemoryEventCodec.RetrievalSnapshot(query,
                List.of(),
                new ResultRef("result-检索"),
                EVALUATED_AT,
                MemoryRuntime.Phase.CANDIDATE,
                new MemoryRetrievalTrace(QUERY_ID, List.of(), List.of(), List.of(), List.of()),
                new ResultRef("audit-检索"),
                testDiagnostics(List.of()));
    }

    private static MemoryEventCodec.RetrievalSnapshot snapshotWithIntent(String intent) {
        MemoryEventCodec.RetrievalSnapshot original = snapshot(
                orderedSet(MemoryScope.USER_AGENT, MemoryScope.SESSION),
                orderedSet(new MemoryTypeRef("偏好\"\\"), new MemoryTypeRef("alpha")));
        MemoryQuery query = original.query();
        MemoryQuery changed = new MemoryQuery(query.queryId(),
                query.owner(),
                query.subjectEndUserId(),
                intent,
                Optional.empty(),
                query.scopes(),
                query.types(),
                query.profile(),
                query.asOf(),
                query.retrievalPolicy(),
                query.maxItems(),
                query.tokenBudget(),
                query.characterBudget(),
                query.requireProvenance());
        return new MemoryEventCodec.RetrievalSnapshot(changed,
                original.facts(),
                original.resultRef(),
                original.evaluatedAt(),
                original.phase(),
                original.trace(),
                original.accessAuditRef(),
                testDiagnostics(original.facts()));
    }

    private static MemoryEventCodec.RetrievalSnapshot fullInjectedSnapshot() {
        MemoryEventCodec.RetrievalSnapshot candidate = snapshot(
                orderedSet(MemoryScope.USER_AGENT, MemoryScope.SESSION),
                orderedSet(new MemoryTypeRef("偏好\"\\"), new MemoryTypeRef("alpha")));
        return new MemoryEventCodec.RetrievalSnapshot(candidate.query(),
                List.of(fullFact()),
                candidate.resultRef(),
                candidate.evaluatedAt(),
                MemoryRuntime.Phase.INJECTED,
                new MemoryRetrievalTrace(
                        QUERY_ID,
                        List.of("fact-完整"),
                        List.of("fact-完整"),
                        List.of("fact-完整"),
                        List.of()),
                candidate.accessAuditRef(),
                testDiagnostics(List.of(fullFact())));
    }

    private static MemoryFact fullFact() {
        TenantId tenant = new TenantId("tenant-甲");
        SessionRef session = new SessionRef(tenant, "session-一");
        return new MemoryFact(new MemoryFactRef(tenant, "fact-完整"),
                testOwner((new MemoryFactRef(tenant, "fact-完整")).tenantId(), new EndUserId(7), Optional.of(new AgentId("agent-猫")), Optional.of(session), MemoryScope.SESSION),
                new MemoryTypeRef("偏好\"\\"),
                new RetentionClassRef("retention-长"),
                "v1",
                "subject-\"雪\"",
                "predicate-\\path",
                new TypedPayload("fact.payload.v1", "{\"a\":\"雪\",\"z\":\"line\\nnext\"}"),
                List.of(new EventId("source-一"), new EventId("source-二")),
                session,
                Optional.of(new ExecutionRef(tenant, "execution-一")),
                Optional.of("$.facts[\"偏好\"]"),
                "extractor-一",
                9_007_199_254_740_993L,
                Instant.parse("2026-09-08T00:59:59.123456789Z"),
                Instant.parse("2026-09-08T01:00:00Z"),
                Optional.of(Instant.parse("2026-09-08T02:00:00Z")),
                Optional.of(Instant.parse("2027-09-08T00:00:00Z")),
                0.875,
                0.625,
                true,
                Optional.of(new MemoryFactRef(tenant, "fact-来源")),
                Optional.of(new MemoryFactRef(tenant, "fact-后继")),
                0.125,
                FactStatus.ACTIVE);
    }

    private static MemoryFactDraft fullDraft() {
        TenantId tenant = new TenantId("tenant-甲");
        SessionRef session = new SessionRef(tenant, "session-一");
        return new MemoryFactDraft(testOwner((session).tenantId(), new EndUserId(7), Optional.of(new AgentId("agent-猫")), Optional.of(session), MemoryScope.SESSION),
                new MemoryTypeRef("偏好\"\\"),
                new RetentionClassRef("retention-长"),
                "v1",
                "subject-\"雪\"",
                "predicate-\\path",
                new TypedPayload("fact.payload.v1", "{\"a\":\"雪\",\"z\":\"line\\nnext\"}"),
                List.of(new EventId("source-一"), new EventId("source-二")),
                session,
                Optional.of(new ExecutionRef(tenant, "execution-一")),
                Optional.of("$.facts[\"偏好\"]"),
                "extractor-一",
                Instant.parse("2026-09-08T01:00:00Z"),
                Optional.of(Instant.parse("2026-09-08T02:00:00Z")),
                Optional.of(Instant.parse("2027-09-08T00:00:00Z")),
                0.875,
                0.625,
                true,
                Optional.of(new MemoryFactRef(tenant, "fact-来源")),
                FactStatus.PENDING_CONFIRM);
    }

    private static RunIdentity fullRunIdentity() {
        TenantId tenant = new TenantId("tenant-甲");
        SessionOwner owner = SessionOwner.user(
                new SessionRef(tenant, "session-一"), new AgentId("agent-猫"), new EndUserId(7));
        TraceEnvelope trace = new TraceEnvelope(
                "trace-雪", "span-壹", new InputId("input-根"), Optional.of(new EventId("event-因")));
        return new RunIdentity(owner, owner.target().requireAgent(), new InputId("input-因"), new RunRef(tenant, "run-一"), trace, new ResultRef("identity-验"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), new InputId("input-因").value()));
    }

    private static <T> Set<T> orderedSet(T first, T second) {
        return new LinkedHashSet<>(List.of(first, second));
    }

    private static void assertRejected(String json) {
        assertThrows(IllegalStateException.class, () -> MemoryEventCodec.decodeRetrieval(
                new TypedPayload(MemoryEventCodec.RETRIEVAL_SCHEMA, json)));
    }

    private static MemoryOwner testOwner(com.skillforge.runtime.api.TenantId tenant,
            com.skillforge.runtime.api.EndUserId user, Optional<com.skillforge.runtime.api.AgentId> agent,
            Optional<com.skillforge.runtime.api.SessionRef> session, MemoryScope scope) {
        java.util.Objects.requireNonNull(session);
        return switch (scope) {
            case SESSION -> new MemoryOwner.Session(session.orElseThrow(() -> new IllegalArgumentException("session required")));
            case USER_AGENT -> {
                if (session.isPresent()) throw new IllegalArgumentException("agent fact has no session owner");
                yield new MemoryOwner.UserAgent(tenant, user, agent.orElseThrow(() -> new IllegalArgumentException("agent required")));
            }
            case USER -> {
                if (session.isPresent() || agent.isPresent()) throw new IllegalArgumentException("user owner has no agent/session");
                yield new MemoryOwner.User(tenant, user);
            }
            default -> throw new IllegalArgumentException("use typed group owner in group fixtures");
        };
    }

    private static MemoryOwner testQueryOwner(com.skillforge.runtime.api.TenantId tenant,
            com.skillforge.runtime.api.EndUserId user, com.skillforge.runtime.api.AgentId agent,
            Optional<com.skillforge.runtime.api.SessionRef> session) {
        java.util.Objects.requireNonNull(session);
        if (session.isPresent()) {
            if (!session.orElseThrow().tenantId().equals(tenant)) throw new IllegalArgumentException("foreign session");
            return new MemoryOwner.Session(session.orElseThrow());
        }
        return new MemoryOwner.UserAgent(tenant, user, agent);
    }

    /** 受控 fixture 的词法候选诊断，不表示真实检索得分。 */
    private static com.skillforge.runtime.api.MemoryRetrievalDiagnostics testDiagnostics(List<MemoryFact> facts) {
        var candidates = new java.util.ArrayList<com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Candidate>();
        for (int index = 0; index < facts.size(); index++) {
            MemoryFact fact = facts.get(index);
            candidates.add(new com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Candidate(fact.ref(), fact.revision(),
                    Optional.of(1d), Optional.of(index + 1), Optional.empty(), Optional.empty(), 1d / (61 + index)));
        }
        return new com.skillforge.runtime.api.MemoryRetrievalDiagnostics(com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Mode.LEXICAL,
                Optional.empty(), facts.size(), 0, candidates, facts.stream().map(fact -> fact.ref().factId()).toList());
    }
}
