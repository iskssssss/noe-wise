package com.skillforge.core.chat.trajectory;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Optional;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 只读取已授权根运行及有持久父边的后代；不调用执行器或能力提供者。 */
@Repository
public class TrajectoryRepository {
    private final TrajectoryMapper mapper;
    private final PlatformObservationAccess access;
    public TrajectoryRepository(TrajectoryMapper mapper, PlatformObservationAccess access) { this.mapper = mapper; this.access = access; }

    List<RunFact> runs(TrajectoryQuery.Resource resource) {
        return access.read(resource.tenantId(), () -> mapper.runs(Long.toString(resource.tenantId()), resource.sessionId(), resource.rootInputId()));
    }
    ProductSession productSession(TrajectoryQuery.Resource resource) {
        return access.read(resource.tenantId(), () -> mapper.productSession(Long.toString(resource.tenantId()), resource.sessionId()));
    }
    List<Edge> edges(long tenantId, List<String> runs) { return access.read(tenantId, () -> mapper.edges(Long.toString(tenantId), runs)); }
    List<EventFact> events(long tenantId, List<RunFact> runs) { return access.read(tenantId, () -> mapper.events(Long.toString(tenantId), runs, null)); }
    /** 列表摘要与步骤图只解释正式执行观测；终态详情仍按原水位读取完整事件。 */
    List<EventFact> graphEvents(long tenantId, List<RunFact> runs) {
        return access.read(tenantId, () -> mapper.events(Long.toString(tenantId), runs, com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE));
    }
    List<AttemptFact> attempts(long tenantId, List<String> runs) {
        return access.read(tenantId, () -> mapper.attempts(Long.toString(tenantId), runs, AgentExecutionCodec.MODEL_REQUEST_SCHEMA, AgentExecutionCodec.MODEL_MANIFEST_SCHEMA));
    }
    List<AuxiliaryAttemptFact> auxiliaryAttempts(long tenantId, List<String> runs) {
        return runs.isEmpty() ? List.of() : access.read(tenantId, () -> mapper.auxiliaryAttempts(Long.toString(tenantId), runs));
    }
    TypedPayload material(long tenantId, String reference) {
        var row = access.read(tenantId, () -> mapper.material(Long.toString(tenantId), reference));
        return row == null ? null : new TypedPayload(row.schemaId(), row.canonicalJson());
    }
    /** 调用者已核验该Run/Attempt所属轨迹；只批读其原请求manifest引用的固定叶。 */
    List<Optional<ExecutionStore.Material>> modelMaterials(long tenantId, String run, String request, List<ResultRef> references) {
        var rows = access.read(tenantId, () -> mapper.modelMaterials(Long.toString(tenantId), run, request,
                references.stream().map(ResultRef::value).toList()));
        var indexed = new java.util.HashMap<ResultRef, ExecutionStore.Material>();
        for (var row : rows) {
            var ref = new ResultRef(row.reference());
            indexed.put(ref, new ExecutionStore.Material(ref, new TypedPayload(row.schemaId(), row.canonicalJson())));
        }
        return references.stream().map(ref -> Optional.ofNullable(indexed.get(ref))).toList();
    }
    CitationEvent citationEvent(long tenant, String session, String message) {
        return access.read(tenant, () -> mapper.citationEvent(Long.toString(tenant), session, message));
    }
    List<CitationAttempt> citationAttempts(long tenant, String run, String result) {
        return access.read(tenant, () -> mapper.citationAttempts(Long.toString(tenant), run, result));
    }
    public record CitationEvent(String runId, String agentId, String inputId, String rootInputId, String traceId, String spanId,
            String causedByEventId, String canonicalJson) { }
    public record CitationAttempt(String requestRef, String resultRef, long actionOrdinal) { }
    Instant now() { return mapper.readAt(); }

    public record RunFact(String runId, String sessionId, String rootInputId, String inputId,
                          String traceId, String spanId, String agentId, String status, String phase,
                          long createdSecond, int createdNano, long updatedSecond, int updatedNano,
                          long executionUpper, long memoryUpper) {
        public Instant createdAt() { return Instant.ofEpochSecond(createdSecond, createdNano); }
        public Instant updatedAt() { return Instant.ofEpochSecond(updatedSecond, updatedNano); }
    }
    public record Edge(String parentRunId, String parentActionId, String parentAttemptId,
                       String childRunId, String originKind) { }
    public record EventFact(String source, String sessionId, String runId, String actionId,
                            String eventId, String eventType, String schemaId, String canonicalJson,
                            String inputId, String rootInputId, String traceId, String spanId,
                            long sequence, long occurredSecond, int occurredNano) {
        public Instant occurredAt() { return Instant.ofEpochSecond(occurredSecond, occurredNano); }
    }
    /** 已验证实际Action/Attempt与材料闭包；参数正文仅在content端点按引用读取。 */
    public record AttemptFact(String runId, String sessionId, String actionId, String kind,
                              String target, String attemptId, long observationSequence,
                              String requestRef, String resultRef, String resultKind,
                              String batchModelActionId, String provider, String model,
                              String invocationId, Instant startedAt, Instant finishedAt,
                              Long promptTokens, Long completionTokens, Long cachedInputTokens,
                              Long cacheWriteInputTokens, Long reasoningOutputTokens,
                              String usageSource, String callPoint, Instant ingestedAt,
                              boolean diagnosticAvailable) implements ModelUsageFact { }
    /** 辅助调用由父Run预算与真实外调账本精确关联，不拥有Core Action或typed材料。 */
    public record AuxiliaryAttemptFact(String runId, String sessionId, String rootInputId, String inputId,
            String attemptId, String invocationId, String status, String provider, String model, String traceId, String spanId,
            Instant startedAt, Instant finishedAt, Long promptTokens, Long completionTokens, Long cachedInputTokens,
            Long cacheWriteInputTokens, Long reasoningOutputTokens, String usageSource, String callPoint,
            Instant ingestedAt, String sourceRootInputId) implements ModelUsageFact { }
    /** 两种真实模型来源共享计量字段，来源身份及材料边界仍由各自事实类型持有。 */
    public interface ModelUsageFact {
        String runId(); String attemptId(); String invocationId(); String provider(); String model();
        Instant startedAt(); Instant finishedAt(); Long promptTokens(); Long completionTokens();
        Long cachedInputTokens(); Long cacheWriteInputTokens(); Long reasoningOutputTokens();
        String usageSource(); String callPoint(); Instant ingestedAt();
    }
    public record ModelMaterialFact(String reference, String schemaId, String canonicalJson) { }
    public record MaterialFact(String schemaId, String canonicalJson) { }
    public record ProductSession(String tenantName, String agentId, String agentName) { }
}
