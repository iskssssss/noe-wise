package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.knowledge.application.KnowledgeWriteProposalService;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.institution.memory.MemoryGovernanceService;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V79 提案队列的真实迁移、RLS 和审核 CAS 集成验证。 */
@org.springframework.context.annotation.Import(KnowledgeEmbeddingFixture.Configuration.class)
class KnowledgeWriteProposalIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private KnowledgeWriteProposalService proposalService;

    @Autowired
    private EndUserService endUserService;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private MemoryGovernanceService memoryGovernance;

    @Autowired private DataSource dataSource;
    @Autowired private PlatformModelCatalogService models;

    private KnowledgeEmbeddingFixture embeddingFixture;

    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() {
        PlanDO plan = ensureFreePlan(planMapper);
        tenantA = createTenant(tenantMapper, planMapper, "write-proposal-a-" + SnowflakeIds.nextId());
        tenantB = createTenant(tenantMapper, planMapper, "write-proposal-b-" + SnowflakeIds.nextId());
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        if (embeddingFixture != null) embeddingFixture.close();
        TenantContext.clear();
    }

    @Test
    void memoryProposalProjectsThroughRuntimeAndReviewRemainsIdempotent() throws Exception {
        TenantContext.setTenantId(tenantA);
        long userId = endUserService.create(tenantA, "知识提案测试用户").getId();
        appendObservation(tenantA, userId, "source-event-1");
        KnowledgeWriteProposal proposal = new KnowledgeWriteProposal(
                "integration-proposal-" + SnowflakeIds.nextId(), KnowledgeWriteProposal.Target.MEMORY,
                "source-event-1", "bundle-1",
                Map.of("source", Map.of("kind", "observation",
                                "observation_type", "inbound.message"),
                        "memory", Map.of("type", "preference", "subject", "user:" + userId,
                                "predicate", "theme", "object", Map.of("value", "dark"))),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);

        assertEquals(KnowledgeWriteProposal.Status.PENDING_CONFIRM,
                proposalService.create(tenantA, proposal).status());
        assertEquals(1, proposalService.listPending(tenantA).size());
        var approved = proposalService.approve(tenantA, proposal.proposalId(), "admin-1", "confirmed");
        assertEquals(KnowledgeWriteProposal.Status.APPROVED, approved.proposal().status());
        assertEquals("applied", approved.projectionStatus());
        assertTrue(approved.projectionRef() != null);
        assertNull(approved.projectionError());
        var replay = proposalService.approve(tenantA, proposal.proposalId(), "admin-1", "retry");
        assertEquals(approved.projectionRef(), replay.projectionRef());
        assertEquals("applied", replay.projectionStatus());
        assertEquals(approved.projectionError(), replay.projectionError());
        assertEquals(0, proposalService.listPending(tenantA).size());
        assertFalse(proposalService.listPending(tenantB).stream()
                .anyMatch(item -> item.proposalId().equals(proposal.proposalId())));
        var firstMemory = memoryGovernance.list(tenantA, null, 20);
        assertEquals(1, firstMemory.size());
        assertEquals("ACTIVE", firstMemory.getFirst().status().name());
        assertEquals("v2", firstMemory.getFirst().schemaVersion());
        assertEquals("skillforge.memory.preference.v2", firstMemory.getFirst().object().schemaId());
        assertEquals("dark", new com.fasterxml.jackson.databind.ObjectMapper().readTree(firstMemory.getFirst().object().canonicalJson()).path("value").path("value").asText());

        appendEvent(tenantA, userId, "source-event-conflict",
                "agent-source-event-1", "session-source-event-conflict",
                "{\"text\":\"approved observation\"}");
        KnowledgeWriteProposal conflict = new KnowledgeWriteProposal(
                "integration-proposal-conflict-" + SnowflakeIds.nextId(),
                KnowledgeWriteProposal.Target.MEMORY, "source-event-conflict", "bundle-conflict",
                Map.of("source", Map.of("kind", "observation",
                                "observation_type", "inbound.message"),
                        "memory", Map.of("type", "preference", "subject", "user:" + userId,
                                "predicate", "theme", "object", Map.of("value", "light"))),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);
        proposalService.create(tenantA, conflict);
        var conflictResult = proposalService.approve(
                tenantA, conflict.proposalId(), "admin-1", "source accepted");
        assertEquals("applied", conflictResult.projectionStatus());
        var pendingMemory = memoryGovernance.list(tenantA, "PENDING_CONFIRM", 20);
        assertEquals(1, pendingMemory.size());
        assertTrue(pendingMemory.getFirst().reviewRequired());
    }

    @Test
    void approvedInstitutionalKnowledgeProposalPublishesActiveRevision() throws Exception {
        TenantContext.setTenantId(tenantA);
        embeddingFixture = new KnowledgeEmbeddingFixture(models, tenantA);
        long userId = endUserService.create(tenantA, "机构知识提案用户").getId();
        appendObservation(tenantA, userId, "source-governance-1");
        Long kbId = Long.valueOf(String.valueOf(
                knowledgeService.createBase(tenantA, "治理投影库", null).id()));
        String proposalId = "integration-governance-" + SnowflakeIds.nextId();
        KnowledgeWriteProposal proposal = new KnowledgeWriteProposal(
                proposalId, KnowledgeWriteProposal.Target.INSTITUTIONAL_KNOWLEDGE,
                "source-governance-1", "bundle-governance-1",
                Map.of("source", Map.of("kind", "observation",
                                "observation_type", "inbound.message"),
                        "projection_type", "institutional_knowledge",
                        "institutional_knowledge", Map.of("kb_id", kbId,
                                "title", "审批后的政策", "content", "审批后的政策正文",
                                "capability_policy", Map.of(
                                        "model_invocable", true,
                                        "user_invocable", false,
                                        "allowed_operators", List.of("VECTOR"),
                                        "freshness_policy", "PINNED",
                                        "citation_required", true))),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);

        proposalService.create(tenantA, proposal);
        var approved = proposalService.approve(tenantA, proposalId, "admin-governance", "通过");

        assertEquals("applied", approved.projectionStatus());
        assertFalse(approved.projectionRef() == null);
        assertFalse(knowledgeService.getBase(tenantA, kbId).activeRevisionId() == null);
        var search = knowledgeService.search(tenantA, String.valueOf(kbId), "政策", 5, null);
        assertTrue(search.stream()
                .map(item -> item.content())
                .anyMatch("审批后的政策正文"::equals));
        var replay = proposalService.approve(tenantA, proposalId, "admin-governance", "重放");
        assertEquals(approved.projectionRef(), replay.projectionRef());
        assertEquals("applied", replay.projectionStatus());
    }

    @Test
    void graphProjectionIsRejectedWithoutLegacyCompatibility() {
        TenantContext.setTenantId(tenantA);
        long userId = endUserService.create(tenantA, "旧图谱提案用户").getId();
        appendObservation(tenantA, userId, "source-governance-graph-1");
        String proposalId = "integration-governance-graph-" + SnowflakeIds.nextId();
        KnowledgeWriteProposal proposal = new KnowledgeWriteProposal(
                proposalId, KnowledgeWriteProposal.Target.INSTITUTIONAL_KNOWLEDGE,
                "source-governance-graph-1", "bundle-governance-graph-1",
                Map.of("source", Map.of("kind", "observation",
                                "observation_type", "inbound.message"),
                        "projection_type", "graph",
                        "graph", Map.of("source", "governance-test",
                                "entities", List.of(Map.of("type", "Policy", "id", "POL-001",
                                        "attributes", Map.of("policy_id", "POL-001", "title", "治理政策"))))),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);

        proposalService.create(tenantA, proposal);
        var approved = proposalService.approve(tenantA, proposalId, "admin-governance", "通过");

        assertEquals("failed", approved.projectionStatus());
        assertNull(approved.projectionRef());
        assertEquals("projection_type 仅支持 institutional_knowledge", approved.projectionError());
    }

    @Test
    void evidenceRefMustMatchImmutableBundleAndCommittedSessionEvent() throws Exception {
        TenantContext.setTenantId(tenantA);
        long userId = endUserService.create(tenantA, "证据写回用户").getId();
        String bundleId = "write-bundle-" + SnowflakeIds.nextId();
        insertKnowledgeResultFixture(bundleId, 2, "item-1");
        appendEvent(tenantA, userId, "source-evidence-1",
                "{\"evidenceRef\":{\"bundleId\":\"" + bundleId
                        + "\",\"version\":2,\"itemIds\":[\"item-1\"]}}");

        KnowledgeWriteProposal valid = evidenceProposal(
                "evidence-valid-" + SnowflakeIds.nextId(), bundleId, "item-1");
        assertEquals(KnowledgeWriteProposal.Status.PENDING_CONFIRM,
                proposalService.create(tenantA, valid).status());

        KnowledgeWriteProposal uncommittedItem = evidenceProposal(
                "evidence-invalid-" + SnowflakeIds.nextId(), bundleId, "item-missing");
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> proposalService.create(tenantA, uncommittedItem));
    }

    @Test
    void observationExportPathMustResolveInsideCommittedSafeOutput() {
        TenantContext.setTenantId(tenantA);
        long userId = endUserService.create(tenantA, "导出来源验证用户").getId();
        String canonicalOutput = "{\"result\":{\"theme\":\"dark\"},"
                + "\"unrelated\":\"$.result.missing\"}";
        appendResultEvent(tenantA, userId, "source-export-valid", "execution-export-valid",
                canonicalOutput);
        KnowledgeWriteProposal valid = observationProposal(
                "observation-valid-" + SnowflakeIds.nextId(), "source-export-valid",
                "execution-export-valid", "$.result.theme");
        assertEquals(KnowledgeWriteProposal.Status.PENDING_CONFIRM,
                proposalService.create(tenantA, valid).status());

        KnowledgeWriteProposal collision = observationProposal(
                "observation-collision-" + SnowflakeIds.nextId(), "source-export-valid",
                "execution-export-valid", "$.result.missing");
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> proposalService.create(tenantA, collision));
    }

    private KnowledgeWriteProposal evidenceProposal(String proposalId, String bundleId, String itemId) {
        return new KnowledgeWriteProposal(proposalId, KnowledgeWriteProposal.Target.MEMORY,
                "source-evidence-1", bundleId,
                Map.of("source", Map.of("kind", "evidence_ref", "version", 2,
                                "item_ids", List.of(itemId)),
                        "memory", Map.of("type", "preference", "subject", "user:evidence",
                                "predicate", "theme", "object", Map.of("value", "light"))),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);
    }

    private KnowledgeWriteProposal observationProposal(String proposalId, String eventId,
            String executionId, String exportPath) {
        return new KnowledgeWriteProposal(proposalId, KnowledgeWriteProposal.Target.MEMORY,
                eventId, "observation-bundle",
                Map.of("source", Map.of("kind", "observation",
                                "observation_type", "agent.tool-result",
                                "execution_id", executionId, "export_path", exportPath),
                        "memory", Map.of("type", "preference", "subject", "user:export",
                                "predicate", "theme", "object", Map.of("value", "dark"))),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);
    }

    private void appendResultEvent(String tenantId, long userId, String eventId,
            String executionId, String canonicalOutput) {
        String payload = "{\"status\":\"SUCCEEDED\",\"output\":{"
                + "\"schemaId\":\"test.safe-output.v1\",\"canonicalJson\":"
                + jsonString(canonicalOutput) + "}}";
        TenantId tenant = new TenantId(tenantId);
        InputId input = new InputId("input-" + eventId);
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session-" + eventId),
                new AgentId("agent-" + eventId), new EndUserId(userId));
        SessionProjectionTarget target = new SessionProjectionTarget(owner, input, Optional.empty(),
                Optional.of(new ExecutionRef(tenant, executionId)), Optional.empty(), Optional.empty());
        SessionEventDraft event = new SessionEventDraft(new EventId(eventId), "agent.tool-result", "1",
                "fingerprint-" + eventId,
                new TraceEnvelope("trace-" + eventId, "span-" + eventId, input, Optional.empty()),
                new TypedPayload("skillforge.runtime.agent.tool-result.v1", payload), Instant.now());
        var result = new JdbcSessionLogPort(dataSource).append(
                new SessionLogPort.AppendSessionEvent(target, event));
        assertTrue(result instanceof SessionLogPort.AppendResult.Appended);
    }

    private String jsonString(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 原 Knowledge result 的生产写入链由 adapter 测试覆盖；此处只隔离验证来源闭包。 */
    private void insertKnowledgeResultFixture(String bundleId, long version, String itemId)
            throws Exception {
        String bundle = "{\"bundleId\":\"" + bundleId + "\",\"version\":" + version
                + ",\"items\":[{\"itemId\":\"" + itemId + "\"}]}";
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("set session_replication_role=replica");
            try (var insert = connection.prepareStatement(
                    "insert into nw_runtime_knowledge_results(tenant_id,action_id,invocation_id,"
                            + "attempt_id,call_fingerprint,result_ref,bundle_data,bundle_json) "
                            + "values(?,?,?,?,?,?,?,?)")) {
                insert.setString(1, tenantA);
                insert.setString(2, "action-" + bundleId);
                insert.setString(3, "invocation-" + bundleId);
                insert.setString(4, "attempt-" + bundleId);
                insert.setString(5, "fingerprint-" + bundleId);
                insert.setString(6, "result-" + bundleId);
                insert.setString(7, "fixture");
                insert.setString(8, bundle);
                assertEquals(1, insert.executeUpdate());
            } finally {
                statement.execute("set session_replication_role=origin");
            }
        }
    }

    private void appendObservation(String tenantId, long userId, String eventId) {
        appendEvent(tenantId, userId, eventId, "{\"text\":\"approved observation\"}");
    }

    private void appendEvent(String tenantId, long userId, String eventId, String payload) {
        appendEvent(tenantId, userId, eventId, "agent-" + eventId,
                "session-" + eventId, payload);
    }

    private void appendEvent(String tenantId, long userId, String eventId,
            String agentId, String sessionId, String payload) {
        TenantId tenant = new TenantId(tenantId);
        InputId input = new InputId("input-" + eventId);
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, sessionId),
                new AgentId(agentId), new EndUserId(userId));
        SessionProjectionTarget target = new SessionProjectionTarget(owner, input, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        SessionEventDraft event = new SessionEventDraft(new EventId(eventId), "inbound.message", "v1",
                "fingerprint-" + eventId,
                new TraceEnvelope("trace-" + eventId, "span-" + eventId, input, Optional.empty()),
                new TypedPayload("skillforge.session.inbound.v1", payload),
                Instant.now());
        var result = new JdbcSessionLogPort(dataSource).append(
                new SessionLogPort.AppendSessionEvent(target, event));
        assertTrue(result instanceof SessionLogPort.AppendResult.Appended);
    }
}
