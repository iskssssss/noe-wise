package com.skillforge.core.chat.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.application.BillingTraceQueryService;
import com.skillforge.core.billing.application.BillingTraceFacts;
import com.skillforge.core.capability.connector.MemorySearchObservation;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationQuery;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.observation.SessionObservationService;
import com.skillforge.core.chat.observation.SessionObservationView;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.knowledge.KnowledgeObservationAccess;
import com.skillforge.core.model.invocation.ModelCallRecordReader;
import com.skillforge.core.model.invocation.ModelCallRecordStatus;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.session.ExecutionCapabilityInspector;
import com.skillforge.runtime.session.ExecutionModelAttemptInspector;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 从已提交运行、操作、记忆及计量事实重建轨迹，读取不会派发任何能力调用。 */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class TrajectoryProjectionService {
    private static final int CHILD_PAGE_SIZE = 200;
    private static final int MAX_STEP_QUERY_CODE_POINTS = 256;
    private static final String SNAPSHOT_PURPOSE = "trajectory-snapshot-v3";
    private static final String CURSOR_PURPOSE = "trajectory-cursor-v1";
    private static final String CONTENT_PURPOSE = "trajectory-content-v2";
    private final TrajectoryRepository repository;
    private final SessionObservationAuthorizer authorizer;
    private final SessionObservationService sessions;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final ObservationReadAudit audit;
    private final PlatformObservationAccess platformReads;
    private final BillingTraceQueryService billing;
    private final TrajectorySnapshotCache snapshots;
    private final ObjectProvider<TrajectorySourceAuthorizer> sourceAuthorizers;
    private final KnowledgeObservationAccess knowledgeAccess;
    private final ModelCallRecordReader modelRecords;
    private final ExecutionModelAttemptInspector modelInspector;
    private final int maxCodeUnits;
    private final int maxNodes;
    private final int maxDepth;

    public TrajectoryProjectionService(TrajectoryRepository repository, SessionObservationAuthorizer authorizer,
            SessionObservationService sessions, CryptoService crypto, ObjectMapper json, ObservationReadAudit audit,
            PlatformObservationAccess platformReads,
            BillingTraceQueryService billing,
            TrajectorySnapshotCache snapshots,
            ObjectProvider<TrajectorySourceAuthorizer> sourceAuthorizers, KnowledgeObservationAccess knowledgeAccess,
            ModelCallRecordReader modelRecords,
            @Value("${agent-runtime.max-payload-code-units:8388608}") int maxCodeUnits,
            @Value("${agent-runtime.max-payload-nodes:100000}") int maxNodes,
            @Value("${agent-runtime.max-payload-depth:128}") int maxDepth) {
        this.repository = repository; this.authorizer = authorizer; this.sessions = sessions; this.crypto = crypto;
        this.json = json; this.audit = audit; this.billing = billing;
        this.platformReads = platformReads;
        this.snapshots = snapshots;
        this.sourceAuthorizers = sourceAuthorizers; this.knowledgeAccess = knowledgeAccess;
        this.modelRecords = modelRecords;
        this.modelInspector = new ExecutionModelAttemptInspector(maxCodeUnits, maxNodes, maxDepth);
        this.maxCodeUnits = maxCodeUnits; this.maxNodes = maxNodes; this.maxDepth = maxDepth;
    }

    public TrajectoryView.Summary summary(SessionObservationScope scope, TrajectoryQuery.Resource resource) {
        authorize(scope, resource);
        var captured = capture(scope, resource);
        var messages = messages(scope, resource);
        return summarize(scope, resource, captured, messages, graph(scope, captured));
    }

    public TrajectoryView.Detail detail(SessionObservationScope scope, TrajectoryQuery.Resource resource) {
        authorize(scope, resource);
        var captured = capture(scope, resource);
        var messages = messages(scope, resource);
        var events = repository.events(resource.tenantId(), captured.runs());
        var graph = graph(scope, captured, events);
        var summary = summarize(scope, resource, captured, messages, graph);
        var usage = usage(resource, captured, graph.nodes());
        return new TrajectoryView.Detail(summary, messages, usage, costs(resource, captured, usage),
                snapshotReference(captured), captured.readAt(), TrajectoryTerminationProjection.project(resource,
                        captured.runs(), captured.edges(), events, captured.attempts(), captured.auxiliary()));
    }

    public TrajectoryView.ObservationPage nodes(SessionObservationScope scope, TrajectoryQuery.Nodes query) {
        authorize(scope, query.resource());
        if (query.pageSize() < 1 || query.pageSize() > CHILD_PAGE_SIZE) throw bad("page_size 仅支持 1 至 200");
        if (query.query() != null && query.query().codePointCount(0, query.query().length()) > MAX_STEP_QUERY_CODE_POINTS)
            throw bad("步骤搜索内容最多为 256 个字符");
        String search = normalized(query.query());
        Cursor cursor = query.cursor() == null || query.cursor().isBlank() ? null : decode(query.cursor(), Cursor.class);
        String encodedSnapshot = cursor == null ? query.snapshot() : cursor.snapshot();
        if (cursor != null && (!CURSOR_PURPOSE.equals(cursor.purpose()) || !Objects.equals(search, cursor.query())
                || query.snapshot() != null && !query.snapshot().equals(encodedSnapshot))) throw bad("轨迹游标与查询条件不一致");
        Snapshot captured = encodedSnapshot == null || encodedSnapshot.isBlank() ? capture(scope, query.resource()) : readSnapshot(scope, query.resource(), encodedSnapshot);
        validate(scope, query.resource(), captured);
        captured = visible(scope, captured);
        if (encodedSnapshot == null || encodedSnapshot.isBlank()) encodedSnapshot = snapshotReference(captured);
        var graph = graph(scope, captured);
        var matching = graph.nodes().stream().filter(node -> matches(node, search)).toList();
        int offset = cursor == null ? 0 : cursor.offset();
        if (offset < 0 || offset > matching.size()) throw bad("轨迹游标位置无效");
        if (cursor == null && query.nodeId() != null && !query.nodeId().isBlank()) {
            // 聚合祖先也保留来源事件；深链必须使用事件实际所属操作，不能按展示顺序命中祖先。
            String target = graph.nodes().stream().anyMatch(node -> node.nodeId().equals(query.nodeId()))
                    ? query.nodeId() : graph.eventNodes().get(query.nodeId());
            int located = -1;
            for (int i = 0; i < matching.size(); i++) {
                var node = matching.get(i);
                if (node.nodeId().equals(target)) { located = i; break; }
            }
            if (located < 0) throw missing("轨迹节点不存在或不属于当前查询");
            offset = located / query.pageSize() * query.pageSize();
        }
        int through = Math.min(matching.size(), offset + query.pageSize());
        var items = matching.subList(offset, through);
        var byId = graph.nodes().stream().collect(Collectors.toMap(TrajectoryView.ObservationNode::nodeId, Function.identity()));
        Set<String> itemIds = items.stream().map(TrajectoryView.ObservationNode::nodeId).collect(Collectors.toSet());
        Map<String, TrajectoryView.ObservationNode> ancestors = new LinkedHashMap<>();
        for (var node : items) {
            Set<String> visited = new LinkedHashSet<>();
            for (String parent = node.parentNodeId(); parent != null && visited.add(parent); ) {
                var ancestor = byId.get(parent);
                if (ancestor == null) break;
                if (!itemIds.contains(parent)) ancestors.putIfAbsent(parent, ancestor);
                parent = ancestor.parentNodeId();
            }
        }
        String next = through < matching.size() ? encode(new Cursor(CURSOR_PURPOSE, encodedSnapshot, search, through)) : null;
        return new TrajectoryView.ObservationPage(items, List.copyOf(ancestors.values()), next, encodedSnapshot,
                graph.coverage(), captured.readAt());
    }

    public TrajectoryView.Content content(SessionObservationScope scope, TrajectoryQuery.Content query) {
        authorize(scope, query.resource());
        ContentKey key = decode(query.contentRef(), ContentKey.class);
        if (!CONTENT_PURPOSE.equals(key.purpose()) || !key.resource().equals(query.resource())
                || !key.actor().equals(scope.actorId()) || !key.scope().equals(scope.kind().name()))
            throw missing("轨迹内容不属于当前授权资源");
        if (!Set.of("input", "output").contains(key.side())) throw bad("轨迹内容区块无效");
        var authorized = captureSources(scope, query.resource());
        var runs = authorized.runs();
        if (runs.isEmpty() || runs.stream().noneMatch(run -> run.runId().equals(key.runId()) && run.sessionId().equals(key.sessionId())))
            throw missing("轨迹内容来源已不可用");
        if ("AUXILIARY_MODEL".equals(key.source())) return auxiliaryContent(scope, query, key, authorized);
        if (!"CORE_ATTEMPT".equals(key.source())) throw bad("轨迹内容来源类型无效");
        var attempts = authorized.attempts();
        var actual = attempts.stream().filter(attempt -> attempt.attemptId().equals(key.attemptId())
                && attempt.runId().equals(key.runId()) && attempt.actionId().equals(key.actionId())
                && attempt.requestRef().equals(key.requestRef())
                && ("input".equals(key.side()) || Objects.equals(attempt.resultRef(), key.resultRef())))
                .findFirst().orElseThrow(() -> missing("轨迹内容来源身份不匹配"));
        if ("output".equals(key.side()) && restrictedOutput(authorized, actual))
            return redacted(scope, query, key.side());
        var request = repository.material(query.tenantId(), key.requestRef());
        var result = key.resultRef() == null ? null : repository.material(query.tenantId(), key.resultRef());
        if (request == null) throw missing("轨迹内容未记录");
        if ("MODEL".equals(actual.kind())) {
            var inspected = modelInspector.inspect(request, java.util.Optional.ofNullable(result),
                    references -> repository.modelMaterials(query.tenantId(), key.runId(), key.requestRef(), references));
            Object value = "input".equals(key.side()) ? inspected.input()
                    : inspected.outcome().map(TrajectoryModelContent::outcome).orElse(null);
            String availability = value == null ? "NOT_RECORDED" : "FULL";
            auditContent(scope, query, key.side(), availability);
            try {
                return new TrajectoryView.Content(query.contentRef(), availability,
                        "input".equals(key.side()) ? TrajectoryModelContent.INPUT_SCHEMA : TrajectoryModelContent.OUTPUT_SCHEMA,
                        value == null ? null : json.writeValueAsString(value));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "模型原始内容无法读取");
            }
        }
        var safe = new ExecutionCapabilityInspector(maxCodeUnits, maxNodes, maxDepth).inspect(ExecutionStore.Kind.valueOf(actual.kind()),
                request, java.util.Optional.ofNullable(result));
        if (safe.knowledgeAccess().isPresent()) {
            var access = safe.knowledgeAccess().orElseThrow();
            var source = runs.stream().filter(run -> run.runId().equals(actual.runId())).findFirst().orElseThrow();
            if (!access.tenantId().value().equals(Long.toString(query.tenantId())) || !access.agentId().value().equals(source.agentId())
                    || !access.sessionRef().sessionId().equals(source.sessionId())) throw missing("知识来源身份不匹配");
            try {
                knowledgeAccess.requireRead(access.tenantId(), access.agentId(), access.sessionRef(), access.target(), access.sourceVersions(),
                        scope.kind() == SessionObservationScope.Kind.PLATFORM_ADMIN);
            } catch (ApiException denied) {
                if (denied.getStatus() != HttpStatus.FORBIDDEN) throw denied;
                return redacted(scope, query, key.side());
            }
        }
        if ("output".equals(key.side())) safe = MemorySearchObservation.restrictHistoricalOutput(safe);
        var payload = "input".equals(key.side()) ? safe.input() : safe.output().orElse(null);
        String availability = payload == null ? "NOT_RECORDED"
                : "input".equals(key.side()) ? safe.inputAvailability().name() : safe.outputAvailability().name();
        audit.record(Long.toString(query.tenantId()), scope.actorId(), "trajectory.content.read", "session", query.sessionId(),
                "读取已授权轨迹能力内容", Map.of("root_input_id", query.rootInputId(), "availability", availability, "side", key.side()));
        if (!"FULL".equals(availability) && !"PARTIAL".equals(availability))
            return new TrajectoryView.Content(query.contentRef(), availability, payload == null ? null : payload.schemaId(), null);
        return new TrajectoryView.Content(query.contentRef(), availability, payload.schemaId(), payload.canonicalJson());
    }
    private TrajectoryView.Content redacted(SessionObservationScope scope, TrajectoryQuery.Content query, String side) {
        audit.record(Long.toString(query.tenantId()), scope.actorId(), "trajectory.content.read", "session", query.sessionId(),
                "读取已授权轨迹能力内容", Map.of("root_input_id", query.rootInputId(), "availability", "REDACTED", "side", side));
        return new TrajectoryView.Content(query.contentRef(), "REDACTED", null, null);
    }

    /** 辅助材料只由当前可见的真实预算/外调闭包定位，不能借用相邻主模型材料。 */
    private TrajectoryView.Content auxiliaryContent(SessionObservationScope scope, TrajectoryQuery.Content query,
            ContentKey key, Snapshot authorized) {
        if (key.actionId() != null || key.requestRef() != null || key.resultRef() != null || key.invocationId() == null)
            throw bad("辅助模型内容引用身份无效");
        var actual = repository.auxiliaryAttempts(query.tenantId(), List.of(key.runId())).stream()
                .filter(attempt -> attempt.attemptId().equals(key.attemptId())
                && attempt.invocationId().equals(key.invocationId()) && attempt.runId().equals(key.runId())
                && attempt.sessionId().equals(key.sessionId()) && Objects.equals(attempt.rootInputId(), attempt.sourceRootInputId()))
                .findFirst().orElseThrow(() -> missing("辅助模型内容来源身份不匹配"));
        var record = platformReads.read(query.tenantId(),
                () -> modelRecords.find(query.tenantId(), actual.invocationId(), actual.attemptId()));
        if (record.isEmpty()) throw missing("辅助模型原始内容未记录");
        var saved = record.orElseThrow();
        if (saved.tenantId() != query.tenantId() || !saved.invocationId().equals(actual.invocationId())
                || !saved.attemptId().equals(actual.attemptId())) throw missing("辅助模型原始材料身份不匹配");
        var payload = "input".equals(key.side()) ? saved.request() : saved.outcome().orElse(null);
        String availability = payload == null ? "NOT_RECORDED" : "FULL";
        auditContent(scope, query, key.side(), availability);
        if (payload == null) return new TrajectoryView.Content(query.contentRef(), availability, null, null);
        return new TrajectoryView.Content(query.contentRef(), availability, payload.schemaId(), payload.canonicalJson());
    }

    private void auditContent(SessionObservationScope scope, TrajectoryQuery.Content query, String side, String availability) {
        audit.record(Long.toString(query.tenantId()), scope.actorId(), "trajectory.content.read", "session", query.sessionId(),
                "读取已授权轨迹原始内容", Map.of("root_input_id", query.rootInputId(), "availability", availability, "side", side));
    }

    /** 平台私有诊断复用唯一真实后代闭包；本方法不授予模型正文读取权。 */
    public String requireDescendantAttempt(SessionObservationScope scope, TrajectoryQuery.Resource parent,
            String sourceSessionId, String sourceRootId, String attemptId) {
        authorize(scope, parent);
        var captured = captureSources(scope, parent);
        var sources = captured.runs().stream().filter(run -> run.sessionId().equals(sourceSessionId)
                && run.rootInputId().equals(sourceRootId)).map(TrajectoryRepository.RunFact::runId).collect(Collectors.toSet());
        return captured.attempts().stream().filter(attempt -> sources.contains(attempt.runId())
                && attempt.attemptId().equals(attemptId) && "MODEL".equals(attempt.kind()))
                .map(TrajectoryRepository.AttemptFact::runId).findFirst()
                .orElseThrow(() -> missing("模型调用不存在或不属于已授权父轨迹"));
    }

    /** 当前入口已授权；正文与后代身份读取只需要真实可见来源，不加载展示计量。 */
    private Snapshot captureSources(SessionObservationScope scope, TrajectoryQuery.Resource resource) {
        var runs = repository.runs(resource);
        if (runs.isEmpty()) throw missing("根输入不存在或没有已提交执行记录");
        var ids = runs.stream().map(TrajectoryRepository.RunFact::runId).toList();
        var snapshot = new Snapshot(SNAPSHOT_PURPOSE, scope.kind().name(), scope.actorId(), resource,
                runs, repository.edges(resource.tenantId(), ids), List.of(), repository.attempts(resource.tenantId(), ids),
                List.of(), Map.of(), List.of(), repository.now());
        return visible(scope, snapshot);
    }

    /** 概览与节点快照需要辅助记录可用性和费用，正文读取不重复收集它们。 */
    private Snapshot capture(SessionObservationScope scope, TrajectoryQuery.Resource resource) {
        var authorized = captureSources(scope, resource);
        var allowedRuns = authorized.runs().stream().map(TrajectoryRepository.RunFact::runId).toList();
        var auxiliary = repository.auxiliaryAttempts(resource.tenantId(), allowedRuns);
        Map<String, ModelCallRecordStatus> auxiliaryContent = new LinkedHashMap<>();
        var keys = auxiliary.stream().filter(attempt -> Objects.equals(attempt.rootInputId(), attempt.sourceRootInputId()))
                .map(attempt -> new ModelCallRecordReader.Key(attempt.invocationId(), attempt.attemptId())).distinct().toList();
        if (!keys.isEmpty()) {
            var statuses = platformReads.read(resource.tenantId(), () -> modelRecords.statuses(resource.tenantId(), keys));
            for (var key : keys) {
                var status = statuses.get(key);
                if (status != null) auxiliaryContent.put(key.attemptId(), status);
            }
        }
        var sources = authorized.runs().stream().map(run -> new BillingTraceFacts.ReadScope(run.sessionId(), run.runId(), run.rootInputId())).toList();
        var costs = platformReads.read(resource.tenantId(), () -> billing.effectiveCostsForScopes(resource.tenantId(), sources));
        return new Snapshot(authorized.purpose(), authorized.scope(), authorized.actor(), resource, authorized.runs(), authorized.edges(),
                authorized.restricted(), authorized.attempts(), auxiliary, auxiliaryContent, costs, authorized.readAt());
    }
    private void authorize(SessionObservationScope scope, TrajectoryQuery.Resource resource) {
        if (resource == null || resource.sessionId() == null || resource.sessionId().isBlank()
                || resource.rootInputId() == null || resource.rootInputId().isBlank()) throw bad("session_id 和 root_input_id 必填");
        authorizer.requireSession(scope, resource.tenantId(), resource.sessionId());
        if (repository.productSession(resource) == null) throw missing("会话不存在或不属于指定机构");
    }
    private void validate(SessionObservationScope scope, TrajectoryQuery.Resource resource, Snapshot captured) {
        if (!SNAPSHOT_PURPOSE.equals(captured.purpose()) || !captured.resource().equals(resource)
                || !captured.actor().equals(scope.actorId()) || !captured.scope().equals(scope.kind().name())) throw bad("轨迹快照不属于当前授权资源");
        var current = repository.runs(resource).stream().collect(Collectors.toMap(TrajectoryRepository.RunFact::runId, Function.identity()));
        if (captured.runs().isEmpty() || captured.runs().stream().anyMatch(run -> !current.containsKey(run.runId())
                || !current.get(run.runId()).sessionId().equals(run.sessionId()))) throw missing("轨迹快照来源已不可用，请刷新");
    }
    private String snapshotReference(Snapshot captured) {
        return encode(new SnapshotReference(SNAPSHOT_PURPOSE, captured.scope(), captured.actor(), captured.resource(), snapshots.save(captured)));
    }
    private Snapshot readSnapshot(SessionObservationScope scope, TrajectoryQuery.Resource resource, String token) {
        var reference = decode(token, SnapshotReference.class);
        if (!SNAPSHOT_PURPOSE.equals(reference.purpose()) || !reference.resource().equals(resource)
                || !reference.scope().equals(scope.kind().name()) || !reference.actor().equals(scope.actorId()))
            throw bad("轨迹快照不属于当前授权资源");
        var captured = snapshots.read(reference.scope(), reference.actor(), reference.id());
        if (captured == null) throw bad("轨迹快照已过期，请刷新后重试");
        return captured;
    }
    private TrajectoryGraph.Result graph(SessionObservationScope scope, Snapshot captured) {
        return graph(scope, captured, repository.graphEvents(captured.resource().tenantId(), captured.runs()));
    }
    private TrajectoryGraph.Result graph(SessionObservationScope scope, Snapshot captured, List<TrajectoryRepository.EventFact> events) {
        var result = TrajectoryGraph.build(captured.runs(), captured.edges(), captured.attempts(), captured.auxiliary(),
                events, maxCodeUnits, maxNodes, maxDepth,
                (attempt, side) -> "output".equals(side) && restrictedOutput(captured, attempt)
                        ? TrajectoryView.ContentReference.restricted() : reference(scope, captured.resource(), attempt, side),
                (attempt, side) -> auxiliaryReference(scope, captured, attempt, side));
        if (captured.restricted().isEmpty()) return result;
        var nodes = new ArrayList<>(result.nodes());
        for (var edge : captured.restricted()) {
            String parent = nodes.stream().filter(node -> node.attempts().stream().anyMatch(attempt -> attempt.attemptId().equals(edge.parentAttemptId())))
                    .map(TrajectoryView.ObservationNode::nodeId).findFirst().orElse(edge.parentActionId() == null ? "agent:" + edge.parentRunId() : edge.parentActionId());
            String recordedParent = parent;
            if (nodes.stream().noneMatch(node -> node.nodeId().equals(recordedParent))) parent = "agent:" + edge.parentRunId();
            String id = "restricted-agent:" + java.util.UUID.nameUUIDFromBytes((edge.parentRunId() + "\0" + edge.parentActionId() + "\0" + edge.childRunId()).getBytes(StandardCharsets.UTF_8));
            nodes.add(new TrajectoryView.ObservationNode(id, parent, TrajectoryView.Kind.AGENT, "子 Agent（内容受限）", "REDACTED",
                    null, captured.resource().rootInputId(), null, null, null, null, null, null, null, null, null, null, "0",
                    "当前身份没有该子运行的读取权限", TrajectoryView.ContentReference.restricted(), TrajectoryView.ContentReference.restricted(), List.of(), List.of()));
        }
        return new TrajectoryGraph.Result(List.copyOf(nodes), "PARTIAL", result.eventNodes());
    }
    private static boolean restrictedOutput(Snapshot snapshot, TrajectoryRepository.AttemptFact attempt) {
        return snapshot.restricted().stream().anyMatch(edge -> attempt.runId().equals(edge.parentRunId())
                && attempt.actionId().equals(edge.parentActionId())
                && (edge.parentAttemptId() == null || attempt.attemptId().equals(edge.parentAttemptId())));
    }

    /** 来源权限随每次读取重验；拒绝的子运行及其后代不进入事件、正文或用量投影。 */
    private Snapshot visible(SessionObservationScope scope, Snapshot snapshot) {
        var byRun = snapshot.runs().stream().collect(Collectors.toMap(TrajectoryRepository.RunFact::runId, Function.identity()));
        Set<String> children = snapshot.edges().stream().map(TrajectoryRepository.Edge::childRunId).collect(Collectors.toSet());
        Set<String> allowed = snapshot.runs().stream().filter(run -> run.sessionId().equals(snapshot.resource().sessionId())
                && run.rootInputId().equals(snapshot.resource().rootInputId()) && !children.contains(run.runId())).map(TrajectoryRepository.RunFact::runId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (allowed.isEmpty()) snapshot.runs().stream().filter(run -> run.sessionId().equals(snapshot.resource().sessionId())
                && run.rootInputId().equals(snapshot.resource().rootInputId())).findFirst().ifPresent(run -> allowed.add(run.runId()));
        Set<TrajectoryRepository.Edge> restricted = new LinkedHashSet<>(snapshot.restricted());
        var authorizer = sourceAuthorizers.getIfAvailable();
        boolean changed;
        do {
            changed = false;
            for (var edge : snapshot.edges()) {
                if (!allowed.contains(edge.parentRunId()) || allowed.contains(edge.childRunId())) continue;
                var source = byRun.get(edge.childRunId());
                if (source == null) continue;
                boolean readable = scope.kind() == SessionObservationScope.Kind.PLATFORM_ADMIN || authorizer != null
                        && authorizer.canRead(scope, snapshot.resource(), new TrajectorySourceAuthorizer.Source(snapshot.resource().tenantId(),
                                source.sessionId(), source.rootInputId(), source.runId(), source.agentId(), edge.parentRunId(), edge.originKind()));
                if (readable) { changed |= allowed.add(source.runId()); restricted.remove(edge); }
                else restricted.add(edge);
            }
        } while (changed);
        var visibleEdges = snapshot.edges().stream().filter(edge -> allowed.contains(edge.parentRunId()) && allowed.contains(edge.childRunId())).toList();
        var visibleAuxiliary = snapshot.auxiliary().stream().filter(attempt -> allowed.contains(attempt.runId())).toList();
        var visibleAuxiliaryAttempts = visibleAuxiliary.stream().map(TrajectoryRepository.AuxiliaryAttemptFact::attemptId).collect(Collectors.toSet());
        return new Snapshot(snapshot.purpose(), snapshot.scope(), snapshot.actor(), snapshot.resource(),
                snapshot.runs().stream().filter(run -> allowed.contains(run.runId())).toList(), visibleEdges,
                restricted.stream().filter(edge -> allowed.contains(edge.parentRunId()) && !allowed.contains(edge.childRunId())).toList(),
                snapshot.attempts().stream().filter(attempt -> allowed.contains(attempt.runId())).toList(),
                visibleAuxiliary,
                snapshot.auxiliaryContent().entrySet().stream().filter(entry -> visibleAuxiliaryAttempts.contains(entry.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                snapshot.costs().stream().filter(cost -> allowed.contains(cost.runId())).toList(), snapshot.readAt());
    }
    private TrajectoryView.ContentReference reference(SessionObservationScope scope, TrajectoryQuery.Resource resource,
            TrajectoryRepository.AttemptFact attempt, String side) {
        if (attempt.requestRef() == null) return TrajectoryView.ContentReference.missing();
        if ("MODEL".equals(attempt.kind()) && !attempt.diagnosticAvailable())
            return TrajectoryView.ContentReference.missing();
        if ("output".equals(side) && attempt.resultRef() == null) return TrajectoryView.ContentReference.missing();
        String ref = encode(new ContentKey(CONTENT_PURPOSE, scope.kind().name(), scope.actorId(), resource,
                "CORE_ATTEMPT", attempt.sessionId(), attempt.runId(), attempt.actionId(), attempt.attemptId(),
                null, attempt.requestRef(), attempt.resultRef(), side));
        return new TrajectoryView.ContentReference(ref, "AVAILABLE");
    }
    private TrajectoryView.ContentReference auxiliaryReference(SessionObservationScope scope, Snapshot snapshot,
            TrajectoryRepository.AuxiliaryAttemptFact attempt, String side) {
        var status = snapshot.auxiliaryContent().get(attempt.attemptId());
        if (status == null || ("input".equals(side) ? !status.requestRecorded() : !status.outcomeRecorded()))
            return TrajectoryView.ContentReference.missing();
        String ref = encode(new ContentKey(CONTENT_PURPOSE, scope.kind().name(), scope.actorId(), snapshot.resource(),
                "AUXILIARY_MODEL", attempt.sessionId(), attempt.runId(), null, attempt.attemptId(),
                attempt.invocationId(), null, null, side));
        return new TrajectoryView.ContentReference(ref, "AVAILABLE");
    }
    private List<SessionObservationView.Message> messages(SessionObservationScope scope, TrajectoryQuery.Resource resource) {
        var result = new LinkedHashMap<String, SessionObservationView.Message>();
        String cursor = null;
        do {
            var page = sessions.messages(scope, new SessionObservationQuery.Messages(resource.tenantId(), resource.sessionId(),
                    resource.rootInputId(), cursor, CHILD_PAGE_SIZE));
            for (var message : page.items()) result.put(message.messageId(), message);
            cursor = page.nextCursor();
        } while (cursor != null);
        return result.values().stream().sorted(Comparator.comparing(SessionObservationView.Message::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(SessionObservationView.Message::messageId)).toList();
    }
    private TrajectoryView.Summary summarize(SessionObservationScope scope, TrajectoryQuery.Resource resource, Snapshot captured,
            List<SessionObservationView.Message> messages, TrajectoryGraph.Result graph) {
        var session = repository.productSession(resource);
        if (session == null) throw missing("会话不存在或不属于指定机构");
        return summarize(resource, captured, messages, graph, session);
    }

    private TrajectoryView.Summary summarize(TrajectoryQuery.Resource resource, Snapshot captured,
            List<SessionObservationView.Message> messages, TrajectoryGraph.Result graph, TrajectoryRepository.ProductSession session) {
        Set<String> children = captured.edges().stream().map(TrajectoryRepository.Edge::childRunId).collect(Collectors.toSet());
        var roots = captured.runs().stream().filter(run -> run.sessionId().equals(resource.sessionId()) && run.rootInputId().equals(resource.rootInputId())
                && !children.contains(run.runId())).toList();
        if (roots.isEmpty()) roots = captured.runs().stream().filter(run -> run.sessionId().equals(resource.sessionId())
                && run.rootInputId().equals(resource.rootInputId())).limit(1).toList();
        var status = roots.stream().anyMatch(run -> "UNCERTAIN".equals(run.phase())) ? "OUTCOME_UNKNOWN"
                : roots.stream().allMatch(run -> "QUEUED".equals(run.status())) ? "QUEUED"
                : roots.stream().anyMatch(run -> Set.of("RUNNING", "QUEUED", "RESUMING").contains(run.status())) ? "RUNNING"
                : roots.stream().anyMatch(run -> "SUSPENDED".equals(run.status())) ? "WAITING"
                : roots.stream().anyMatch(run -> "FAILED".equals(run.status())) ? "FAILED"
                : roots.stream().allMatch(run -> "CANCELLED".equals(run.status())) ? "CANCELLED"
                : roots.stream().anyMatch(run -> "TERMINATED".equals(run.status())) ? "TERMINATED" : "COMPLETED";
        Instant start = roots.stream().map(TrajectoryRepository.RunFact::createdAt).min(Comparator.naturalOrder()).orElse(null);
        Instant finish = roots.stream().allMatch(run -> TrajectoryGraph.terminal(run.status()))
                ? roots.stream().map(TrajectoryRepository.RunFact::updatedAt).max(Comparator.naturalOrder()).orElse(null) : null;
        var usage = usage(resource, captured, graph.nodes());
        String input = messages.stream().filter(message -> "user".equals(message.role())).map(SessionObservationView.Message::content)
                .filter(Objects::nonNull).collect(Collectors.joining("\n"));
        String output = messages.stream().filter(message -> "assistant".equals(message.role()) && "final".equalsIgnoreCase(message.phase()))
                .map(SessionObservationView.Message::content).filter(Objects::nonNull).collect(Collectors.joining("\n"));
        String answer = output.isBlank() ? Set.of("RUNNING", "QUEUED", "WAITING").contains(status) ? "IN_PROGRESS"
                : Set.of("FAILED", "TERMINATED").contains(status) ? "FAILED" : "NONE" : "COMPLETED";
        var models = modelAttempts(captured).stream().map(TrajectoryRepository.ModelUsageFact::model).filter(Objects::nonNull).distinct().toList();
        var tools = graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.TOOL).map(TrajectoryView.ObservationNode::name).distinct().toList();
        var traces = graph.nodes().stream().map(TrajectoryView.ObservationNode::traceId).filter(Objects::nonNull).distinct().toList();
        int steps = (int) graph.nodes().stream().filter(node -> node.kind() == TrajectoryView.Kind.STEP)
                .map(node -> node.runId() + "\0" + Objects.requireNonNullElse(node.stepId(), node.nodeId())).distinct().count();
        int modelCalls = modelAttempts(captured).size();
        int toolCalls = (int) captured.attempts().stream().filter(value -> !"MODEL".equals(value.kind())).count();
        return new TrajectoryView.Summary(Long.toString(resource.tenantId()), session.tenantName(), resource.sessionId(), resource.rootInputId(),
                session.agentId(), session.agentName(), input.isBlank() ? null : input, output.isBlank() ? null : output, status, answer,
                start, finish, TrajectoryGraph.duration(start, finish), traces, models, tools, steps, modelCalls,
                toolCalls, usage.totals(), usage.coverage());
    }

    private SessionObservationView.Usage usage(TrajectoryQuery.Resource resource, Snapshot captured, List<TrajectoryView.ObservationNode> nodes) {
        var models = modelAttempts(captured);
        var sourceRuns = captured.runs().stream().collect(Collectors.toMap(TrajectoryRepository.RunFact::runId, Function.identity()));
        int unknown = (int) models.stream().filter(value -> "UNKNOWN".equals(TrajectoryGraph.report(value))).count();
        int partial = (int) models.stream().filter(value -> "PARTIAL".equals(TrajectoryGraph.report(value))).count();
        int zero = (int) models.stream().filter(value -> "ZERO".equals(TrajectoryGraph.report(value))).count();
        var coverage = new SessionObservationView.Coverage(models.isEmpty() ? "NO_CALLS" : unknown == models.size() ? "UNKNOWN"
                : unknown > 0 || partial > 0 ? "PARTIAL" : "COMPLETE", models.size(), models.size() - unknown - partial, unknown, partial, zero);
        var totals = totals(models);
        var paired = models.stream().filter(value -> value.promptTokens() != null && value.cachedInputTokens() != null && value.cachedInputTokens() <= value.promptTokens()).toList();
        BigInteger input = sum(paired, TrajectoryRepository.ModelUsageFact::promptTokens), cached = sum(paired, TrajectoryRepository.ModelUsageFact::cachedInputTokens);
        String ratio = input == null || input.signum() == 0 ? null : new java.math.BigDecimal(cached).divide(new java.math.BigDecimal(input), 8, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        var cache = new SessionObservationView.CacheStats(paired.isEmpty() ? "UNKNOWN" : input.signum() == 0 ? "NOT_APPLICABLE" : "AVAILABLE",
                paired.size(), models.size(), text(input), text(cached), ratio);
        var attempts = models.stream().map(value -> new SessionObservationView.UsageAttempt(value.attemptId(), value.invocationId(),
                nodes.stream().filter(node -> node.attempts().stream().anyMatch(attempt -> attempt.attemptId().equals(value.attemptId()))).map(TrajectoryView.ObservationNode::nodeId).findFirst().orElse(null),
                value.runId(), value.provider(), value.model(), value.callPoint(), TrajectoryGraph.report(value), value.usageSource(),
                TrajectoryGraph.totals(value), value.ingestedAt(), value instanceof TrajectoryRepository.AttemptFact core && core.diagnosticAvailable(),
                sourceRuns.get(value.runId()).sessionId(), sourceRuns.get(value.runId()).rootInputId())).toList();
        return new SessionObservationView.Usage(Long.toString(resource.tenantId()), resource.sessionId(), resource.rootInputId(), captured.readAt(),
                coverage, totals, cache, List.of(), attempts, null);
    }
    private TrajectoryView.UsageSummary costs(TrajectoryQuery.Resource resource, Snapshot captured, SessionObservationView.Usage usage) {
        Set<String> events = new LinkedHashSet<>(), measuredAttempts = new LinkedHashSet<>();
        Map<String, java.math.BigDecimal> amounts = new LinkedHashMap<>();
        var byRun = captured.runs().stream().collect(Collectors.toMap(TrajectoryRepository.RunFact::runId, Function.identity()));
        int priced = 0;
        for (var fact : captured.costs()) {
            var run = byRun.get(fact.runId());
            if (fact.tenantId() != resource.tenantId() || run == null || !run.sessionId().equals(fact.sessionId())
                    || !run.rootInputId().equals(fact.rootInputId()) || !events.add(fact.eventId())) continue;
            if (fact.attemptId() != null) measuredAttempts.add(fact.attemptId());
            if ("priced".equals(fact.costStatus()) && fact.confirmedCostCents() != null) priced++;
            if (fact.confirmedCostCents() != null) amounts.merge(fact.currency(), fact.confirmedCostCents(), java.math.BigDecimal::add);
        }
        int missing = (int) modelAttempts(captured).stream().filter(attempt -> !measuredAttempts.contains(attempt.attemptId())).count();
        int unconfirmed = events.size() - priced;
        var status = events.isEmpty() && missing == 0 ? TrajectoryView.CostStatus.NO_COSTS
                : unconfirmed == 0 && missing == 0 ? TrajectoryView.CostStatus.COMPLETE
                : !amounts.isEmpty() ? TrajectoryView.CostStatus.PARTIAL : TrajectoryView.CostStatus.UNKNOWN;
        var coverage = new TrajectoryView.CostCoverage(status, events.size(), priced, unconfirmed, missing);
        var decimalAmounts = amounts.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().stripTrailingZeros().toPlainString()));
        BigInteger input = integer(usage.totals().promptTokens()), output = integer(usage.totals().completionTokens());
        String tokens = input == null || output == null ? null : input.add(output).toString();
        return new TrajectoryView.UsageSummary(usage.coverage().totalAttempts(), tokens, usage.totals().promptTokens(), usage.totals().completionTokens(),
                decimalAmounts, usage.coverage().unknownAttempts(), coverage);
    }
    private static List<TrajectoryRepository.ModelUsageFact> modelAttempts(Snapshot snapshot) {
        Map<String, TrajectoryRepository.ModelUsageFact> attempts = new LinkedHashMap<>();
        snapshot.attempts().stream().filter(attempt -> "MODEL".equals(attempt.kind())).forEach(attempt -> attempts.put(attempt.attemptId(), attempt));
        snapshot.auxiliary().forEach(attempt -> attempts.putIfAbsent(attempt.attemptId(), attempt));
        return List.copyOf(attempts.values());
    }
    private static SessionObservationView.TokenTotals totals(List<TrajectoryRepository.ModelUsageFact> values) {
        return new SessionObservationView.TokenTotals(text(sum(values, TrajectoryRepository.ModelUsageFact::promptTokens)),
                text(sum(values, TrajectoryRepository.ModelUsageFact::completionTokens)), text(sum(values, TrajectoryRepository.ModelUsageFact::cachedInputTokens)),
                text(sum(values, TrajectoryRepository.ModelUsageFact::cacheWriteInputTokens)), text(sum(values, TrajectoryRepository.ModelUsageFact::reasoningOutputTokens)));
    }
    private static BigInteger sum(List<TrajectoryRepository.ModelUsageFact> values, Function<TrajectoryRepository.ModelUsageFact, Long> field) {
        BigInteger result = null;
        for (var value : values) { Long amount = field.apply(value); if (amount != null) result = (result == null ? BigInteger.ZERO : result).add(BigInteger.valueOf(amount)); }
        return result;
    }
    private static BigInteger integer(String value) { return value == null ? null : new BigInteger(value); }
    private static String text(BigInteger value) { return value == null ? null : value.toString(); }
    private static String normalized(String value) { return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT); }
    private static boolean matches(TrajectoryView.ObservationNode node, String query) {
        return query == null || (node.name() + "\n" + node.kind() + "\n" + Objects.toString(node.summary(), "") + "\n" + node.status()).toLowerCase(Locale.ROOT).contains(query);
    }
    private String encode(Object value) {
        try { return crypto.encrypt(json.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("trajectory_token_encoding_failed", failure); }
    }
    private <T> T decode(String value, Class<T> type) {
        if (value == null || value.isBlank()) throw bad("轨迹引用缺失");
        try { return json.readValue(crypto.decrypt(value), type); }
        catch (Exception invalid) { throw bad("轨迹引用已失效或格式无效，请刷新后重试"); }
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }

    record Snapshot(String purpose, String scope, String actor, TrajectoryQuery.Resource resource,
                            List<TrajectoryRepository.RunFact> runs, List<TrajectoryRepository.Edge> edges, List<TrajectoryRepository.Edge> restricted,
                            List<TrajectoryRepository.AttemptFact> attempts, List<TrajectoryRepository.AuxiliaryAttemptFact> auxiliary,
                            Map<String, ModelCallRecordStatus> auxiliaryContent,
                            List<BillingTraceFacts.EffectiveCost> costs, Instant readAt) {
        Snapshot {
            runs = List.copyOf(runs); edges = List.copyOf(edges); restricted = List.copyOf(restricted);
            attempts = List.copyOf(attempts); auxiliary = List.copyOf(auxiliary); auxiliaryContent = Map.copyOf(auxiliaryContent); costs = List.copyOf(costs);
        }
    }
    private record SnapshotReference(String purpose, String scope, String actor, TrajectoryQuery.Resource resource, String id) { }
    private record Cursor(String purpose, String snapshot, String query, int offset) { }
    private record ContentKey(String purpose, String scope, String actor, TrajectoryQuery.Resource resource,
                              String source, String sessionId, String runId, String actionId, String attemptId, String invocationId,
                              String requestRef, String resultRef, String side) { }
}
