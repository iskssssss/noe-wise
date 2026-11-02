package com.skillforge.institution.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.core.agent.memory.MemoryJsonCodec;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExistingFact;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionInput;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionOutput;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Source;
import com.skillforge.institution.memory.MemoryExtractionMapper.CandidateRow;
import com.skillforge.institution.memory.MemoryExtractionMapper.Job;
import com.skillforge.institution.memory.MemoryExtractionMaterial.FactAlias;
import com.skillforge.institution.memory.MemoryExtractionMaterial.FrozenInput;
import com.skillforge.institution.memory.MemoryExtractionMaterial.PreparedData;
import com.skillforge.institution.runtime.AgentRuntimeHostContracts;
import com.skillforge.institution.group.GroupMemoryExtractionInput;
import com.skillforge.institution.group.GroupMemoryExtractionQueue;
import com.skillforge.institution.group.GroupMemoryExtractionMapper.Pending;
import com.skillforge.institution.group.GroupMemorySources;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort.ExtractionGovernance;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式来源队列只解释一次；冻结输入、治理草稿和 Runtime 原命令后按原回执恢复。 */
@Service
public final class MemoryExtractionWorker {
    private static final String EXTRACTOR = "memory-extractor-v2";
    private static final String POLICY = "memory-extraction-policy-v2";
    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionWorker.class);
    private final MemoryExtractionMapper mapper;
    private final TransactionTemplate transactions;
    private final MemoryCandidateGovernance governance;
    private final MemoryRuntime runtime;
    private final MemoryExtractionAgent extractor;
    private final MemoryExtractionReuse reuse;
    private final MemoryExtractionCandidates contract;
    private final MemoryExtractionPolicyMapper extractionPolicy;
    private final ActiveMemoryTenantScan tenants;
    private final GroupMemoryExtractionInput groups;
    private final GroupMemoryExtractionQueue groupQueue;

    public MemoryExtractionWorker(MemoryExtractionMapper mapper, PlatformTransactionManager transactions,
            MemoryCandidateGovernance governance, MemoryRuntime runtime, MemoryExtractionAgent extractor,
            MemoryExtractionReuse reuse, ObjectMapper json, MemoryExtractionPolicyMapper extractionPolicy,
            ActiveMemoryTenantMapper tenantMapper, GroupMemoryExtractionInput groups, GroupMemoryExtractionQueue groupQueue) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactions);
        this.governance = governance;
        this.runtime = runtime;
        this.extractor = extractor;
        this.reuse = reuse;
        this.contract = new MemoryExtractionCandidates(json);
        this.extractionPolicy = extractionPolicy;
        this.tenants = new ActiveMemoryTenantScan(tenantMapper);
        this.groups = groups;
        this.groupQueue = groupQueue;
    }

    @Scheduled(fixedDelayString = "${skillforge.memory.extraction-poll-interval:2000}")
    public void poll() {
        for (String tenant : tenants.next()) {
            String previous = TenantContext.getTenantId();
            TenantContext.setTenantId(tenant);
            try {
                enqueueGroup(tenant);
                transactions.executeWithoutResult(ignored -> mapper.compactTerminalCandidates(tenant, 200));
                Job job = transactions.execute(ignored -> {
                    Job next = mapper.next(tenant, EXTRACTOR, POLICY);
                    if (next == null) return null;
                    Job claimed = next.claimed(UUID.randomUUID().toString());
                    requireOne(mapper.claim(claimed, EXTRACTOR));
                    return claimed;
                });
                if (job != null) process(job);
            } catch (RuntimeException failure) {
                log.warn("记忆提取调度失败 event=memory_extraction_poll result=failed tenant={} type={}",
                        tenant, failure.getClass().getSimpleName());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
            }
        }
    }

    private void enqueueGroup(String tenant) {
        try {
            transactions.executeWithoutResult(ignored -> groupQueue.enqueue(tenant, EXTRACTOR, POLICY, this::context));
        } catch (RuntimeException unavailable) {
            // 群交接保留原pending与来源身份；不扩大成个人提取队列的停顿。
            log.warn("群记忆来源交接失败 event=group_memory_extraction_enqueue result=failed tenant={} type={}",
                    tenant, unavailable.getClass().getSimpleName());
        }
    }

    private void process(Job job) {
        try {
            if (!"inbound.message".equals(job.sourceEventType())
                    && !(job.group() && GroupMemorySources.EVENT_TYPE.equals(job.sourceEventType()))) {
                finish(job, "FAILED", null, null, "memory_observation_extraction_not_supported");
                return;
            }
            PortCallContext sourceContext = context(job);
            FrozenInput frozen = transactions.execute(ignored -> mapper.input(job, EXTRACTOR));
            InputMaterial modelInput = null;
            if (job.modelStatus() == null && !groupAllowed(job)) {
                finish(job, "WAITING", null, null, "memory_group_extraction_paused_or_unavailable");
                return;
            }
            if (frozen == null) {
                if (job.modelStatus() != null) throw new IllegalStateException("memory_extraction_material_missing");
                if ("inbound-hydrate".equals(job.memoryType()) && !automaticExtractionEnabled(job)) {
                    finish(job, "SKIPPED", null, null, "memory_automatic_extraction_disabled");
                    return;
                }
                FreezeResult preparedInput = freeze(job, sourceContext);
                if (preparedInput == null) {
                    finish(job, "SKIPPED", null, null, "memory_inbound_text_missing");
                    return;
                }
                frozen = preparedInput.material();
                modelInput = preparedInput.input();
                FrozenInput original = frozen;
                inClaim(job, () -> { requireOne(mapper.freezeInput(job, EXTRACTOR, original)); return null; });
            }
            if (!"COMPLETED".equals(job.modelStatus())) {
                if (modelInput == null) modelInput = restoreInput(job, sourceContext, frozen);
                if (!interpret(job, modelInput)) return;
            }
            FrozenInput original = frozen;
            List<CandidateRow> rows = transactions.execute(ignored -> mapper.candidates(job, EXTRACTOR));
            if (rows.isEmpty()) {
                finish(job, "SKIPPED", null, null, "memory_extraction_empty");
                return;
            }
            String lastFact = null;
            Long lastRevision = null;
            String suppressed = null;
            Instant occurredAt = original.sourceTime();
            for (CandidateRow row : rows) {
                if ("RECORDED".equals(row.status())) {
                    lastFact = row.factId(); lastRevision = row.factRevision(); continue;
                }
                if (Set.of("UNKNOWN", "FAILED").contains(row.status())) {
                    if (suppressed(row.errorCode())) { suppressed = row.errorCode(); continue; }
                    finish(job, row.status(), lastFact, lastRevision, row.errorCode()); return;
                }
                try {
                    // 模型耗时不占本地写入deadline；身份与请求仍使用原任务，只有调用控制期限更新。
                    PortCallContext context = context(job);
                    Candidate candidate = row.candidate();
                    PreparedData prepared = row.prepared() == null
                            ? prepare(job, context, original, candidate, row.ordinal()) : row.prepared();
                    MemoryFactDraft draft = draft(job, candidate, prepared);
                    WriteOutcome outcome = inClaim(job, () -> governance.withSlot(context, draft, () -> {
                        // CREATE可能已独立提交而父候选尚处PREPARED；先核原journal，不能因新差异阻断原ACK。
                        boolean recovering = "ACK_PENDING".equals(row.status())
                                || reuse.hasRecordedMutation(context, new IdempotencyKey(row.commandKey()));
                        // 确定原命令尚未记录时才重新核治理；已记录命令只按原草稿/键/时间恢复Runtime回执。
                        MemoryCandidateGovernance.Decision currentDecision = null;
                        if (!recovering) {
                            if (!groupAllowed(job)) throw new GroupPaused();
                            Optional<MemoryFact> target = referencedSnapshot(context, original, candidate);
                            currentDecision = evaluate(job, context, draft, candidate, target);
                        }
                        if (!draft.reviewRequired() && currentDecision != null && currentDecision.reviewRequired()) {
                            requireOne(mapper.candidateResult(job, EXTRACTOR, row.ordinal(), "FAILED", null, null,
                                    "memory_extraction_governance_changed"));
                            return new WriteOutcome("FAILED", null, "memory_extraction_governance_changed");
                        }
                        Supplier<WriteOutcome> write = () -> write(context, row.commandKey(), draft, occurredAt);
                        WriteOutcome result = "inbound-hydrate".equals(job.memoryType())
                                && "preference".equals(draft.type().value()) && !draft.reviewRequired()
                                && draft.derivedFrom().isEmpty() && !recovering
                                ? reuse.reuseOrCreate(context, draft, existing -> existing
                                        // 原CREATE可能已落库但尚未ACK；自己的来源必须恢复原Runtime命令。
                                        .map(value -> value.sourceEventIds().contains(new EventId(job.sourceEventId()))
                                                ? write.get() : new WriteOutcome("RECORDED", value, null)).orElseGet(write))
                                : write.get();
                        requireOne(mapper.candidateResult(job, EXTRACTOR, row.ordinal(), result.status(),
                                result.fact() == null ? null : result.fact().ref().factId(),
                                result.fact() == null ? null : result.fact().revision(), result.error()));
                        return result;
                    }));
                    if (!"RECORDED".equals(outcome.status())) {
                        if (suppressed(outcome.error())) { suppressed = outcome.error(); continue; }
                        finish(job, outcome.status(), lastFact, lastRevision, outcome.error()); return;
                    }
                    lastFact = outcome.fact().ref().factId(); lastRevision = outcome.fact().revision();
                } catch (IllegalArgumentException rejected) {
                    if (!suppressed(rejected.getMessage())) throw rejected;
                    suppressed = rejected.getMessage();
                    String reason = suppressed;
                    inClaim(job, () -> { requireOne(mapper.candidateResult(job, EXTRACTOR, row.ordinal(), "FAILED", null, null, reason)); return null; });
                }
            }
            finish(job, lastFact == null && suppressed != null ? "SKIPPED" : "COMPLETED", lastFact, lastRevision, suppressed);
        } catch (GroupPaused paused) {
            finish(job, "WAITING", null, null, "memory_group_extraction_paused_or_unavailable");
        } catch (FenceLost stopped) {
            log.debug("记忆提取领取已失效 event=memory_extraction result=fence_lost source_tag={}", tag(job));
        } catch (IllegalArgumentException invalid) {
            finish(job, "FAILED", null, null, stableReason(invalid, "memory_extraction_invalid"));
        } catch (RuntimeException uncertain) {
            // 可能已提交 Runtime 或模型结果；未知材料和原键保留，不转换成可重新解释的 READY。
            log.warn("记忆提取结果待核对 event=memory_extraction result=unknown source_tag={} type={}",
                    tag(job), uncertain.getClass().getSimpleName());
            finish(job, "UNKNOWN", null, null, "memory_extraction_outcome_unknown");
        }
    }

    private FreezeResult freeze(Job job, PortCallContext context) {
        SourceMaterial sourceMaterial = source(job, context);
        if (sourceMaterial.text().isBlank()) return null;
        Source source = new Source("s0", sourceMaterial.text(), sourceMaterial.occurredAt());
        var facts = job.group() ? reuse.relatedGroup(context, group(job), sourceMaterial.groupSubjects())
                : reuse.related(context, job.endUserId(), "user:" + job.endUserId());
        var aliases = new ArrayList<FactAlias>();
        var existing = new ArrayList<ExistingFact>();
        for (MemoryFact fact : facts) {
            String alias = "f" + aliases.size();
            aliases.add(new FactAlias(alias, fact.ref().factId(), fact.revision()));
            existing.add(existing(alias, fact));
        }
        ExtractionInput input = input(job, source, existing, sourceMaterial.groupSubjects());
        return new FreezeResult(new FrozenInput(source.occurredAt(), aliases, sourceMaterial.groupSubjects()),
                new InputMaterial(input, sourceMaterial.inlineCandidate()));
    }

    /** 来源与事实都是原不可变引用；恢复不再读取当前配置或current revision拼装新输入。 */
    private InputMaterial restoreInput(Job job, PortCallContext context, FrozenInput frozen) {
        SourceMaterial sourceMaterial = source(job, context, frozen.groupSubjects());
        if (sourceMaterial.text().isBlank()) throw new IllegalArgumentException("memory_inbound_text_missing");
        Source source = new Source("s0", sourceMaterial.text(), sourceMaterial.occurredAt());
        if (!source.occurredAt().equals(frozen.sourceTime())) throw new IllegalArgumentException("memory_source_time_changed");
        var existing = new ArrayList<ExistingFact>();
        for (FactAlias alias : frozen.facts()) existing.add(existing(alias.alias(), reuse.modelReference(context, alias)));
        return new InputMaterial(input(job, source, existing, frozen.groupSubjects()), sourceMaterial.inlineCandidate());
    }

    private ExtractionInput input(Job job, Source source, List<ExistingFact> existing, List<Long> subjects) {
        return job.group() ? contract.typedGroupInput(job.endUserId(),
                new MemoryExtractionCandidates.GroupContext(job.groupBindingId(), subjects), List.of(source), existing)
                : contract.typedInput(job.endUserId(), List.of(source), existing);
    }

    private SourceMaterial source(Job job, PortCallContext context) {
        return source(job, context, null);
    }

    private SourceMaterial source(Job job, PortCallContext context, List<Long> frozenSubjects) {
        if (job.group()) {
            var material = frozenSubjects == null
                    ? groups.source(context, job.integrationId(), job.groupBindingId(), new EventId(job.sourceEventId()))
                    : groups.source(context, job.integrationId(), job.groupBindingId(), new EventId(job.sourceEventId()), frozenSubjects);
            if (material.authorEndUserId() != job.endUserId()) throw new IllegalArgumentException("memory_extraction_source_author_mismatch");
            return new SourceMaterial(material.text(), material.occurredAt(), null, material.subjectEndUserIds());
        }
        CommittedSessionEvent source = reuse.source(context, new EventId(job.sourceEventId()));
        if (!source.agentId().equals(context.runIdentity().agentId()) || !source.eventType().equals(job.sourceEventType())
                || !source.executionRef().equals(optional(job.sourceExecutionId()).map(value -> new ExecutionRef(tenant(job), value)))) {
            throw new IllegalArgumentException("memory_extraction_source_mismatch");
        }
        InboundMemorySource payload = MemoryJsonCodec.decode(source.payload().canonicalJson(), InboundMemorySource.class,
                "memory_inbound_text_invalid");
        return new SourceMaterial(payload.text(), source.occurredAt(), payload.candidate(), List.of());
    }

    private ExistingFact existing(String alias, MemoryFact fact) {
        var content = MemoryContent.withoutRevisionProposal(MemoryContent.decode(fact.object().canonicalJson()));
        return new ExistingFact(alias, fact.type().value(), fact.scope().name().toLowerCase(java.util.Locale.ROOT),
                fact.subject(), fact.predicate(), content, fact.revision(), fact.validFrom(), effectiveEnd(fact).orElse(null));
    }

    private boolean interpret(Job job, InputMaterial input) {
        List<Candidate> candidates;
        MemoryExtractionAgent.Result result;
        if ("inbound-hydrate".equals(job.memoryType()) || job.group()) {
            result = extractor.extract(job.tenantId(), commandPrefix(job), input.input(), () -> {
                if (job.group() && !groups.allowed(job.tenantId(), job.integrationId(), job.groupBindingId(), job.agentId(),
                        context(job).runIdentity().runRef().runId())) throw new GroupPaused();
            }, binding -> inClaim(job, () -> {
                requireOne(mapper.modelResult(job, EXTRACTOR, "WAITING", binding.systemRunId(),
                        binding.configurationRef(), binding.invocationRef()));
                return null;
            }));
            candidates = result.candidates();
        } else {
            if (input.inlineCandidate() == null) throw new IllegalArgumentException("memory_extraction_candidate_missing");
            // 正式源事件只在此协议边界投影；证据与alias仍由唯一候选契约校验，不信任metadata自证。
            candidates = contract.parse(MemoryJsonCodec.encode(new ExtractionOutput(MemoryExtractionCandidates.SCHEMA_VERSION,
                    List.of(input.inlineCandidate()))), input.input());
            result = new MemoryExtractionAgent.Result(MemoryExtractionAgent.Status.COMPLETED, candidates, null, null, null, null);
        }
        String status = switch (result.status()) {
            case COMPLETED -> "COMPLETED"; case PENDING -> "WAITING";
            case UNKNOWN -> "UNKNOWN"; case REJECTED -> "REJECTED";
        };
        inClaim(job, () -> {
            requireOne(mapper.modelResult(job, EXTRACTOR, status, result.systemRunId(), result.configurationRef(), result.invocationRef()));
            if (result.status() == MemoryExtractionAgent.Status.COMPLETED) {
                for (int ordinal = 0; ordinal < candidates.size(); ordinal++) {
                    requireOne(mapper.insertCandidate(job, EXTRACTOR, ordinal, commandPrefix(job) + ":" + ordinal,
                            candidates.get(ordinal)));
                }
            }
            return null;
        });
        if (result.status() == MemoryExtractionAgent.Status.COMPLETED) return true;
        finish(job, result.status() == MemoryExtractionAgent.Status.REJECTED ? "FAILED" : status, null, null, result.reasonCode());
        return false;
    }

    private PreparedData prepare(Job job, PortCallContext context, FrozenInput frozen, Candidate candidate, int ordinal) {
        MemoryFactDraft initial = initialDraft(job, frozen.sourceTime(), candidate);
        return inClaim(job, () -> governance.withSlot(context, initial, () -> {
            var decision = evaluate(job, context, initial, candidate, referencedSnapshot(context, frozen, candidate));
            MemoryFactDraft governed = governed(initial, candidate, decision);
            PreparedData prepared = PreparedData.from(governed, decision.reasons(),
                    decision.revisionTarget().map(value -> value.fact().factId()).orElse(null),
                    decision.revisionTarget().map(MemoryCandidateGovernance.RevisionTarget::expectedRevision).orElse(null));
            requireOne(mapper.prepareCandidate(job, EXTRACTOR, ordinal, prepared));
            return prepared;
        }));
    }

    private Optional<MemoryFact> referencedSnapshot(PortCallContext context, FrozenInput frozen, Candidate candidate) {
        return Optional.ofNullable(candidate.relation().target()).map(target -> {
            FactAlias alias = frozen.facts().stream().filter(value -> value.alias().equals(target)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("memory_relation_target_missing"));
            return reuse.reference(context, alias);
        });
    }

    private MemoryCandidateGovernance.Decision evaluate(Job job, PortCallContext context, MemoryFactDraft draft,
            Candidate candidate, Optional<MemoryFact> target) {
        return job.group() ? governance.evaluateGroup(context, draft, candidate, target, new EndUserId(job.endUserId()))
                : governance.evaluate(context, draft, candidate, target);
    }

    private MemoryFactDraft initialDraft(Job job, Instant sourceTime, Candidate candidate) {
        boolean task = "task".equals(candidate.temporal().kind());
        String type = task ? "episode" : candidate.type();
        MemoryScope scope = job.group() ? MemoryScope.valueOf(candidate.scope().toUpperCase(java.util.Locale.ROOT))
                : task || !"user".equals(candidate.scope()) ? MemoryScope.USER_AGENT : MemoryScope.USER;
        Instant validFrom = "interval".equals(candidate.temporal().kind()) ? candidate.temporal().from() : sourceTime;
        Optional<Instant> validTo = "interval".equals(candidate.temporal().kind())
                ? Optional.ofNullable(candidate.temporal().to()) : Optional.empty();
        return new MemoryFactDraft(owner(job, scope, candidate.subject()), new MemoryTypeRef(type), new RetentionClassRef("standard"),
                AgentRuntimeHostContracts.MEMORY_SCHEMA_VERSION, candidate.subject(), candidate.predicate(),
                new TypedPayload("preference".equals(type) ? AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD
                        : AgentRuntimeHostContracts.MEMORY_EPISODE_PAYLOAD, MemoryContent.encode(MemoryContent.interpretedContent(candidate))),
                List.of(new EventId(job.sourceEventId())), new SessionRef(tenant(job), job.sessionId()),
                optional(job.sourceExecutionId()).map(value -> new ExecutionRef(tenant(job), value)), Optional.empty(),
                EXTRACTOR, validFrom, validTo, task ? Optional.of(sourceTime.plus(Duration.ofHours(24))) : Optional.empty(),
                0.8, 0.5, false, Optional.empty(), FactStatus.ACTIVE);
    }

    private MemoryFactDraft governed(MemoryFactDraft initial, Candidate candidate, MemoryCandidateGovernance.Decision decision) {
        var body = MemoryContent.interpretedContent(candidate);
        if (decision.revisionTarget().isPresent()) {
            var target = decision.revisionTarget().orElseThrow();
            body = MemoryContent.withRevisionProposal(body, target.fact().factId(), target.expectedRevision());
        }
        return new MemoryFactDraft(initial.owner(), initial.type(), initial.retentionClass(), initial.schemaVersion(),
                initial.subject(), initial.predicate(), new TypedPayload(initial.object().schemaId(), MemoryContent.encode(body)),
                initial.sourceEventIds(), initial.sourceSessionRef(), initial.sourceExecutionRef(), initial.exportPath(),
                initial.extractorVersion(), initial.validFrom(), initial.validTo(), initial.expiresAt(), initial.confidence(),
                initial.importance(), decision.reviewRequired(), decision.revisionTarget().map(MemoryCandidateGovernance.RevisionTarget::fact),
                decision.reviewRequired() ? FactStatus.PENDING_CONFIRM : FactStatus.ACTIVE);
    }

    /** 固定字段来自typed准备记录，正文从原候选解释恢复；不再维护第二份Draft JSON快照。 */
    private MemoryFactDraft draft(Job job, Candidate candidate, PreparedData prepared) {
        var body = MemoryContent.interpretedContent(candidate);
        Optional<MemoryFactRef> target = optional(prepared.targetFactId()).map(value -> new MemoryFactRef(tenant(job), value));
        if (target.isPresent()) body = MemoryContent.withRevisionProposal(body, target.orElseThrow().factId(), prepared.targetRevision());
        return new MemoryFactDraft(owner(job, MemoryScope.valueOf(prepared.scope()), candidate.subject()), new MemoryTypeRef(prepared.type()),
                new RetentionClassRef(prepared.retentionClass()), prepared.schemaVersion(), candidate.subject(), candidate.predicate(),
                new TypedPayload(prepared.payloadSchemaId(), MemoryContent.encode(body)), List.of(new EventId(job.sourceEventId())),
                new SessionRef(tenant(job), job.sessionId()), optional(job.sourceExecutionId()).map(value -> new ExecutionRef(tenant(job), value)),
                Optional.empty(), EXTRACTOR, prepared.validFrom(), prepared.validTo(), prepared.expiresAt(), prepared.confidence(),
                prepared.importance(), prepared.reviewRequired(), target, FactStatus.valueOf(prepared.initialStatus()));
    }

    private WriteOutcome write(PortCallContext context, String key, MemoryFactDraft draft, Instant occurredAt) {
        var result = runtime.mutate(new MemoryRuntime.MutationCall(context,
                new MemoryRuntime.Mutation.Create(new IdempotencyKey(key), draft), occurredAt));
        return switch (result) {
            case MemoryRuntime.MutationResult.Recorded value -> new WriteOutcome("RECORDED", value.fact(), null);
            case MemoryRuntime.MutationResult.Pending value -> new WriteOutcome("ACK_PENDING", null, value.failure().reasonCode());
            case MemoryRuntime.MutationResult.Uncertain value -> new WriteOutcome("UNKNOWN", null, value.failure().reasonCode());
            case MemoryRuntime.MutationResult.Conflict ignored -> new WriteOutcome("FAILED", null, "memory_revision_conflict");
            case MemoryRuntime.MutationResult.Rejected value -> new WriteOutcome("FAILED", null, value.failure().reasonCode());
            case MemoryRuntime.MutationResult.Stopped value -> new WriteOutcome("FAILED", null, value.failure().reasonCode());
        };
    }

    private <T> T inClaim(Job job, Supplier<T> action) {
        return transactions.execute(ignored -> {
            if (!job.claimToken().equals(mapper.lockClaim(job, EXTRACTOR))) throw new FenceLost();
            return action.get();
        });
    }

    private void finish(Job job, String status, String fact, Long revision, String error) {
        transactions.executeWithoutResult(ignored -> mapper.finish(job, EXTRACTOR, status, fact, revision, error));
    }

    private boolean automaticExtractionEnabled(Job job) {
        return Boolean.TRUE.equals(transactions.execute(ignored -> extractionPolicy.automaticExtractionEnabled(job.tenantId(), job.agentId())));
    }

    private boolean groupAllowed(Job job) {
        return !job.group() || Boolean.TRUE.equals(transactions.execute(ignored -> groups.allowed(job.tenantId(),
                job.integrationId(), job.groupBindingId(), job.agentId(), context(job).runIdentity().runRef().runId())));
    }

    private PortCallContext context(Pending source) {
        return context(new Job(source.tenantId(), GroupMemorySources.eventId(source.sourceMessageId(), source.sourceRevision()).value(),
                "group-hydrate", "source", source.agentId(), "GROUP", source.groupBindingId(), source.authorEndUserId(),
                source.sessionId(), null, GroupMemorySources.EVENT_TYPE, POLICY, null, null, null, null, null, null, null,
                source.integrationId(), source.groupBindingId()));
    }

    private GroupBindingRef group(Job job) {
        if (!job.group() || job.groupBindingId() == null || job.integrationId() == null
                || !job.groupBindingId().equals(job.sourceTargetId())) throw new IllegalArgumentException("memory_group_source_identity_invalid");
        return new GroupBindingRef(tenant(job), job.groupBindingId());
    }

    private MemoryOwner owner(Job job, MemoryScope scope, String subject) {
        return switch (scope) {
            case USER_AGENT -> new MemoryOwner.UserAgent(tenant(job), new EndUserId(job.endUserId()), new AgentId(job.agentId()));
            case USER -> new MemoryOwner.User(tenant(job), new EndUserId(job.endUserId()));
            case GROUP -> new MemoryOwner.Group(group(job));
            case GROUP_MEMBER -> new MemoryOwner.GroupMember(group(job), new EndUserId(Long.parseLong(subject.substring("user:".length()))));
            default -> throw new IllegalArgumentException("memory_personal_owner_required");
        };
    }

    private PortCallContext context(Job job) {
        TenantId tenant = tenant(job);
        SessionTarget target = new SessionTarget(SessionTarget.Kind.valueOf(job.sourceTargetKind()), job.sourceTargetId());
        SessionOwner owner = job.group()
                ? new SessionOwner(new SessionRef(tenant, job.sessionId()), SessionSubject.group(group(job)), target)
                : SessionOwner.user(new SessionRef(tenant, job.sessionId()), target, new EndUserId(job.endUserId()));
        String suffix = digest(job.tenantId() + ":" + job.sourceEventId() + ":" + EXTRACTOR + ":" + POLICY + ":" + job.memoryType() + ":" + job.canonicalKey());
        InputId input = new InputId("memory-extract:" + suffix);
        RunIdentity run = new RunIdentity(owner, new AgentId(job.agentId()), input, new RunRef(tenant, "memory-extract:" + suffix),
                new TraceEnvelope("memory-extract:" + suffix, "memory-extract:" + suffix, input, Optional.empty()),
                new ResultRef("memory-extract-identity:" + suffix), new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant, "memory-extractor")),
                Optional.empty(), new RunOriginRef(tenant, "memory-extract:" + suffix));
        VerifiedIdentity identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant, "memory-extractor"), owner,
                Set.of("memory:extract"), 1, new ResultRef("memory-extract-verified:" + suffix));
        return new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(30), () -> false));
    }

    private static Optional<Instant> effectiveEnd(MemoryFact fact) {
        if (fact.validTo().isEmpty()) return fact.expiresAt();
        if (fact.expiresAt().isEmpty()) return fact.validTo();
        return Optional.of(fact.validTo().orElseThrow().isBefore(fact.expiresAt().orElseThrow())
                ? fact.validTo().orElseThrow() : fact.expiresAt().orElseThrow());
    }
    private static Optional<String> optional(String value) { return value == null || value.isBlank() ? Optional.empty() : Optional.of(value); }
    private static boolean suppressed(String reason) {
        return ExtractionGovernance.FORGOTTEN_SOURCE.reasonCode().equals(reason)
                || ExtractionGovernance.SUPERSEDED_SOURCE.reasonCode().equals(reason);
    }
    private static TenantId tenant(Job job) { return new TenantId(job.tenantId()); }
    private static String commandPrefix(Job job) { return "memory-extract:" + EXTRACTOR + ":" + POLICY + ":v2:" + digest(job.tenantId() + ":" + job.sourceEventId() + ":" + job.memoryType() + ":" + job.canonicalKey()); }
    private static String tag(Job job) { return digest(job.sourceEventId()).substring(0, 12); }
    private static void requireOne(int changed) { if (changed != 1) throw new FenceLost(); }
    private static String stableReason(Exception failure, String fallback) { return failure.getMessage() != null && failure.getMessage().matches("memory_[a-z0-9_]{1,180}") ? failure.getMessage() : fallback; }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("sha256_unavailable"); }
    }
    private record FreezeResult(FrozenInput material, InputMaterial input) {}
    private record InputMaterial(ExtractionInput input, Candidate inlineCandidate) {}
    private record SourceMaterial(String text, Instant occurredAt, Candidate inlineCandidate, List<Long> groupSubjects) {}

    /** 正式入站载荷的必要投影；未知扩展属于原协议，不复制或持久化到提取任务。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InboundMemorySource(List<InboundPart> content, InboundMetadata metadata, Candidate memoryCandidate) {
        private InboundMemorySource { content = content == null ? List.of() : List.copyOf(content); }
        private String text() {
            return content.stream().filter(part -> "text".equals(part.type())).map(InboundPart::text)
                    .collect(java.util.stream.Collectors.joining());
        }
        private Candidate candidate() {
            return metadata != null && metadata.memoryCandidate() != null ? metadata.memoryCandidate() : memoryCandidate;
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InboundMetadata(Candidate memoryCandidate) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InboundPart(String type, String text) {
        private InboundPart {
            if ("text".equals(type) && text == null) throw new IllegalArgumentException("memory_inbound_text_invalid");
        }
    }
    private record WriteOutcome(String status, MemoryFact fact, String error) {}
    private static final class GroupPaused extends RuntimeException {}
    private static final class FenceLost extends RuntimeException {}
}
