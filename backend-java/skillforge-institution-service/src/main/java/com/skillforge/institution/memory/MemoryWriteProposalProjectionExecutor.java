package com.skillforge.institution.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalProjectionExecutor;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.institution.runtime.AgentRuntimeHostContracts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import com.skillforge.institution.memory.MemoryProposalSourceMapper.Source;
import org.springframework.stereotype.Component;

/** 将已审核 MEMORY 提案写入 Runtime Memory 事实源，不回退宿主旧存储。 */
@Component
public final class MemoryWriteProposalProjectionExecutor
        implements KnowledgeWriteProposalProjectionExecutor {
    private static final Logger log = LoggerFactory.getLogger(MemoryWriteProposalProjectionExecutor.class);
    private final MemoryProposalSourceMapper mapper;
    private final MemoryRuntime runtime;
    private final ObjectMapper json;
    private final MemoryCandidateGovernance governance;

    public MemoryWriteProposalProjectionExecutor(
            MemoryProposalSourceMapper mapper, MemoryRuntime runtime, ObjectMapper json,
            MemoryCandidateGovernance governance) {
        this.mapper = mapper;
        this.runtime = runtime;
        this.json = json;
        this.governance = governance;
    }

    @Override
    public boolean supports(KnowledgeWriteProposal proposal) {
        return proposal != null && proposal.target() == KnowledgeWriteProposal.Target.MEMORY;
    }

    @Override
    public ProjectionResult project(String tenantId, KnowledgeWriteProposal proposal) {
        log.debug("记忆提案投影开始 tenant={}", tenantId);
        try {
            Source source = source(tenantId, proposal.sourceEventId());
            Map<?, ?> body = object(proposal.payload().get("memory"), "payload.memory");
            String typeValue = text(body, "type");
            if (!Set.of("preference", "episode").contains(typeValue)) {
                throw invalid("memory.type 尚未注册");
            }
            Object object = body.get("object");
            if (!(object instanceof Map<?, ?>)) {
                throw invalid("memory.object 必须为结构化对象");
            }
            TenantId tenant = new TenantId(tenantId);
            Optional<String> exportPath = exportPath(proposal.payload());
            String subject = text(body, "subject");
            String predicate = text(body, "predicate");
            String objectJson = MemoryContent.encode(MemoryContent.explicitContent(json.valueToTree(object), typeValue));
            PortCallContext context = context(tenant, source);
            MemoryTypeRef type = new MemoryTypeRef(typeValue);
            SessionRef sourceSession = new SessionRef(tenant, source.sessionId());
            MemoryFactDraft draft = new MemoryFactDraft(
                    new com.skillforge.runtime.api.MemoryOwner.UserAgent(tenant,new EndUserId(source.endUserId()),new AgentId(source.agentId())), type,
                    new RetentionClassRef("standard"), AgentRuntimeHostContracts.MEMORY_SCHEMA_VERSION, subject,
                    predicate, new TypedPayload(
                            "preference".equals(typeValue)
                                    ? AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD
                                    : AgentRuntimeHostContracts.MEMORY_EPISODE_PAYLOAD,
                            objectJson),
                    List.of(new EventId(proposal.sourceEventId())),
                    sourceSession,
                    optional(source.executionId()).map(value -> new ExecutionRef(tenant, value)),
                    exportPath, "knowledge-write-proposal-v1", Instant.now(), Optional.empty(),
                    Optional.empty(), unit(body, "confidence", 0.8),
                    unit(body, "importance", 0.5), false, Optional.empty(), FactStatus.ACTIVE);
            MemoryRuntime.MutationResult result = governance.withSlot(context, draft, () -> {
                boolean review = governance.evaluateStructured(context, draft, Boolean.TRUE.equals(body.get("sensitive")))
                        .reviewRequired();
                MemoryFactDraft accepted = review ? new MemoryFactDraft(draft.owner(), draft.type(), draft.retentionClass(), draft.schemaVersion(),
                        draft.subject(), draft.predicate(), draft.object(), draft.sourceEventIds(), draft.sourceSessionRef(),
                        draft.sourceExecutionRef(), draft.exportPath(), draft.extractorVersion(), draft.validFrom(), draft.validTo(),
                        draft.expiresAt(), draft.confidence(), draft.importance(), true, draft.derivedFrom(), FactStatus.PENDING_CONFIRM) : draft;
                return runtime.mutate(new MemoryRuntime.MutationCall(context, new MemoryRuntime.Mutation.Create(
                        new IdempotencyKey("knowledge-write-proposal:" + proposal.proposalId()), accepted), draft.validFrom()));
            });
            if (result instanceof MemoryRuntime.MutationResult.Recorded recorded) {
                log.debug("记忆提案投影完成 tenant={} status={} revision={}", tenantId, recorded.fact().status(), recorded.fact().revision());
                return ProjectionResult.applied(SnowflakeIds.nextId(),
                        Map.of("fact_id", recorded.fact().ref().factId(),
                                "revision", recorded.fact().revision(),
                                "status", recorded.fact().status().name()));
            }
            return ProjectionResult.failed(reason(result));
        } catch (Exception exception) {
            log.warn("记忆提案投影失败 tenant={} type={}", tenantId, exception.getClass().getSimpleName());
            return ProjectionResult.failed("memory_write_proposal_failed");
        }
    }

    private Source source(String tenantId, String eventId) {
        List<Source> values = mapper.sources(tenantId, eventId);
        if (values.size() != 1) throw invalid("Memory 提案来源事件不存在");
        return values.getFirst();
    }

    private PortCallContext context(TenantId tenant, Source source) {
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, source.sessionId()),
                new AgentId(source.agentId()), new EndUserId(source.endUserId()));
        String suffix = Long.toUnsignedString(SnowflakeIds.nextId());
        InputId input = new InputId("memory-proposal:" + suffix);
        RunIdentity run = new RunIdentity(owner, new AgentId(source.agentId()), input,
                new RunRef(tenant, "memory-proposal:" + suffix),
                new TraceEnvelope("memory-proposal:" + suffix, "memory-proposal:" + suffix,
                        input, Optional.empty()),
                new com.skillforge.runtime.api.ResultRef("memory-proposal-identity:" + suffix),
                new com.skillforge.runtime.api.RunRequester.System(
                        new PrincipalRef.SystemPrincipal(tenant, "memory-proposal-governance")),
                Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(tenant, "memory-proposal:" + suffix));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.SystemPrincipal(tenant, "memory-proposal-governance"), owner,
                Set.of("memory:manage"), 1,
                new com.skillforge.runtime.api.ResultRef("memory-proposal-verified:" + suffix));
        return new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false));
    }

    private Optional<String> exportPath(Map<String, Object> payload) {
        Map<?, ?> source = object(payload.get("source"), "payload.source");
        return optional(source.get("export_path"));
    }

    private Map<?, ?> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw invalid(field + " 必须为对象");
        return map;
    }

    private String text(Map<?, ?> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw invalid(field + " 不能为空");
        return text.trim();
    }

    private double unit(Map<?, ?> map, String field, double fallback) {
        Object value = map.get(field);
        double result = value instanceof Number number ? number.doubleValue() : fallback;
        if (!Double.isFinite(result) || result < 0 || result > 1) throw invalid(field + " 无效");
        return result;
    }

    private Optional<String> optional(Object value) {
        return value instanceof String text && !text.isBlank()
                ? Optional.of(text.trim()) : Optional.empty();
    }

    private String reason(MemoryRuntime.MutationResult result) {
        return switch (result) {
            case MemoryRuntime.MutationResult.Rejected value -> value.failure().reasonCode();
            case MemoryRuntime.MutationResult.Conflict ignored -> "memory_revision_conflict";
            case MemoryRuntime.MutationResult.Pending value -> value.failure().reasonCode();
            case MemoryRuntime.MutationResult.Uncertain value -> value.failure().reasonCode();
            case MemoryRuntime.MutationResult.Stopped value -> value.failure().reasonCode();
            case MemoryRuntime.MutationResult.Recorded ignored -> "memory_write_proposal_failed";
        };
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

}
