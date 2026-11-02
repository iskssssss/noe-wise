package com.skillforge.core.billing.metering;

import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.pricing.PriceCalculator.Amounts;
import com.skillforge.core.billing.pricing.ExchangeRateProvider;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.MediaPricingContext;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量事件写入组件（计量层唯一落库入口）。
 *
 * <p>「最小单元」原子事件：每次 AI 调用、知识源操作或 Workflow 外部动作与
 * 每条用户请求（request）各一条；只 INSERT 不可变（RLS 仅开放 INSERT+SELECT），
 * 事件幂等键（event_id）由调用点在重试 lambda 外生成防双计。统计（token/金额/次数）
 * 全部由查询基于 usage_events 聚合，本组件不写任何聚合值。
 */
@Service
public class CostRecorder {

    /** 独立于调用终态保存有变化的供应商累计报告；缺失字段不抹去已报告值，UNKNOWN 不变为零。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordUsageReport(String tenantId, CostContext context, ExternalInvocationRequest invocation,
                                  String model, long revision, LlmUsage usage, Map<String, Object> metadata) {
        recordUsageReport(tenantId, context, invocation, CostType.LLM_CHAT, model, revision, usage, metadata);
    }

    /** 辅助调用开始时追加未知占位，网络失败后仍能导出真实尝试；成功 measured 按 attempt 去重。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordAuxiliaryStarted(String tenantId, ExternalInvocationRequest invocation) {
        log.debug("辅助调用未知计量进入 tenant={} operation={}",tenantId,invocation.operationType());
        CostType type=switch(invocation.operationType()) {
            case MODEL_CHAT -> CostType.LLM_CHAT;
            case MODEL_EMBEDDING -> CostType.LLM_EMBEDDING;
            case MODEL_RERANK -> CostType.LLM_RERANK;
            case MODEL_IMAGE,MODEL_VIDEO,MODEL_TRANSCRIPTION,MODEL_SPEECH ->
                    throw new IllegalArgumentException("media_invocation_explicit_source_required");
            case KNOWLEDGE_SOURCE -> CostType.KNOWLEDGE_SOURCE;
            case TOOL_CALL,CONNECTOR_CALL -> CostType.TOOL_CALL;
            case WORKFLOW_EXTERNAL -> CostType.WORKFLOW_EXTERNAL;
        };
        CostContext context=MeterScope.current();
        if(context==null) {
            var builder=CostContext.builder().callPoint("auxiliary.invocation");
            if(invocation.context() instanceof com.skillforge.core.shared.tracing.TraceContext trace)builder
                    .traceContext(trace).requestId(trace.inputId());
            if(invocation.context() instanceof com.skillforge.core.shared.tracing.ControlOperationContext control)builder
                    .scopeType("control_operation").scopeId(control.operationId()).requestId(control.rootOperationId());
            context=builder.build();
        }
        recordUsageReport(tenantId,context,invocation,type,invocation.service(),0,LlmUsage.empty(),Map.of());
        log.debug("辅助调用未知计量退出 tenant={} operation={}",tenantId,invocation.operationType());
    }

    /** 模型及辅助调用共用不可变报告写入，类型由正式调用守卫确定，不能来自用户参数。 */
    private void recordUsageReport(String tenantId, CostContext context, ExternalInvocationRequest invocation,
                                  CostType type, String model, long revision, LlmUsage usage, Map<String, Object> metadata) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(usage, "usage");
        if (revision < 0 || (usage.usageSource() != UsageSource.PROVIDER_REPORTED && usage.usageSource() != UsageSource.UNKNOWN)) {
            throw new IllegalArgumentException("invalid_provider_usage_report");
        }
        for (Long field : new Long[] {usage.promptTokens(), usage.completionTokens(), usage.cachedInputTokens(),
                usage.cacheWriteInputTokens(), usage.reasoningOutputTokens()}) {
            if (field != null && field < 0) throw new IllegalArgumentException("invalid_provider_usage_report");
        }
        CostEventDO event = new CostEventDO();
        event.setId(SnowflakeIds.nextId());
        event.setTenantId(Long.parseLong(tenantId));
        event.setEventType(type.value());
        event.setMeasurementUnit(type.measurementUnit());
        event.setProvider(invocation.provider());
        event.setModel(model);
        Instant now = Instant.now();
        event.setOccurredAt(now); event.setIngestedAt(now); event.setCreatedAt(now);
        event.setUsageEventKind(UsageEventKind.USAGE_REPORT.value());
        event.setReportRevision(revision);
        event.setEventId(invocation.attemptId() + ":usage-report:" + revision);
        event.setInvocationId(invocation.invocationId()); event.setAttemptId(invocation.attemptId());
        event.setRequestId(context.requestId()); event.setRootInputId(context.rootInputId());
        event.setSessionId(context.sessionId()); event.setEndUserId(context.endUserId());
        event.setChannelId(context.channelId()); event.setScopeId(context.scopeId()); event.setScopeType(context.scopeType());
        event.setAgentId(context.agentId()); event.setRunId(context.runId());
        event.setExecutionId(context.executionId()); event.setCallPoint(context.callPoint());
        event.setPromptTokens(usage.promptTokens()); event.setCompletionTokens(usage.completionTokens());
        event.setCachedInputTokens(usage.cachedInputTokens()); event.setCacheWriteInputTokens(usage.cacheWriteInputTokens());
        event.setReasoningOutputTokens(usage.reasoningOutputTokens()); event.setUsageSource(usage.usageSource().name());
        event.setMeta(mergeMeta(context, metadata));
        // 供应商新 revision 按契约严格递增。锁只串行化此 attempt 的消费，
        // 不写观察水位或修改账本；相同有效计量的中间观察无需形成恢复事实。
        costEventMapper.lockUsageAttempt(event.getTenantId(), event.getAttemptId());
        CostEventDO existing = findByEventId(event.getTenantId(), event.getEventId());
        if (existing != null) {
            assertSameUsageReport(existing, event);
            return;
        }
        CostEventDO effective = costEventMapper.effectiveUsageReport(event.getTenantId(), event.getAttemptId());
        if (effective != null && unchangedUsage(effective, event)) return;
        attribute(event, context);
        if (costEventMapper.insertUsageReport(event) == 0) {
            assertSameUsageReport(findByEventId(event.getTenantId(), event.getEventId()), event);
        } else {
            enqueue(event);
        }
    }

    /** 只比较本观察提供的字段；缺失不清除，已报告零与未知 null 保持不同。 */
    private boolean unchangedUsage(CostEventDO effective, CostEventDO incoming) {
        return unchangedField(effective.getPromptTokens(), incoming.getPromptTokens())
                && unchangedField(effective.getCompletionTokens(), incoming.getCompletionTokens())
                && unchangedField(effective.getCachedInputTokens(), incoming.getCachedInputTokens())
                && unchangedField(effective.getCacheWriteInputTokens(), incoming.getCacheWriteInputTokens())
                && unchangedField(effective.getReasoningOutputTokens(), incoming.getReasoningOutputTokens());
    }

    /** 非空值才代表新的供应商事实，不能用默认零补造缺失值。 */
    private boolean unchangedField(Long effective, Long incoming) {
        return incoming == null || Objects.equals(effective, incoming);
    }

    /** 已落账 revision 仍按精确身份及计量核验重放，同键异内容拒绝。 */
    private void assertSameUsageReport(CostEventDO existing, CostEventDO event) {
        long revision = event.getReportRevision();
        if (existing == null || !Objects.equals(existing.getReportRevision(), revision)
                || !Objects.equals(existing.getEventType(), event.getEventType())
                || !Objects.equals(existing.getSessionId(), event.getSessionId())
                || !Objects.equals(existing.getRootInputId(), event.getRootInputId())
                || !Objects.equals(existing.getRunId(), event.getRunId())
                || !Objects.equals(existing.getInvocationId(), event.getInvocationId())
                || !Objects.equals(existing.getAttemptId(), event.getAttemptId())
                || !Objects.equals(existing.getProvider(), event.getProvider())
                || !Objects.equals(existing.getModel(), event.getModel())
                || !Objects.equals(existing.getRequestId(), event.getRequestId())
                || !Objects.equals(existing.getExecutionId(), event.getExecutionId())
                || !Objects.equals(existing.getCallPoint(), event.getCallPoint())
                || !Objects.equals(existing.getAgentId(), event.getAgentId())
                || !Objects.equals(existing.getEndUserId(), event.getEndUserId())
                || !Objects.equals(existing.getUsageSource(), event.getUsageSource())
                || !Objects.equals(existing.getPromptTokens(), event.getPromptTokens())
                || !Objects.equals(existing.getCompletionTokens(), event.getCompletionTokens())
                || !Objects.equals(existing.getCachedInputTokens(), event.getCachedInputTokens())
                || !Objects.equals(existing.getCacheWriteInputTokens(), event.getCacheWriteInputTokens())
                || !Objects.equals(existing.getReasoningOutputTokens(), event.getReasoningOutputTokens())) {
            throw new IllegalStateException("provider_usage_report_conflict");
        }
    }

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(CostRecorder.class);

    /** 事件表映射器。 */
    private final CostEventMapper costEventMapper;

    /** 写时计价器。 */
    private final PriceCalculator priceCalculator;

    /** 外部汇率快照 Provider；缺失时外币金额保持 unresolved。 */
    private final ExchangeRateProvider exchangeRateProvider;

    /** 应用来源解析与可靠导出写入，在生产宿主中正式装配。 */
    private final PlatformUsageRecorder platformUsageRecorder;

    /**
     * 构造用量事件写入组件。
     *
     * @param costEventMapper  事件表映射器
     * @param priceCalculator  写时计价器
     */
    public CostRecorder(CostEventMapper costEventMapper, PriceCalculator priceCalculator) {
        this(costEventMapper, priceCalculator, (ExchangeRateProvider) null);
    }

    /** 生产装配：Provider 通过 ObjectProvider 可选注入，避免汇率服务故障阻断启动。 */
    @Autowired
    public CostRecorder(CostEventMapper costEventMapper, PriceCalculator priceCalculator,
                        ObjectProvider<ExchangeRateProvider> exchangeRateProvider,
                        ObjectProvider<PlatformUsageRecorder> platformUsageRecorder) {
        this(costEventMapper, priceCalculator,
                exchangeRateProvider == null ? null : exchangeRateProvider.getIfAvailable(),
                platformUsageRecorder.getObject());
    }

    CostRecorder(CostEventMapper costEventMapper, PriceCalculator priceCalculator,
                 ExchangeRateProvider exchangeRateProvider) {
        this(costEventMapper, priceCalculator, exchangeRateProvider, null);
    }

    /** 显式技术依赖装配，应用导出与计量共享同一事务。 */
    CostRecorder(CostEventMapper costEventMapper, PriceCalculator priceCalculator,
                 ExchangeRateProvider exchangeRateProvider, PlatformUsageRecorder platformUsageRecorder) {
        this.costEventMapper = costEventMapper;
        this.priceCalculator = priceCalculator;
        this.exchangeRateProvider = exchangeRateProvider;
        this.platformUsageRecorder = platformUsageRecorder;
    }

    /** 实际供应商发送前冻结原媒体调用来源；平台连接探测不调用本入口。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordMediaStarted(String tenantId, CostContext source, ExternalInvocationRequest invocation,
            CostType type, ModelRevisionRef pricingModel) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(pricingModel, "pricingModel");
        if (!mediaType(type) || !type.value().equals(invocation.operationType().value()))
            throw new IllegalArgumentException("media_invocation_type_invalid");
        long tenant = Long.parseLong(tenantId);
        costEventMapper.lockUsageAttempt(tenant, invocation.attemptId());
        String id = invocation.attemptId() + ":usage-report:0";
        CostEventDO previous = findByEventId(tenant, id);
        if (previous != null) {
            if (!Objects.equals(previous.getInvocationId(), invocation.invocationId())
                    || !Objects.equals(previous.getEventType(), type.value())
                    || !Objects.equals(previous.getProvider(), invocation.provider())
                    || !Objects.equals(previous.getModel(), invocation.service())
                    || !Objects.equals(previous.getRunId(), source.runId())
                    || !Objects.equals(previous.getSessionId(), source.sessionId())
                    || !Objects.equals(previous.getRootInputId(), source.rootInputId())
                    || !Objects.equals(previous.getPricingModelId(), Long.valueOf(pricingModel.modelId()))
                    || !Objects.equals(previous.getPricingModelRevision(), pricingModel.revision()))
                throw new IllegalStateException("media_started_identity_conflict");
            return;
        }
        var amounts = priceCalculator.unpricedMedia(pricingModel);
        CostEventDO event = new CostEventDO();
        Instant now = Instant.now();
        event.setId(SnowflakeIds.nextId()); event.setTenantId(tenant); event.setEventId(id);
        event.setInvocationId(invocation.invocationId()); event.setAttemptId(invocation.attemptId());
        event.setEventType(type.value()); event.setProvider(invocation.provider()); event.setModel(invocation.service());
        event.setPricingModelId(Long.valueOf(pricingModel.modelId())); event.setPricingModelRevision(pricingModel.revision());
        event.setPricingSnapshotVersion(amounts.pricingSnapshotVersion()); event.setCurrency(amounts.currency());
        event.setOccurredAt(now); event.setIngestedAt(now); event.setCreatedAt(now);
        event.setQuantity(1); event.setMeasurementUnit(type.measurementUnit());
        event.setUsageEventKind(UsageEventKind.USAGE_REPORT.value()); event.setReportRevision(0L);
        event.setAmountStatus(AmountStatus.UNRESOLVED.value()); event.setUsageSource(UsageSource.UNKNOWN.name());
        event.setRequestId(source.requestId()); event.setRootInputId(source.rootInputId());
        event.setSessionId(source.sessionId()); event.setEndUserId(source.endUserId()); event.setChannelId(source.channelId());
        event.setAgentId(source.agentId()); event.setRunId(source.runId()); event.setWorkflowInstanceId(source.workflowInstanceId());
        event.setExecutionId(source.executionId()); event.setScopeId(source.scopeId()); event.setScopeType(source.scopeType());
        event.setCallPoint(source.callPoint()); event.setMeta(mergeMeta(source, null));
        attribute(event, source);
        if (costEventMapper.insertUsageReport(event) != 1) throw new IllegalStateException("media_started_fact_conflict");
        enqueue(event);
    }

    /** 原任务观察仅追加事实，内部锁版本不是供应商修订；历史无开始事实的任务不回填。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordMediaObservation(String tenantId, String invocationId, long observationRevision,
            MediaUsage usage, boolean settled, MediaPricingContext pricingContext) {
        Objects.requireNonNull(usage, "usage");
        if (invocationId == null || invocationId.isBlank() || observationRevision <= 0)
            throw new IllegalArgumentException("media_observation_identity_invalid");
        long tenant = Long.parseLong(tenantId);
        CostEventDO original = costEventMapper.mediaStarted(tenant, invocationId);
        if (original == null) return;
        costEventMapper.lockUsageAttempt(tenant, original.getAttemptId());
        String eventId = original.getAttemptId() + ":media-observation:" + observationRevision;
        CostEventDO replay = findByEventId(tenant, eventId);
        if (replay != null) {
            if (!containsMediaUsage(replay, usage)) throw new IllegalStateException("media_observation_conflict");
        }
        CostEventDO latest = costEventMapper.latestMediaObservation(tenant, original.getAttemptId());
        if (latest == null) latest = original;
        long latestRevision = latest.getReportRevision() != null ? latest.getReportRevision()
                : latest.getUsageSnapshotRevision() == null ? 0 : latest.getUsageSnapshotRevision();
        if (replay == null && observationRevision < latestRevision)
            throw new IllegalStateException("media_observation_revision_stale");
        MediaUsage merged = mediaUsage(latest).merge(usage);
        CostEventDO measured = findByEventId(tenant, original.getAttemptId() + ":usage");
        if (measured != null) {
            if (!sameMediaUsage(mediaUsage(measured), merged)) throw new IllegalStateException("media_terminal_usage_conflict");
            return;
        }
        if (replay == null && !sameMediaUsage(mediaUsage(latest), merged)) {
            CostEventDO report = mediaObservation(original, merged);
            report.setEventId(eventId); report.setReportRevision(observationRevision);
            report.setUsageEventKind(UsageEventKind.USAGE_REPORT.value());
            report.setAmountStatus(AmountStatus.UNRESOLVED.value());
            if (costEventMapper.insertUsageReport(report) != 1) throw new IllegalStateException("media_observation_conflict");
            enqueue(report);
        }
        if (settled) {
            CostEventDO terminal = mediaObservation(original, merged);
            terminal.setEventId(original.getAttemptId() + ":usage");
            terminal.setUsageEventKind(UsageEventKind.MEASURED.value()); terminal.setUsageSnapshotRevision(observationRevision);
            CostType type = switch (original.getEventType()) {
                case "media.image" -> CostType.MEDIA_IMAGE;
                case "media.video" -> CostType.MEDIA_VIDEO;
                case "media.transcription" -> CostType.MEDIA_TRANSCRIPTION;
                case "media.speech" -> CostType.MEDIA_SPEECH;
                default -> throw new IllegalStateException("media_invocation_type_invalid");
            };
            Amounts amounts = priceCalculator.calculateMedia(new ModelRevisionRef(original.getPricingModelId().toString(),
                    original.getPricingModelRevision()), type, merged, pricingContext);
            terminal.setInputAmountCents(amounts.input()); terminal.setOutputAmountCents(amounts.output());
            terminal.setTotalAmountCents(amounts.total());
            terminal.setInputAmount(amounts.preciseInput()); terminal.setOutputAmount(amounts.preciseOutput());
            terminal.setTotalAmount(amounts.preciseTotal()); terminal.setCurrency(amounts.currency());
            ExchangeRateProvider.Rate rate = exchangeRate(amounts.currency(), original.getOccurredAt());
            terminal.setExchangeRateToCny(rate == null ? null : rate.toCny());
            terminal.setPricingSnapshotVersion(rate == null ? amounts.pricingSnapshotVersion()
                    : amounts.pricingSnapshotVersion() + "@fx-" + rate.snapshotVersion());
            terminal.setAmountStatus(amountStatus(amounts, amounts.currency(), rate).value());
            if (costEventMapper.insert(terminal) != 1) throw new IllegalStateException("media_terminal_fact_conflict");
            enqueue(terminal);
        }
    }

    /** 发送前再次取消或到期已确定没有供应商请求；追加不适用事实而不删除原未知开始记录。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordMediaNotSubmitted(String tenantId, String invocationId, long observationRevision) {
        if (invocationId == null || invocationId.isBlank() || observationRevision <= 0)
            throw new IllegalArgumentException("media_observation_identity_invalid");
        long tenant = Long.parseLong(tenantId);
        CostEventDO original = costEventMapper.mediaStarted(tenant, invocationId);
        if (original == null) return;
        costEventMapper.lockUsageAttempt(tenant, original.getAttemptId());
        CostEventDO measured = findByEventId(tenant, original.getAttemptId() + ":usage");
        if (measured != null) {
            if (!Integer.valueOf(0).equals(measured.getQuantity())
                    || !AmountStatus.NOT_APPLICABLE.value().equals(measured.getAmountStatus()))
                throw new IllegalStateException("media_not_submitted_conflict");
            return;
        }
        CostEventDO latest = costEventMapper.latestMediaObservation(tenant, original.getAttemptId());
        if (latest != null && mediaUsage(latest).reported()) throw new IllegalStateException("media_not_submitted_conflict");
        CostEventDO event = mediaObservation(original, MediaUsage.empty());
        event.setEventId(original.getAttemptId() + ":usage");
        event.setUsageEventKind(UsageEventKind.MEASURED.value()); event.setUsageSnapshotRevision(observationRevision);
        event.setQuantity(0); event.setAmountStatus(AmountStatus.NOT_APPLICABLE.value());
        if (costEventMapper.insert(event) != 1) throw new IllegalStateException("media_terminal_fact_conflict");
        enqueue(event);
    }

    private static boolean mediaType(CostType type) {
        return type == CostType.MEDIA_IMAGE || type == CostType.MEDIA_VIDEO
                || type == CostType.MEDIA_TRANSCRIPTION || type == CostType.MEDIA_SPEECH;
    }

    private CostEventDO mediaObservation(CostEventDO original, MediaUsage usage) {
        CostEventDO event = new CostEventDO();
        event.setId(SnowflakeIds.nextId()); event.setTenantId(original.getTenantId());
        event.setOriginKind(original.getOriginKind());
        event.setActorRef(original.getActorRef());
        event.setResponsibilityRef(original.getResponsibilityRef());
        event.setGroupBindingId(original.getGroupBindingId());
        event.setRequesterKind(original.getRequesterKind());
        event.setRequesterRef(original.getRequesterRef());
        event.setOriginSessionId(original.getOriginSessionId());
        event.setOriginEventId(original.getOriginEventId());
        event.setResponsibilityAgentId(original.getResponsibilityAgentId());
        event.setSystemAgentRunId(original.getSystemAgentRunId());
        event.setSystemAgentConfigurationRef(original.getSystemAgentConfigurationRef());
        event.setSystemAgentInvocationRef(original.getSystemAgentInvocationRef());
        event.setDeploymentId(original.getDeploymentId());
        event.setIntegrationId(original.getIntegrationId());
        event.setBusinessScopeId(original.getBusinessScopeId());
        event.setAgentDefinitionVersion(original.getAgentDefinitionVersion());
        event.setSourceOwnerKind(original.getSourceOwnerKind());
        event.setSourceOwnerTenantId(original.getSourceOwnerTenantId());
        event.setAgentDefinitionId(original.getAgentDefinitionId());
        event.setAgentRevisionId(original.getAgentRevisionId());
        event.setAgentRevision(original.getAgentRevision());
        event.setDeploymentRevision(original.getDeploymentRevision());
        event.setGrantId(original.getGrantId());
        event.setGrantRevision(original.getGrantRevision());
        event.setRequestId(original.getRequestId());
        event.setRootInputId(original.getRootInputId());
        event.setSessionId(original.getSessionId());
        event.setEndUserId(original.getEndUserId());
        event.setChannelId(original.getChannelId());
        event.setAgentId(original.getAgentId());
        event.setRunId(original.getRunId());
        event.setWorkflowInstanceId(original.getWorkflowInstanceId());
        event.setExecutionId(original.getExecutionId());
        event.setScopeId(original.getScopeId());
        event.setScopeType(original.getScopeType());
        event.setCallPoint(original.getCallPoint());
        event.setParentEventId(original.getId()); event.setInvocationId(original.getInvocationId()); event.setAttemptId(original.getAttemptId());
        event.setEventType(original.getEventType()); event.setProvider(original.getProvider()); event.setModel(original.getModel());
        event.setPricingModelId(original.getPricingModelId()); event.setPricingModelRevision(original.getPricingModelRevision());
        event.setPricingSnapshotVersion(original.getPricingSnapshotVersion()); event.setCurrency(original.getCurrency());
        event.setQuantity(1); event.setMeasurementUnit(original.getMeasurementUnit());
        event.setOccurredAt(original.getOccurredAt()); event.setIngestedAt(Instant.now()); event.setCreatedAt(event.getIngestedAt());
        event.setPromptTokens(usage.inputTokens()); event.setCompletionTokens(usage.outputTokens());
        event.setImageCount(usage.imageCount()); event.setVideoCount(usage.videoCount());
        event.setCharacters(usage.characters()); event.setDurationSeconds(usage.durationSeconds());
        if (usage.inputTokens() != null && usage.outputTokens() != null) {
            try { event.setTokens(Math.addExact(usage.inputTokens(), usage.outputTokens())); }
            catch (ArithmeticException overflow) { event.setTokens(null); }
        }
        event.setUsageSource(usage.reported() ? UsageSource.PROVIDER_REPORTED.name() : UsageSource.UNKNOWN.name());
        event.setMeta(original.getMeta() == null ? Map.of() : new LinkedHashMap<>(original.getMeta()));
        if (platformUsageRecorder != null) platformUsageRecorder.inheritGrantChain(original, event);
        return event;
    }

    private static MediaUsage mediaUsage(CostEventDO event) {
        return new MediaUsage(event.getPromptTokens(), event.getCompletionTokens(), event.getImageCount(),
                event.getVideoCount(), event.getCharacters(), event.getDurationSeconds());
    }

    private static boolean containsMediaUsage(CostEventDO event, MediaUsage reported) {
        return (reported.inputTokens() == null || Objects.equals(event.getPromptTokens(), reported.inputTokens()))
                && (reported.outputTokens() == null || Objects.equals(event.getCompletionTokens(), reported.outputTokens()))
                && (reported.imageCount() == null || Objects.equals(event.getImageCount(), reported.imageCount()))
                && (reported.videoCount() == null || Objects.equals(event.getVideoCount(), reported.videoCount()))
                && (reported.characters() == null || Objects.equals(event.getCharacters(), reported.characters()))
                && (reported.durationSeconds() == null || event.getDurationSeconds() != null
                    && event.getDurationSeconds().compareTo(reported.durationSeconds()) == 0);
    }

    private static boolean sameMediaUsage(MediaUsage left, MediaUsage right) {
        return Objects.equals(left.inputTokens(), right.inputTokens()) && Objects.equals(left.outputTokens(), right.outputTokens())
                && Objects.equals(left.imageCount(), right.imageCount()) && Objects.equals(left.videoCount(), right.videoCount())
                && Objects.equals(left.characters(), right.characters()) && (left.durationSeconds() == null
                    ? right.durationSeconds() == null : right.durationSeconds() != null && left.durationSeconds().compareTo(right.durationSeconds()) == 0);
    }

    /**
     * 记录一次 AI 调用事件（llm.chat / llm.embedding / llm.rerank）。
     *
     * <p>关联维度取自 {@link MeterScope#current()}；无作用域（连接测试等）不记。
     * event_id 优先用作用域上下文携带值（调用点重试外生成），缺失时按
     * requestId+callPoint+雪花 兜底生成（兜底场景不保证重试防双计）。
     *
     * @param tenantId         租户 ID
     * @param type             事件类型
     * @param provider         AI 服务商标识
     * @param model            模型名
     * @param usage            token 明细及其来源；未知字段保持 null
     * @param quantity         计量单位数（llm.rerank 文档数等），可空
     * @param extraMeta        补充细节（plan_id/step_id/模型参数等），可空
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordLlm(
            String tenantId,
            CostType type,
            String provider,
            String model,
            LlmUsage usage,
            Integer quantity,
            Map<String, Object> extraMeta) {
        recordLlm(tenantId, type, provider, model, usage, quantity, null, extraMeta);
    }

    /** 确定失败只按原 attempt 已保存的真实报告计价；没有报告字段时不生成 measured。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordFailedModelUsage(String tenantId, CostContext context, ExternalInvocationRequest invocation,
            String model, ModelRevisionRef pricingModel, Map<String, Object> metadata) {
        long tenant = Long.parseLong(tenantId);
        costEventMapper.lockUsageAttempt(tenant, invocation.attemptId());
        if (findByEventId(tenant, invocation.attemptId() + ":usage") != null)
            throw new IllegalStateException("model_terminal_usage_conflict");
        CostEventDO report = costEventMapper.effectiveProviderReports(tenant, invocation.attemptId());
        if (report == null || report.getPromptTokens() == null && report.getCompletionTokens() == null
                && report.getCachedInputTokens() == null && report.getCacheWriteInputTokens() == null
                && report.getReasoningOutputTokens() == null) return;
        Long input = report.getPromptTokens();
        Long output = report.getCompletionTokens();
        Long total = input != null && output != null && input <= Long.MAX_VALUE - output ? input + output : null;
        var usage = new LlmUsage(input, report.getCachedInputTokens(), report.getCacheWriteInputTokens(), output,
                report.getReasoningOutputTokens(), total, UsageSource.PROVIDER_REPORTED, null);
        try (var attempt = ExternalInvocationScope.open(invocation); var meter = MeterScope.open(context)) {
            recordLlm(tenantId, CostType.LLM_CHAT, invocation.provider(), model, usage, 1,
                    Objects.requireNonNull(pricingModel), metadata);
        }
    }

    /** 记录一次使用显式冻结模型版本计价的 AI 调用。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordLlm(
            String tenantId,
            CostType type,
            String provider,
            String model,
            LlmUsage usage,
            Integer quantity,
            ModelRevisionRef pricingModel,
            Map<String, Object> extraMeta) {
        CostContext ctx = MeterScope.current();
        if (ctx == null) {
            // 无计量作用域：连接测试等非业务调用，不产生流水
            return;
        }
        ExternalInvocationRequest invocation = ExternalInvocationScope.current();
        String eventId = invocation == null
                ? (ctx.eventId() != null && !ctx.eventId().isBlank()
                ? ctx.eventId()
                : fallbackEventId(tenantId, type, ctx))
                : invocation.attemptId() + ":usage";
        Long tenantIdLong = Long.parseLong(tenantId);
        Long promptTokens = usage == null ? null : usage.promptTokens();
        Long completionTokens = usage == null ? null : usage.completionTokens();
        Long tokens = usage == null ? null : usage.totalTokens();
        UsageSource usageSource = usage == null ? UsageSource.UNKNOWN : usage.usageSource();
        Instant attemptStartedAt = type == CostType.LLM_CHAT && invocation != null
                ? costEventMapper.modelAttemptStartedAt(tenantIdLong, invocation.attemptId()) : null;
        Amounts amounts = priceCalculator.calculate(
                pricingModel, tenantIdLong, type, provider, model,
                usage, quantity, attemptStartedAt);
        CostEventDO event = new CostEventDO();
        event.setId(SnowflakeIds.nextId());
        event.setTenantId(tenantIdLong);
        event.setEventType(type.value());
        event.setProvider(provider);
        event.setModel(model);
        Instant now = Instant.now();
        event.setOccurredAt(now);
        event.setIngestedAt(now);
        event.setQuantity(quantity);
        event.setMeasurementUnit(type.measurementUnit());
        event.setTokens(tokens);
        event.setPromptTokens(promptTokens);
        event.setCompletionTokens(completionTokens);
        event.setCachedInputTokens(usage == null ? null : usage.cachedInputTokens());
        event.setCacheWriteInputTokens(usage == null ? null : usage.cacheWriteInputTokens());
        event.setReasoningOutputTokens(usage == null ? null : usage.reasoningOutputTokens());
        event.setUsageSource(usageSource.name());
        event.setEstimatorVersion(usage == null ? null : usage.estimatorVersion());
        event.setInputAmountCents(amounts.input());
        event.setOutputAmountCents(amounts.output());
        event.setTotalAmountCents(amounts.total());
        event.setInputAmount(amounts.preciseInput());
        event.setOutputAmount(amounts.preciseOutput());
        event.setTotalAmount(amounts.preciseTotal());
        String currency = amounts.currency() == null ? "CNY" : amounts.currency();
        Instant exchangeAt = amounts.timePricing() == null ? now : attemptStartedAt;
        ExchangeRateProvider.Rate rate = exchangeAt == null ? null : exchangeRate(currency, exchangeAt);
        event.setExchangeRateToCny(rate == null ? null : rate.toCny());
        String priceVersion = amounts.pricingSnapshotVersion();
        event.setPricingSnapshotVersion(rate == null
                ? priceVersion : priceVersion + "@fx-" + rate.snapshotVersion());
        event.setCurrency(currency);
        event.setUsageEventKind(UsageEventKind.MEASURED.value());
        event.setAmountStatus(amountStatus(amounts, currency, rate).value());
        event.setEventId(eventId);
        event.setInvocationId(invocation == null ? null : invocation.invocationId());
        event.setAttemptId(invocation == null ? null : invocation.attemptId());
        if (invocation != null && type == CostType.LLM_CHAT) {
            // measured 是完整终态快照的唯一所有者。冻结真实报告水位而不生成内部报告号，
            // 同水位终态可补全字段，晚到真实 revision 仍能覆盖更正，UNKNOWN 保持 null。
            costEventMapper.lockUsageAttempt(tenantIdLong, invocation.attemptId());
            CostEventDO effective = costEventMapper.effectiveUsageReport(tenantIdLong, invocation.attemptId());
            event.setUsageSnapshotRevision(effective == null || effective.getReportRevision() == null
                    ? 0L : effective.getReportRevision());
        }
        event.setRequestId(ctx.requestId());
        event.setRootInputId(ctx.rootInputId());
        event.setParentEventId(MeterScope.parentEventId());
        event.setSessionId(ctx.sessionId());
        event.setEndUserId(ctx.endUserId());
        event.setChannelId(ctx.channelId());
        event.setAgentId(ctx.agentId());
        event.setRunId(ctx.runId());
        event.setWorkflowInstanceId(ctx.workflowInstanceId());
        event.setExecutionId(ctx.executionId());
        event.setScopeId(ctx.scopeId());
        event.setScopeType(ctx.scopeType());
        event.setCallPoint(ctx.callPoint());
        event.setMeta(mergeMeta(ctx, extraMeta));
        event.setCreatedAt(now);
        attribute(event, ctx);
        if (exists(tenantIdLong, eventId)) {
            // 重试场景同 event_id 已入账（幂等键语义），跳过防双计
            log.info("用量事件已存在跳过 tenant={} eventId={}", tenantId, eventId);
            return;
        }
        try {
            if (costEventMapper.insert(event) == 0) return;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发同键竞态兜底：UNIQUE(tenant_id, event_id) 冲突视为已计费
            log.info("用量事件并发重复入账跳过 tenant={} eventId={}", tenantId, eventId);
            return;
        }
        enqueue(event);
        // 回写最近事件 ID：嵌套子作用域将其作为 parent_event_id（Langfuse 父子树）
        MeterScope.setLastEventId(event.getId());
        log.info("用量事件入账 tenant={} type={} model={} tokens={} 金额={}分 eventId={} requestId={}",
                tenantId, type.value(), model, tokens, amounts.total(), eventId, ctx.requestId());
    }

    /**
     * 追加一条对账或补偿事实，不更新原始事件。
     *
     * @param tenantId 租户
     * @param adjustment 追加事实
     * @return 已插入或幂等命中的追加事件
     */
    @Transactional(rollbackFor = Exception.class)
    public CostEventDO recordAdjustment(String tenantId, UsageAdjustment adjustment) {
        Objects.requireNonNull(adjustment, "adjustment");
        Long tenantIdLong = Long.parseLong(tenantId);
        if (adjustment.kind() == UsageEventKind.RECONCILIATION) {
            costEventMapper.lockReconciliation(tenantIdLong, adjustment.originalEventId());
        }
        CostEventDO original = findById(tenantIdLong, adjustment.originalEventId());
        if (original == null) {
            throw new IllegalArgumentException("original usage event is not visible to tenant");
        }
        CostEventDO existing = findByEventId(tenantIdLong, adjustment.eventId());
        if (existing != null) {
            assertSameAdjustment(existing, original, adjustment);
            return existing;
        }

        if (adjustment.kind() == UsageEventKind.RECONCILIATION) {
            CostEventDO latest = costEventMapper.latestReconciliation(tenantIdLong, original.getId());
            if (latest != null && unchangedReconciliation(latest, original, adjustment)) return latest;
        }

        CostEventDO event = new CostEventDO();
        event.setId(SnowflakeIds.nextId());
        event.setTenantId(tenantIdLong);
        event.setOriginKind(original.getOriginKind());
        event.setActorRef(original.getActorRef());
        event.setResponsibilityRef(original.getResponsibilityRef());
        event.setGroupBindingId(original.getGroupBindingId());
        event.setRequesterKind(original.getRequesterKind());
        event.setRequesterRef(original.getRequesterRef());
        event.setOriginSessionId(original.getOriginSessionId());
        event.setOriginEventId(original.getOriginEventId());
        event.setResponsibilityAgentId(original.getResponsibilityAgentId());
        event.setSystemAgentRunId(original.getSystemAgentRunId());
        event.setSystemAgentConfigurationRef(original.getSystemAgentConfigurationRef());
        event.setSystemAgentInvocationRef(original.getSystemAgentInvocationRef());
        event.setDeploymentId(original.getDeploymentId());
        event.setIntegrationId(original.getIntegrationId());
        event.setBusinessScopeId(original.getBusinessScopeId());
        event.setAgentDefinitionVersion(original.getAgentDefinitionVersion());
        event.setSourceOwnerKind(original.getSourceOwnerKind());
        event.setSourceOwnerTenantId(original.getSourceOwnerTenantId());
        event.setAgentDefinitionId(original.getAgentDefinitionId());
        event.setAgentRevisionId(original.getAgentRevisionId());
        event.setAgentRevision(original.getAgentRevision());
        event.setDeploymentRevision(original.getDeploymentRevision());
        event.setGrantId(original.getGrantId());
        event.setGrantRevision(original.getGrantRevision());
        event.setEventType(original.getEventType());
        event.setProvider(original.getProvider());
        event.setModel(original.getModel());
        Instant now = Instant.now();
        event.setOccurredAt(now);
        event.setIngestedAt(now);
        event.setQuantity(original.getQuantity());
        event.setMeasurementUnit(original.getMeasurementUnit());
        event.setUsageSource(UsageSource.UNKNOWN.name());
        event.setInputAmount(toCents(adjustment.inputAmount()));
        event.setOutputAmount(toCents(adjustment.outputAmount()));
        event.setTotalAmount(toCents(adjustment.totalAmount()));
        event.setInputAmountCents(toCentsLong(adjustment.inputAmount()));
        event.setOutputAmountCents(toCentsLong(adjustment.outputAmount()));
        event.setTotalAmountCents(toCentsLong(adjustment.totalAmount()));
        event.setExchangeRateToCny(adjustment.exchangeRateToCny());
        event.setPricingSnapshotVersion(adjustment.pricingSnapshotVersion());
        event.setCurrency(adjustment.currency() == null ? original.getCurrency() : adjustment.currency());
        event.setUsageEventKind(adjustment.kind().value());
        event.setAmountStatus(adjustment.amountStatus().value());
        event.setAdjustmentOfEventId(original.getId());
        event.setEventId(adjustment.eventId());
        event.setInvocationId(original.getInvocationId());
        event.setAttemptId(original.getAttemptId());
        event.setRequestId(original.getRequestId());
        event.setRootInputId(original.getRootInputId());
        event.setParentEventId(original.getId());
        event.setSessionId(original.getSessionId());
        event.setEndUserId(original.getEndUserId());
        event.setChannelId(original.getChannelId());
        event.setAgentId(original.getAgentId());
        event.setRunId(original.getRunId());
        event.setWorkflowInstanceId(original.getWorkflowInstanceId());
        event.setExecutionId(original.getExecutionId());
        event.setScopeId(original.getScopeId());
        event.setScopeType(original.getScopeType());
        event.setCallPoint(original.getCallPoint());
        Map<String, Object> meta = new LinkedHashMap<>(adjustment.meta());
        meta.put("adjustment_of_event_id", original.getId());
        event.setMeta(meta);
        event.setCreatedAt(now);
        attribute(event);
        try {
            if (costEventMapper.insert(event) == 0) {
                CostEventDO raced = findByEventId(tenantIdLong, adjustment.eventId());
                if (raced == null) throw new IllegalStateException("usage_adjustment_conflict");
                assertSameAdjustment(raced, original, adjustment);
                return raced;
            }
            enqueue(event);
            return event;
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            CostEventDO raced = findByEventId(tenantIdLong, adjustment.eventId());
            if (raced != null) {
                assertSameAdjustment(raced, original, adjustment);
                return raced;
            }
            throw exception;
        }
    }

    /** 同一原调用的新外部键只有权威成本内容变化才追加；增减 adjustment 不经过此折叠。 */
    private boolean unchangedReconciliation(CostEventDO latest, CostEventDO original, UsageAdjustment adjustment) {
        String currency = adjustment.currency() == null ? original.getCurrency() : adjustment.currency();
        return Objects.equals(latest.getAmountStatus(), adjustment.amountStatus().value())
                && sameAmount(latest.getInputAmount(), adjustment.inputAmount())
                && sameAmount(latest.getOutputAmount(), adjustment.outputAmount())
                && sameAmount(latest.getTotalAmount(), adjustment.totalAmount())
                && sameAmount(latest.getExchangeRateToCny(), adjustment.exchangeRateToCny())
                && Objects.equals(latest.getPricingSnapshotVersion(), adjustment.pricingSnapshotVersion())
                && Objects.equals(latest.getCurrency(), currency);
    }

    /**
     * 记录不产生 token 的受治理外部操作（知识源或 Workflow 外部动作）。
     * 调用方必须在事件 ID 中提供稳定动作身份；方法自行建立计量作用域，避免把控制操作
     * 错误伪装成 LLM 调用或依赖一个可能不存在的用户请求作用域。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordExternal(
            String tenantId, CostType type, String provider, String model,
            CostContext context, Map<String, Object> extraMeta) {
        if (type != CostType.KNOWLEDGE_SOURCE && type != CostType.TOOL_CALL && type != CostType.WORKFLOW_EXTERNAL) {
            throw new IllegalArgumentException("recordExternal only accepts external operation cost types");
        }
        try (MeterScope ignored = MeterScope.open(context)) {
            recordLlm(tenantId, type, provider, model, LlmUsage.empty(), 1, extraMeta);
        }
    }

    /**
     * 记录请求根事件（request：每次用户请求一条，quantity=1、Token 不适用、无金额）。
     *
     * <p>由 {@code ChatConversationDirector} 受理后调用（不依赖 MeterScope，显式传上下文）：
     * 无 LLM 调用的已受理请求同样入账，作为月配额计数与链路根。
     *
     * @param tenantId  租户 ID
     * @param eventId   业务幂等 identifier（调用方生成）
     * @param requestId 请求根 ID
     * @param ctx       关联维度上下文
     * @param occurredAt 请求开始时间
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordRequest(
            String tenantId, String eventId, String requestId, CostContext ctx, Instant occurredAt) {
        Long tenantIdLong = Long.parseLong(tenantId);
        CostEventDO event = requestFact(tenantId,eventId,requestId,ctx,occurredAt);
        attribute(event, ctx);
        if (exists(tenantIdLong, eventId)) {
            log.info("请求根用量事件已存在跳过 tenant={} eventId={}", tenantId, eventId);
            return;
        }
        try {
            if (costEventMapper.insert(event) == 0) return;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发重放同一 inputId：唯一键冲突视为原请求已入账。
            log.info("请求根用量事件并发重复入账跳过 tenant={} eventId={}", tenantId, eventId);
            return;
        }
        enqueue(event);
        log.info("请求根事件入账 tenant={} requestId={} eventId={}", tenantId, requestId, eventId);
    }

    /** 请求根的唯一事实构造；普通Spring入口与宿主借用事务共用同一计量语义。 */
    public static CostEventDO requestFact(String tenantId,String eventId,String requestId,CostContext ctx,Instant occurredAt) {
        Long tenantIdLong=Long.parseLong(tenantId);
        CostEventDO event = new CostEventDO();
        event.setId(SnowflakeIds.nextId());
        event.setTenantId(tenantIdLong);
        event.setEventType(CostType.REQUEST.value());
        event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        event.setIngestedAt(Instant.now());
        event.setQuantity(1);
        event.setMeasurementUnit(CostType.REQUEST.measurementUnit());
        // 请求根事件：无 token 无金额（只记原子事实，不携带聚合值）
        event.setTokens(null);
        event.setUsageEventKind(UsageEventKind.MEASURED.value());
        event.setAmountStatus(AmountStatus.NOT_APPLICABLE.value());
        event.setCurrency(null);
        event.setUsageSource(UsageSource.UNKNOWN.name());
        event.setEventId(eventId);
        event.setRequestId(requestId);
        event.setRootInputId(ctx == null ? null : ctx.rootInputId());
        event.setSessionId(ctx == null ? null : ctx.sessionId());
        event.setEndUserId(ctx == null ? null : ctx.endUserId());
        event.setChannelId(ctx == null ? null : ctx.channelId());
        event.setAgentId(ctx == null ? null : ctx.agentId());
        event.setRunId(ctx == null ? null : ctx.runId());
        event.setWorkflowInstanceId(ctx == null ? null : ctx.workflowInstanceId());
        event.setExecutionId(ctx == null ? null : ctx.executionId());
        event.setScopeId(ctx == null ? null : ctx.scopeId());
        event.setScopeType(ctx == null ? null : ctx.scopeType());
        event.setCallPoint(ctx == null ? null : ctx.callPoint());
        // 请求根事件也必须携带同一条 TraceContext，TraceView 才能从根事件开始
        // 重建 inputId/rootInputId，而不是只在模型调用事件上看到因果标识。
        event.setMeta(mergeMeta(ctx, null));
        event.setCreatedAt(Instant.now());
        return event;
    }

    /** 从正式装配的持久来源解析身份，元数据不能覆盖归属。 */
    private void attribute(CostEventDO event) {
        attribute(event, null);
    }

    private void attribute(CostEventDO event, CostContext context) {
        log.debug("计量归属进入 tenant={}", event.getTenantId());
        String origin = context != null && context.attributionRunId() != null
                ? context.attributionRunId() : event.getRunId();
        if (platformUsageRecorder != null) platformUsageRecorder.attribute(event, origin);
        log.debug("计量归属退出 tenant={} originKind={}", event.getTenantId(), event.getOriginKind());
    }

    /** 新插入的原子事实与待发布投影共用事务提交。 */
    private void enqueue(CostEventDO event) {
        log.debug("计量导出进入 tenant={}", event.getTenantId());
        if (platformUsageRecorder != null) platformUsageRecorder.enqueue(event);
        log.debug("计量导出退出 tenant={}", event.getTenantId());
    }

    /**
     * 构造补充 meta（事件上下文 call_point 等已入显式列，meta 只放调用点补充细节）。
     *
     * @param ctx       计量上下文
     * @param extraMeta 调用点补充细节
     * @return meta Map
     */
    private static Map<String, Object> mergeMeta(CostContext ctx, Map<String, Object> extraMeta) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (ctx != null && ctx.traceContext() != null) {
            var trace = ctx.traceContext();
            meta.put("input_id", trace.inputId());
            meta.put("root_input_id", trace.rootInputId());
            meta.put("trace_id", trace.traceId());
            meta.put("span_id", trace.spanId());
            meta.put("caused_by_event_id", trace.causedByEventId());
        }
        if (extraMeta != null) {
            meta.putAll(extraMeta);
        }
        return meta;
    }

    private ExchangeRateProvider.Rate exchangeRate(String currency, Instant occurredAt) {
        if ("CNY".equalsIgnoreCase(currency)) {
            return new ExchangeRateProvider.Rate(java.math.BigDecimal.ONE, occurredAt, "cny-identity-v1");
        }
        if (exchangeRateProvider == null) {
            return null;
        }
        try {
            return exchangeRateProvider.toCny(currency, occurredAt).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("外部汇率 Provider 不可用，金额保持 unresolved currency={}: {}",
                    currency, exception.getMessage());
            return null;
        }
    }

    private AmountStatus amountStatus(Amounts amounts, String currency,
                                      ExchangeRateProvider.Rate rate) {
        if (amounts.total() == null) {
            return AmountStatus.UNPRICED;
        }
        if (!"CNY".equalsIgnoreCase(currency) && rate == null) {
            return AmountStatus.UNRESOLVED;
        }
        return AmountStatus.PRICED;
    }

    /**
     * 判断同租户同事件 ID 是否已存在（幂等先查后插）。
     *
     * @param tenantId 租户 ID
     * @param eventId  事件 ID
     * @return 是否已存在
     */
    private boolean exists(Long tenantId, String eventId) {
        Long count = costEventMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CostEventDO>()
                        .eq(CostEventDO::getTenantId, tenantId)
                        .eq(CostEventDO::getEventId, eventId));
        return count != null && count > 0;
    }

    private CostEventDO findByEventId(Long tenantId, String eventId) {
        return costEventMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CostEventDO>()
                .eq(CostEventDO::getTenantId, tenantId)
                .eq(CostEventDO::getEventId, eventId));
    }

    private CostEventDO findById(Long tenantId, Long eventId) {
        return costEventMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CostEventDO>()
                .eq(CostEventDO::getTenantId, tenantId)
                .eq(CostEventDO::getId, eventId));
    }

    /** 同键必须核对完整成本来源；仅总金额相同不能掩盖币种、汇率或价格快照更改。 */
    private void assertSameAdjustment(CostEventDO existing, CostEventDO original, UsageAdjustment adjustment) {
        String currency = adjustment.currency() == null ? original.getCurrency() : adjustment.currency();
        if (!Objects.equals(existing.getAdjustmentOfEventId(), adjustment.originalEventId())
                || !Objects.equals(existing.getUsageEventKind(), adjustment.kind().value())
                || !Objects.equals(existing.getAmountStatus(), adjustment.amountStatus().value())
                || !sameAmount(existing.getInputAmount(), adjustment.inputAmount())
                || !sameAmount(existing.getOutputAmount(), adjustment.outputAmount())
                || !sameAmount(existing.getTotalAmount(), adjustment.totalAmount())
                || !sameAmount(existing.getExchangeRateToCny(), adjustment.exchangeRateToCny())
                || !Objects.equals(existing.getCurrency(), currency)
                || !Objects.equals(existing.getPricingSnapshotVersion(), adjustment.pricingSnapshotVersion())) {
            throw new IllegalArgumentException("adjustment event id conflicts with an existing fact");
        }
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private BigDecimal toCents(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros();
    }

    private Long toCentsLong(BigDecimal amount) {
        return amount == null ? null : amount.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 兜底事件 ID（调用点未提供时）：{requestId|tenantId}:{type}:{雪花}。
     *
     * @param tenantId 租户 ID
     * @param type     事件类型
     * @param ctx      计量上下文
     * @return 事件 ID
     */
    private String fallbackEventId(String tenantId, CostType type, CostContext ctx) {
        String root = ctx.requestId() != null ? ctx.requestId() : tenantId;
        return root + ":" + type.value() + ":" + SnowflakeIds.nextId();
    }
}
