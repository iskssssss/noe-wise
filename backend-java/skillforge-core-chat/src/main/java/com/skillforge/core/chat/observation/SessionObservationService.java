package com.skillforge.core.chat.observation;

import java.util.Locale;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.integration.publicread.RuntimeCompletedAnswerReader;
import com.skillforge.core.chat.observation.SessionObservationPublicFacts.Facts;
import com.skillforge.core.chat.observation.SessionObservationPublicFacts.ReadScope;
import com.skillforge.core.chat.observation.SessionObservationPublicFacts.SessionKey;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.chat.session.SessionTraceService;
import com.skillforge.core.chat.observation.SessionObservationRepository.FrozenKeyRow;
import com.skillforge.core.chat.observation.SessionObservationRepository.MessageRow;
import com.skillforge.core.chat.observation.SessionObservationRepository.RoundEventRow;
import com.skillforge.core.chat.observation.SessionObservationRepository.RoundRow;
import com.skillforge.core.chat.observation.SessionObservationRepository.RunRow;
import com.skillforge.core.chat.observation.SessionObservationRepository.SessionRow;
import com.skillforge.core.chat.observation.SessionObservationRepository.TraceRow;
import com.skillforge.core.chat.observation.SessionObservationRepository.UsageRow;
import com.skillforge.core.chat.observation.SessionObservationView.CacheStats;
import com.skillforge.core.chat.observation.SessionObservationView.Content;
import com.skillforge.core.chat.observation.SessionObservationView.Coverage;
import com.skillforge.core.chat.observation.SessionObservationView.Loop;
import com.skillforge.core.chat.observation.SessionObservationView.LoopGroup;
import com.skillforge.core.chat.observation.SessionObservationView.Message;
import com.skillforge.core.chat.observation.SessionObservationView.MessagePage;
import com.skillforge.core.chat.observation.SessionObservationView.Round;
import com.skillforge.core.chat.observation.SessionObservationView.RoundPage;
import com.skillforge.core.chat.observation.SessionObservationView.Run;
import com.skillforge.core.chat.observation.SessionObservationView.SessionDetail;
import com.skillforge.core.chat.observation.SessionObservationView.SessionPage;
import com.skillforge.core.chat.observation.SessionObservationView.SessionSummary;
import com.skillforge.core.chat.observation.SessionObservationView.TokenTotals;
import com.skillforge.core.chat.observation.SessionObservationView.TraceEvent;
import com.skillforge.core.chat.observation.SessionObservationView.TracePage;
import com.skillforge.core.chat.observation.SessionObservationView.TraceUsageSummary;
import com.skillforge.core.chat.observation.SessionObservationView.Usage;
import com.skillforge.core.chat.observation.SessionObservationView.UsageAttempt;
import com.skillforge.core.chat.observation.SessionObservationView.UsageBreakdown;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 从宿主、Runtime 与计量权威事实构建平台会话只读投影。 */
@Service
public class SessionObservationService {
    private static final Logger LOG = LoggerFactory.getLogger(SessionObservationService.class);
    private static final int PUBLIC_BATCH_SIZE = 100;
    private static final Set<Integer> SESSION_PAGE_SIZES = Set.of(20, 50, 100);
    private static final int MAX_CHILD_PAGE_SIZE = 200;
    private static final String PROVIDER_REPORTED = "PROVIDER_REPORTED";
    private static final String UNASSOCIATED = "UNASSOCIATED";
    private static final String ASSOCIATED = "ASSOCIATED";
    private static final String ROUND_CURSOR_KIND = "round-start";

    private final SessionObservationRepository repository;
    private final SessionTraceService traces;
    private final ObservationReadAudit audit;
    private final PlatformObservationAccess reads;
    private final ObjectMapper objectMapper;
    private final RuntimeCompletedAnswerReader publicAnswers;
    private final PublicOutputGuard outputGuard;

    public SessionObservationService(
            SessionObservationRepository repository,
            SessionTraceService traces,
            ObservationReadAudit audit,
            ObjectMapper objectMapper,
            RuntimeCompletedAnswerReader publicAnswers,
            PublicOutputGuard outputGuard, PlatformObservationAccess reads) {
        this.repository = repository;
        this.traces = traces;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.publicAnswers = publicAnswers;
        this.outputGuard = outputGuard;
        this.reads = reads;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionPage sessions(
            SessionObservationScope scope, SessionObservationQuery.Sessions query) {
        requireScope(scope);
        if (query.page() <= 0 || !SESSION_PAGE_SIZES.contains(query.pageSize())) {
            throw badRequest("page 必须为正数，page_size 仅支持 20、50、100");
        }
        if (query.startedFrom() != null && query.startedTo() != null
                && !query.startedFrom().isBefore(query.startedTo())) {
            throw badRequest("started_to 必须晚于 started_from");
        }
        if (scope.kind() == SessionObservationScope.Kind.TENANT
                && query.tenantId() != null) {
            scope.requireTenant(query.tenantId());
        }
        var candidates = repository.list(scope, query).rows();
        String status = blankToNull(query.status());
        String requiredStatus = status == null ? null : status.toUpperCase(Locale.ROOT);
        List<SessionSummary> projected = new ArrayList<>();
        // 候选保持轻量；公开正文仅在固定小批中用于guard与计数，随后立即释放。
        for (int offset = 0; offset < candidates.size(); offset += PUBLIC_BATCH_SIZE) {
            var batch = candidates.subList(offset, Math.min(candidates.size(), offset + PUBLIC_BATCH_SIZE));
            var visible = visibleBatch(batch, batch.stream().map(row -> scope(row, List.of(), row.eventUpperBound(), row.messageUpperBound())).toList());
            for (var row : batch) {
                var item = summary(row, visible.getOrDefault(key(row), List.of()));
                if (requiredStatus == null || requiredStatus.equals(item.answerStatus()) || requiredStatus.equals(item.executionStatus()))
                    projected.add(item);
            }
        }
        long total = projected.size();
        long offset = ((long) query.page() - 1) * query.pageSize();
        List<SessionSummary> page = offset >= total ? List.of()
                : projected.subList((int) offset, (int) Math.min(total, offset + query.pageSize()));
        LOG.debug("平台会话列表公开归并完成 candidateCount={} matchedCount={} pageCount={}", candidates.size(), total, page.size());
        return new SessionPage(page, query.page(), query.pageSize(), total, repository.readAt());
    }

    /** 当前授权范围内可供筛选的会话、用户、智能体、团队与渠道。 */
    @Transactional(readOnly = true)
    public List<FilterOption> filterOptions(SessionObservationScope scope, String q) {
        requireScope(scope);
        return repository.filterOptions(scope, blankToNull(q));
    }

    public record FilterOption(String kind, String value, String label, String description) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionDetail detail(SessionObservationScope scope, SessionObservationQuery.Resource query) {
        SessionRow row = requireSession(scope, query.tenantId(), query.sessionId());
        Usage usage = usage(scope, new SessionObservationQuery.Usage(
                query.tenantId(), query.sessionId(), null, null, MAX_CHILD_PAGE_SIZE));
        var visible = visibleBatch(List.of(row), List.of(scope(row, List.of(), row.eventUpperBound(), row.messageUpperBound())))
                .getOrDefault(key(row), List.of());
        return new SessionDetail(summary(row, visible), row.activeExecutionId(), row.expiredAt(),
                visible.stream().map(VisibleMessage::createdAt).min(Comparator.naturalOrder()).orElse(null),
                visible.stream().map(VisibleMessage::createdAt).max(Comparator.naturalOrder()).orElse(null), usage.totals());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoundPage rounds(SessionObservationScope scope, SessionObservationQuery.Rounds query) {
        SessionRow session = requireSession(scope, query.tenantId(), query.sessionId());
        int pageSize = childPageSize(query.pageSize());
        Cursor roundCursor = decodeCursor(
                query.cursor(), ROUND_CURSOR_KIND, query.tenantId(), query.sessionId(), query.rootInputId());
        Long before = roundCursor.position() == 0
                ? null : roundCursor.position();
        boolean firstPage = query.cursor() == null || query.cursor().isBlank();
        long upperBound = firstPage ? session.eventUpperBound() : roundCursor.upperBound();
        long messageUpperBound = firstPage ? session.messageUpperBound() : roundCursor.secondaryUpperBound();
        List<RoundRow> fetched = repository.rounds(
                query.tenantId(), query.sessionId(), query.rootInputId(), before, upperBound, pageSize + 1);
        if (query.rootInputId() != null
                && !repository.rootExists(query.tenantId(), query.sessionId(), query.rootInputId())) {
            throw missing("根输入不存在或不属于该会话");
        }
        boolean hasMore = fetched.size() > pageSize;
        List<RoundRow> rows = hasMore ? fetched.subList(0, pageSize) : fetched;
        List<String> roots = rows.stream().map(RoundRow::rootInputId).toList();
        List<RunRow> runs = repository.runs(query.tenantId(), query.sessionId(), roots);
        LoopIdentityIndex identities = loopIdentities(
                repository.frozenKeys(query.tenantId(), runs.stream().map(RunRow::runId).toList()));
        List<RoundEventRow> events = repository.roundEvents(query.tenantId(), query.sessionId(), roots, upperBound);
        Map<RoundRunKey, List<LoopAggregate>> loops = aggregateRoundLoops(events, identities);
        var visible = roots.isEmpty() ? List.<VisibleMessage>of() : visibleBatch(List.of(session),
                List.of(scope(session, roots, upperBound, messageUpperBound))).getOrDefault(key(session), List.of());
        Map<String, List<VisibleMessage>> answers = visible.stream().filter(message -> message.rootInputId() != null)
                .collect(java.util.stream.Collectors.groupingBy(VisibleMessage::rootInputId));
        Map<String, List<RunRow>> runsByRoot = groupRuns(runs);
        List<Round> projected = rows.stream().map(row -> round(
                row, runsByRoot.getOrDefault(row.rootInputId(), List.of()), loops, answers)).toList();
        String next = hasMore && !rows.isEmpty()
                ? encodeCursor(new Cursor(ROUND_CURSOR_KIND, query.tenantId(), query.sessionId(), blankToNull(query.rootInputId()),
                        rows.get(rows.size() - 1).firstSequence(), upperBound, messageUpperBound)) : null;
        return new RoundPage(Long.toString(query.tenantId()), query.sessionId(), projected,
                next, repository.readAt());
    }

    /** 同一公开归并用于正文与计数；复合游标冻结两个来源，即使初始上界为零。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public MessagePage messages(SessionObservationScope scope, SessionObservationQuery.Messages query) {
        SessionRow session = requireSession(scope, query.tenantId(), query.sessionId());
        String rootInputId = blankToNull(query.rootInputId());
        if (rootInputId != null && !repository.rootExists(query.tenantId(), query.sessionId(), rootInputId))
            throw missing("根输入不存在或不属于该会话");
        int pageSize = childPageSize(query.pageSize());
        MessageCursor cursor = decodeMessageCursor(query.cursor(), query.tenantId(), query.sessionId(), rootInputId);
        long eventUpper = cursor == null ? session.eventUpperBound() : cursor.eventUpperBound();
        long messageUpper = cursor == null ? session.messageUpperBound() : cursor.messageUpperBound();
        var read = scope(session, rootInputId == null ? List.of() : List.of(rootInputId), eventUpper, messageUpper);
        var visible = visibleBatch(List.of(session), List.of(read)).getOrDefault(key(session), List.of());
        var order = Comparator.comparing(VisibleMessage::createdAt).thenComparingInt(VisibleMessage::source)
                .thenComparing(VisibleMessage::messageId);
        var fetched = visible.stream().filter(message -> cursor == null || before(message, cursor))
                .sorted(order.reversed()).limit((long) pageSize + 1).toList();
        boolean hasMore = fetched.size() > pageSize;
        var rows = hasMore ? fetched.subList(0, pageSize) : fetched;
        List<Message> items = rows.stream().map(message -> new Message(message.messageId(), message.role(), message.content(),
                message.rootInputId(), message.runId(), message.phase(), message.state(), Long.toString(message.messageVersion()),
                message.createdAt())).toList();
        String next = null;
        if (hasMore && !rows.isEmpty()) {
            var last = rows.getLast();
            next = encodeCursor(new MessageCursor("message", 1, query.tenantId(), query.sessionId(), rootInputId,
                    eventUpper, messageUpper, last.createdAt().getEpochSecond(), last.createdAt().getNano(), last.source(), last.messageId()));
        }
        audit.record(Long.toString(query.tenantId()), scope.actorId(),
                "platform.session.messages.read", "session", query.sessionId(),
                "平台管理员读取会话消息正文", Map.of("root_input_id", value(rootInputId), "message_count", items.size()));
        LOG.debug("平台会话消息公开归并完成 visibleCount={} pageCount={} hasMore={}", visible.size(), items.size(), hasMore);
        return new MessagePage(Long.toString(query.tenantId()), query.sessionId(), rootInputId, items, next, repository.readAt());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TracePage trace(SessionObservationScope scope, SessionObservationQuery.Trace query) {
        requireSession(scope, query.tenantId(), query.sessionId());
        requireText(query.rootInputId(), "root_input_id");
        int pageSize = childPageSize(query.pageSize());
        Cursor cursor = decodeCursor(
                query.cursor(), "trace", query.tenantId(), query.sessionId(), query.rootInputId());
        long upperBound = cursor.upperBound() > 0 ? cursor.upperBound()
                : repository.traceUpperBound(query.tenantId(), query.sessionId(), query.rootInputId());
        if (upperBound == 0) throw missing("根输入不存在或没有轨迹事实");
        long after = cursor.position();
        if (query.nodeId() != null && !query.nodeId().isBlank() && query.cursor() == null) {
            long nodeSequence = repository.traceNodeSequence(query.tenantId(), query.sessionId(),
                    query.rootInputId(), query.nodeId(), upperBound)
                    .orElseThrow(() -> missing("轨迹节点不存在或不属于该根输入"));
            after = Math.max(0, nodeSequence - Math.max(1, pageSize / 2) - 1L);
        }
        List<TraceRow> fetched = repository.trace(query.tenantId(), query.sessionId(),
                query.rootInputId(), after, upperBound, pageSize + 1);
        boolean hasMore = fetched.size() > pageSize;
        List<TraceRow> rows = hasMore ? fetched.subList(0, pageSize) : fetched;
        List<RoundEventRow> rootEvents = repository.roundEvents(
                query.tenantId(), query.sessionId(), List.of(query.rootInputId()), upperBound);
        LoopIdentityIndex identities = loopIdentities(repository.frozenKeys(
                query.tenantId(), rootEvents.stream().map(RoundEventRow::runId)
                        .filter(Objects::nonNull).distinct().toList()));
        Long attemptUpperBound = query.cursor() == null || query.cursor().isBlank()
                ? null : cursor.secondaryUpperBound();
        List<UsageRow> usageRows = deduplicate(repository.usage(
                query.tenantId(), query.sessionId(), query.rootInputId(), attemptUpperBound));
        long frozenAttemptUpperBound = attemptUpperBound == null
                ? usageRows.stream().mapToLong(UsageRow::attemptSequence).max().orElse(0)
                : attemptUpperBound;
        Map<String, List<UsageRow>> usageByNode = usageRows.stream()
                .filter(usage -> usage.traceNodeId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        UsageRow::traceNodeId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<TraceEvent> items = rows.stream().map(row -> traceEvent(
                row, identities, traceUsageSummaryOrNull(
                        usageByNode.getOrDefault(row.eventId(), List.of())))).toList();
        List<LoopGroup> groups = aggregateRoundLoops(rootEvents, identities).values().stream()
                .flatMap(List::stream)
                .map(loop -> loop.view(traceUsageSummary(usageForNodes(usageByNode, loop.nodeIds))))
                .toList();
        Set<String> rootNodeIds = rootEvents.stream().map(RoundEventRow::eventId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        TraceUsageSummary unassociatedUsage = traceUsageSummary(usageRows.stream()
                .filter(usage -> usage.traceNodeId() == null || !rootNodeIds.contains(usage.traceNodeId()))
                .toList());
        String next = hasMore && !rows.isEmpty()
                ? encodeCursor(new Cursor("trace", query.tenantId(), query.sessionId(),
                        query.rootInputId(), rows.get(rows.size() - 1).sequence(), upperBound,
                        frozenAttemptUpperBound)) : null;
        return new TracePage(Long.toString(query.tenantId()), query.sessionId(), query.rootInputId(),
                Long.toString(upperBound), next, items, groups, unassociatedUsage, repository.readAt());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Content content(SessionObservationScope scope, SessionObservationQuery.Content query) {
        requireSession(scope, query.tenantId(), query.sessionId());
        requireText(query.rootInputId(), "root_input_id");
        if (!repository.contentBelongsToRoot(query.tenantId(), query.sessionId(),
                query.rootInputId(), query.contentRef())) {
            throw missing("内容引用不存在或不属于该根输入");
        }
        var content = reads.read(query.tenantId(), () -> traces.content(Long.toString(query.tenantId()),
                query.sessionId(), query.contentRef())).orElseThrow(() -> missing("内容引用不可用"));
        audit.record(Long.toString(query.tenantId()), scope.actorId(),
                "platform.session.trace.content.read", "session", query.sessionId(),
                "平台管理员读取会话轨迹正文", Map.of(
                        "root_input_id", query.rootInputId(), "content_ref", query.contentRef(),
                        "availability", content.availability()));
        return content;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Usage usage(SessionObservationScope scope, SessionObservationQuery.Usage query) {
        requireSession(scope, query.tenantId(), query.sessionId());
        int pageSize = childPageSize(query.pageSize());
        Cursor cursor = decodeCursor(
                query.cursor(), "usage", query.tenantId(), query.sessionId(), query.rootInputId());
        Long requestedUpperBound = cursor.upperBound() == 0 ? null : cursor.upperBound();
        List<UsageRow> rows = deduplicate(repository.usage(
                query.tenantId(), query.sessionId(), query.rootInputId(), requestedUpperBound));
        long upperBound = requestedUpperBound == null
                ? rows.stream().mapToLong(UsageRow::attemptSequence).max().orElse(0)
                : requestedUpperBound;
        List<UsageRow> pageCandidates = rows.stream()
                .filter(row -> row.attemptSequence() > cursor.position())
                .limit((long) pageSize + 1).toList();
        boolean hasMore = pageCandidates.size() > pageSize;
        List<UsageRow> pageRows = hasMore ? pageCandidates.subList(0, pageSize) : pageCandidates;
        List<UsageAttempt> attempts = pageRows.stream().map(this::usageAttempt).toList();
        Coverage coverage = coverage(rows);
        List<UsageRow> providerRows = rows.stream().filter(this::providerReported).toList();
        TokenTotals totals = totals(providerRows);
        CacheStats cache = cache(rows);
        Map<BreakdownKey, List<UsageRow>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(new BreakdownKey(
                value(row.provider()), value(row.model()), row.callPoint()), ignored -> new ArrayList<>()).add(row));
        List<UsageBreakdown> breakdowns = grouped.entrySet().stream().map(entry ->
                new UsageBreakdown(entry.getKey().provider(), entry.getKey().model(),
                        entry.getKey().callPoint(), entry.getValue().size(),
                        totals(entry.getValue().stream().filter(this::providerReported).toList()))).toList();
        String next = hasMore && !pageRows.isEmpty()
                ? encodeCursor(new Cursor("usage", query.tenantId(), query.sessionId(),
                        blankToNull(query.rootInputId()),
                        pageRows.get(pageRows.size() - 1).attemptSequence(), upperBound, 0)) : null;
        return new Usage(Long.toString(query.tenantId()), query.sessionId(),
                blankToNull(query.rootInputId()), repository.readAt(), coverage, totals, cache,
                breakdowns, attempts, next);
    }

    private SessionRow requireSession(SessionObservationScope scope, long tenantId, String sessionId) {
        requireScope(scope);
        scope.requireTenant(tenantId);
        requireText(sessionId, "session_id");
        return repository.find(scope, tenantId, sessionId)
                .orElseThrow(() -> missing("会话不存在或不属于指定机构"));
    }

    private SessionSummary summary(SessionRow row, List<VisibleMessage> visible) {
        return new SessionSummary(Long.toString(row.tenantId()), row.tenantName(), row.sessionId(),
                Long.toString(row.userId()), row.userName(), row.targetType(),
                text(row.agentId()), row.agentName(), row.teamId(), row.teamName(),
                text(row.channelId()), row.channelType(), row.channelName(),
                answerStatus(row.executionStatus(), visible.stream().filter(message -> Objects.equals(row.latestRootInputId(), message.rootInputId())
                        && row.latestRootInputId() != null).toList()),
                row.executionStatus(), row.summary(), visible.size(), row.rootInputCount(),
                row.createdAt(), row.updatedAt(), row.lastActivityAt());
    }

    /** 两类来源按消息身份合并；Runtime 原事实优先，安全检查与消息数量共享同一结果。 */
    private Map<SessionKey, List<VisibleMessage>> visibleBatch(List<SessionRow> sessions, List<ReadScope> scopes) {
        LOG.debug("平台公开消息批量归并进入 sessionCount={}", sessions.size());
        try {
            var facts = repository.publicFacts(scopes);
            var memory = repository.messages(scopes);
            Map<SessionKey, List<VisibleMessage>> result = new LinkedHashMap<>();
            for (var session : sessions) {
                var key = key(session);
                var owner = owner(session);
                var guard = outputGuard.capture(owner);
                var source = facts.getOrDefault(key, Facts.empty());
                if (source.owner().filter(actual -> !owner.equals(actual)).isPresent())
                    throw new IllegalArgumentException("public_answer_owner_mismatch");
                List<RuntimeCompletedAnswerReader.Answer> answers = publicAnswers.read(source.events(), guard);
                var runtimeIds = answers.stream().map(RuntimeCompletedAnswerReader.Answer::messageId)
                        .collect(java.util.stream.Collectors.toSet());
                Map<String, VisibleMessage> messages = new LinkedHashMap<>();
                for (var message : memory.getOrDefault(key, List.of())) {
                    if (!"user".equals(message.role()) && !"assistant".equals(message.role())) continue;
                    // 团队采纳事实仍由Runtime拥有，主动普通消息不需要伪造采纳引用。
                    if (session.teamId() != null && message.teamAnswerRef() != null && !runtimeIds.contains(message.messageId())) continue;
                    var safe = guard.protect(memoryMessage(message));
                    messages.put(safe.messageId(), new VisibleMessage(safe.messageId(), safe.role(), safe.content(),
                            metadataText(safe, "root_input_id", "rootInputId"), metadataText(safe, "run_id", "runId"),
                            safe.phase(), safe.state(), safe.messageVersion(), safe.createdAt(), 0));
                }
                for (var answer : answers) {
                    messages.put(answer.messageId(), new VisibleMessage(answer.messageId(), "assistant", answer.content(),
                            answer.rootInputId(), answer.runId(), answer.phase(), answer.state(), answer.messageVersion(), answer.createdAt(), 1));
                }
                result.put(key, List.copyOf(messages.values()));
            }
            return Map.copyOf(result);
        } catch (IllegalArgumentException invalid) {
            // 稳定领域错误不输出正文或来源字段；原异常只保留在服务器原因链中。
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "公开回答事实校验失败", invalid);
        } finally { LOG.debug("平台公开消息批量归并退出 sessionCount={}", sessions.size()); }
    }

    /** 与机构消息历史使用同一typed原消息和guard，附加载荷也不能绕过公开检查。 */
    private ChatMessageRecord memoryMessage(MessageRow row) {
        try {
            Map<String, Object> metadata = row.metadataCanonicalJson() == null ? null
                    : objectMapper.readValue(row.metadataCanonicalJson(), new TypeReference<Map<String, Object>>() { });
            List<ChatReferenceView> references = row.referencesCanonicalJson() == null ? List.of()
                    : objectMapper.readValue(row.referencesCanonicalJson(), new TypeReference<List<ChatReferenceView>>() { });
            return new ChatMessageRecord(row.messageId(), row.role(), row.content(), row.createdAt(), metadata, row.messageVersion(), references);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("public_message_source_invalid", invalid);
        }
    }

    private static String metadataText(ChatMessageRecord message, String primary, String alternate) {
        if (message.metadata() == null) return null;
        Object value = message.metadata().get(primary);
        if (value == null) value = message.metadata().get(alternate);
        return value instanceof String text ? text : null;
    }

    /** 成功回答必须是同根公开final的完成态；真实未知执行结果和未知计量保持原语义。 */
    private static String answerStatus(String execution, List<VisibleMessage> messages) {
        if ("RUNNING".equals(execution) || "WAITING".equals(execution)) return "IN_PROGRESS";
        // 无phase/state的合法主动助手消息是宿主已提交正文；空正文和Run完成都不能证明回答已交付。
        if (messages.stream().anyMatch(message -> "assistant".equals(message.role())
                && message.content() != null && !message.content().isBlank()
                && (message.phase() == null || "final".equals(message.phase()))
                && (message.state() == null || "completed".equals(message.state())))) return "COMPLETED";
        if ("FAILED".equals(execution) || "OUTCOME_UNKNOWN".equals(execution)
                || messages.stream().anyMatch(message -> "assistant".equals(message.role()) && "failed".equals(message.state()))) return "FAILED";
        return "NONE";
    }

    private static SessionKey key(SessionRow session) { return new SessionKey(session.tenantId(), session.sessionId()); }

    /** 只使用当前已授权宿主会话归属，不从相邻事件、正文或时间推测owner。 */
    private static SessionOwner owner(SessionRow session) {
        var target = session.teamId() == null
                ? new SessionTarget(SessionTarget.Kind.AGENT, Long.toString(Objects.requireNonNull(session.agentId())))
                : new SessionTarget(SessionTarget.Kind.TEAM, session.teamId());
        return SessionOwner.user(new SessionRef(new TenantId(Long.toString(session.tenantId())), session.sessionId()),
                target, new EndUserId(session.userId()));
    }

    private static ReadScope scope(SessionRow session, List<String> roots, long eventUpper, long messageUpper) {
        return new ReadScope(session.tenantId(), session.sessionId(), roots, eventUpper, messageUpper);
    }

    /** 游标不依赖最新消息内容或更新版本，只比较被冻结来源的首次创建位置。 */
    private static boolean before(VisibleMessage message, MessageCursor cursor) {
        int comparison = message.createdAt().compareTo(Instant.ofEpochSecond(cursor.beforeEpochSecond(), cursor.beforeNano()));
        if (comparison == 0) comparison = Integer.compare(message.source(), cursor.beforeSource());
        if (comparison == 0) comparison = message.messageId().compareTo(cursor.beforeMessageId());
        return comparison < 0;
    }

    private MessageCursor decodeMessageCursor(String value, long tenantId, String sessionId, String rootInputId) {
        if (value == null || value.isBlank()) return null;
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            if (!digest(payload).equals(parts[1])) throw new IllegalArgumentException();
            var cursor = objectMapper.readValue(payload, MessageCursor.class);
            if (!"message".equals(cursor.kind()) || cursor.version() != 1 || cursor.tenantId() != tenantId
                    || !sessionId.equals(cursor.sessionId()) || !Objects.equals(rootInputId, cursor.rootInputId())
                    || cursor.eventUpperBound() < 0 || cursor.messageUpperBound() < 0
                    || cursor.beforeNano() < 0 || cursor.beforeNano() > 999_999_999
                    || cursor.beforeSource() < 0 || cursor.beforeSource() > 1
                    || cursor.beforeMessageId() == null || cursor.beforeMessageId().isBlank()) throw new IllegalArgumentException();
            Instant.ofEpochSecond(cursor.beforeEpochSecond(), cursor.beforeNano());
            return cursor;
        } catch (Exception invalid) { throw badRequest("cursor 无效或不属于当前查询范围"); }
    }

    private Round round(
            RoundRow row,
            List<RunRow> runRows,
            Map<RoundRunKey, List<LoopAggregate>> loops,
            Map<String, List<VisibleMessage>> answers) {
        List<Run> runs = runRows.stream().map(run -> {
            List<LoopAggregate> aggregates = loops.getOrDefault(
                    new RoundRunKey(row.rootInputId(), run.runId()), List.of());
            List<Loop> loopViews = aggregates.stream().map(LoopAggregate::loop).toList();
            if (loopViews.isEmpty()) {
                loopViews = List.of(new Loop(null, null, UNASSOCIATED,
                        Long.toString(row.firstSequence()), Long.toString(row.lastSequence()),
                        run.startedAt(), run.updatedAt()));
            }
            return new Run(run.runId(), loopViews);
        }).toList();
        String execution = aggregateExecution(runRows);
        String answer = answerStatus(execution, answers.getOrDefault(row.rootInputId(), List.of()));
        return new Round(row.rootInputId(), Long.toString(row.roundNumber()), Long.toString(row.firstSequence()),
                Long.toString(row.lastSequence()), answer, execution, row.startedAt(), row.updatedAt(),
                runs.size(), runs);
    }

    private TraceEvent traceEvent(
            TraceRow row, LoopIdentityIndex identities, TraceUsageSummary usageSummary) {
        LoopIdentity identity = identities.resolve(row, objectMapper);
        return new TraceEvent(row.eventId(), nodeType(row), row.parentNodeId(),
                Long.toString(row.sequence()), row.eventId(), row.eventType(), row.runId(),
                identity == null ? null : identity.loopId(),
                identity == null ? null : Integer.toString(identity.iteration()),
                identity == null ? UNASSOCIATED : ASSOCIATED,
                row.executionId(), row.actionId(), row.traceId(), row.causedByEventId(),
                row.occurredAt(), "session-event:" + row.eventId(), "FULL",
                eventStatus(row.eventType()), "payload_schema_id=" + row.payloadSchemaId(), usageSummary);
    }

    private LoopIdentityIndex loopIdentities(List<FrozenKeyRow> rows) {
        Map<String, LoopIdentity> byAction = new LinkedHashMap<>();
        Map<String, LoopIdentity> byOperation = new LinkedHashMap<>();
        for (FrozenKeyRow row : rows) {
            if (row.loopId() == null || row.iteration() == null || row.iteration() <= 0) continue;
            LoopIdentity identity = new LoopIdentity(row.runId(), row.loopId(), row.iteration(),
                    row.operationKey(), row.actionId());
            if (identity.actionId() != null) byAction.putIfAbsent(identity.actionId(), identity);
            if (identity.operationKey() != null) byOperation.putIfAbsent(identity.operationKey(), identity);
        }
        return new LoopIdentityIndex(Map.copyOf(byAction), Map.copyOf(byOperation));
    }

    private Map<RoundRunKey, List<LoopAggregate>> aggregateRoundLoops(
            List<RoundEventRow> rows, LoopIdentityIndex identities) {
        Map<RoundRunKey, Map<LoopKey, LoopAggregate>> grouped = new LinkedHashMap<>();
        for (RoundEventRow row : rows) {
            if (row.runId() == null) continue;
            LoopIdentity identity = identities.resolve(row, objectMapper);
            LoopKey loopKey = identity == null
                    ? new LoopKey(row.runId(), null, null, UNASSOCIATED)
                    : new LoopKey(identity.runId(), identity.loopId(), identity.iteration(), ASSOCIATED);
            RoundRunKey runKey = new RoundRunKey(row.rootInputId(), row.runId());
            grouped.computeIfAbsent(runKey, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(loopKey, LoopAggregate::new)
                    .add(row.sequence(), row.eventId(), row.occurredAt());
        }
        Map<RoundRunKey, List<LoopAggregate>> result = new LinkedHashMap<>();
        grouped.forEach((key, loops) -> result.put(key, loops.values().stream()
                .sorted(Comparator.comparingLong(LoopAggregate::firstSequence)).toList()));
        return result;
    }

    private UsageAttempt usageAttempt(UsageRow row) {
        String status;
        if (!providerReported(row)) {
            status = "UNKNOWN";
        } else if (row.promptTokens() == null || row.completionTokens() == null) {
            status = "PARTIAL";
        } else if (row.promptTokens() == 0 && row.completionTokens() == 0) {
            status = "ZERO";
        } else {
            status = "REPORTED";
        }
        return new UsageAttempt(row.attemptId(), row.invocationId(), row.traceNodeId(), row.runId(),
                value(row.provider()), value(row.model()), row.callPoint(), status,
                row.usageSource(), totals(List.of(row)), row.ingestedAt(), row.diagnosticAvailable(), row.sourceSessionId(), row.sourceRootInputId());
    }

    private Coverage coverage(List<UsageRow> rows) {
        int reported = 0;
        int unknown = 0;
        int partial = 0;
        int zero = 0;
        for (UsageRow row : rows) {
            switch (usageAttempt(row).reportStatus()) {
                case "REPORTED" -> reported++;
                case "PARTIAL" -> partial++;
                case "ZERO" -> {
                    reported++;
                    zero++;
                }
                default -> unknown++;
            }
        }
        String status = rows.isEmpty() ? "NO_CALLS"
                : unknown == rows.size() ? "UNKNOWN"
                : unknown > 0 || partial > 0 ? "PARTIAL" : "COMPLETE";
        return new Coverage(status, rows.size(), reported, unknown, partial, zero);
    }

    private TraceUsageSummary traceUsageSummary(List<UsageRow> rows) {
        List<UsageRow> providerRows = rows.stream().filter(this::providerReported).toList();
        return new TraceUsageSummary(coverage(rows), totals(providerRows), cache(rows),
                rows.stream().map(UsageRow::attemptId).distinct().toList());
    }

    private TraceUsageSummary traceUsageSummaryOrNull(List<UsageRow> rows) {
        return rows.isEmpty() ? null : traceUsageSummary(rows);
    }

    private static List<UsageRow> usageForNodes(
            Map<String, List<UsageRow>> usageByNode, Set<String> nodeIds) {
        return nodeIds.stream().flatMap(node -> usageByNode.getOrDefault(node, List.of()).stream()).toList();
    }

    private List<UsageRow> deduplicate(List<UsageRow> rows) {
        Map<String, UsageRow> result = new LinkedHashMap<>();
        rows.forEach(row -> result.merge(row.attemptId(), row,
                (left, right) -> later(left.ingestedAt(), right.ingestedAt()) ? left : right));
        return List.copyOf(result.values());
    }

    private TokenTotals totals(List<UsageRow> rows) {
        return new TokenTotals(sum(rows, UsageRow::promptTokens), sum(rows, UsageRow::completionTokens),
                sum(rows, UsageRow::cachedInputTokens), sum(rows, UsageRow::cacheWriteInputTokens),
                sum(rows, UsageRow::reasoningOutputTokens));
    }

    private CacheStats cache(List<UsageRow> rows) {
        BigInteger input = BigInteger.ZERO;
        BigInteger cached = BigInteger.ZERO;
        int paired = 0;
        for (UsageRow row : rows) {
            if (!providerReported(row)) continue;
            if (row.promptTokens() == null || row.cachedInputTokens() == null) continue;
            paired++;
            input = input.add(BigInteger.valueOf(row.promptTokens()));
            cached = cached.add(BigInteger.valueOf(row.cachedInputTokens()));
        }
        if (rows.isEmpty()) return new CacheStats("NOT_APPLICABLE", 0, 0, null, null, null);
        if (paired == 0) {
            boolean knownZeroInput = rows.stream().allMatch(row -> providerReported(row)
                    && row.promptTokens() != null && row.promptTokens() == 0);
            return new CacheStats(knownZeroInput ? "NOT_APPLICABLE" : "UNKNOWN",
                    0, rows.size(), null, null, null);
        }
        String ratio = input.signum() == 0 ? null : new BigDecimal(cached)
                .divide(new BigDecimal(input), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return new CacheStats(input.signum() == 0 ? "NOT_APPLICABLE" : "AVAILABLE",
                paired, rows.size(), input.toString(), cached.toString(), ratio);
    }

    private String sum(List<UsageRow> rows, TokenField field) {
        BigInteger total = BigInteger.ZERO;
        boolean present = false;
        for (UsageRow row : rows) {
            Long value = field.value(row);
            if (value == null) continue;
            present = true;
            total = total.add(BigInteger.valueOf(value));
        }
        return present ? total.toString() : null;
    }

    private boolean providerReported(UsageRow row) {
        return PROVIDER_REPORTED.equals(row.usageSource());
    }

    private Cursor decodeCursor(
            String value, String kind, long tenantId, String sessionId, String rootInputId) {
        if (value == null || value.isBlank()) {
            return new Cursor(kind, tenantId, sessionId, blankToNull(rootInputId), 0, 0, 0);
        }
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            if (!digest(payload).equals(parts[1])) throw new IllegalArgumentException();
            Cursor cursor = objectMapper.readValue(payload, Cursor.class);
            if (!kind.equals(cursor.kind()) || tenantId != cursor.tenantId()
                    || !sessionId.equals(cursor.sessionId())
                    || !Objects.equals(blankToNull(rootInputId), cursor.rootInputId())
                    || cursor.position() < 0 || cursor.upperBound() < 0
                    || cursor.secondaryUpperBound() < 0) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (Exception failure) {
            throw badRequest("cursor 无效或不属于当前查询范围");
        }
    }

    private String encodeCursor(Object cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + digest(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("session observation cursor encode failed", failure);
        }
    }

    private static String digest(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Map<String, List<RunRow>> groupRuns(List<RunRow> rows) {
        Map<String, List<RunRow>> result = new LinkedHashMap<>();
        rows.forEach(row -> result.computeIfAbsent(row.rootInputId(), ignored -> new ArrayList<>()).add(row));
        return result;
    }

    private static String aggregateExecution(List<RunRow> runs) {
        List<String> statuses = runs.stream().map(run -> executionStatus(run.status())).toList();
        if (statuses.contains("RUNNING")) return "RUNNING";
        if (statuses.contains("WAITING")) return "WAITING";
        if (statuses.contains("FAILED")) return "FAILED";
        if (statuses.contains("OUTCOME_UNKNOWN")) return "OUTCOME_UNKNOWN";
        if (statuses.contains("CANCELLED")) return "CANCELLED";
        return statuses.isEmpty() ? "IDLE" : "COMPLETED";
    }

    private static String executionStatus(String runtimeStatus) {
        if (runtimeStatus == null) return "IDLE";
        return switch (runtimeStatus) {
            case "QUEUED", "RUNNING", "RESUMING" -> "RUNNING";
            case "SUSPENDED" -> "WAITING";
            case "FAILED", "TERMINATED" -> "FAILED";
            case "OUTCOME_UNKNOWN" -> "OUTCOME_UNKNOWN";
            case "CANCELLED" -> "CANCELLED";
            case "COMPLETED" -> "COMPLETED";
            default -> "OUTCOME_UNKNOWN";
        };
    }

    private static String nodeType(TraceRow row) {
        String call = value(row.actionCallKind());
        String event = value(row.eventType());
        if (Set.of("MODEL", "TOOL", "WORKFLOW", "KNOWLEDGE", "DELEGATION").contains(call)) return call;
        if (event.startsWith("execution.model-")) return "MODEL";
        if (event.startsWith("execution.tool-")) return "TOOL";
        if (event.equals("execution.user-message")) return "INPUT";
        if (event.startsWith("execution.")) return "RUNTIME";
        return "EVENT";
    }

    private static String eventStatus(String eventType) {
        int separator = eventType == null ? -1 : eventType.indexOf('.');
        return separator < 0 ? "RECORDED" : eventType.substring(separator + 1).toUpperCase(Locale.ROOT);
    }

    private static int childPageSize(int requested) {
        if (requested <= 0) return 50;
        if (requested > MAX_CHILD_PAGE_SIZE) throw badRequest("page_size 最大为 200");
        return requested;
    }

    private static void requireScope(SessionObservationScope scope) {
        if (scope == null) throw new ApiException(HttpStatus.FORBIDDEN, "缺少会话观测授权作用域");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw badRequest(field + " 不能为空");
    }

    private static String text(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static boolean later(Instant left, Instant right) {
        return left != null && (right == null || left.isAfter(right));
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException missing(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    private interface TokenField {
        Long value(UsageRow row);
    }

    private record Cursor(
            String kind, long tenantId, String sessionId, String rootInputId,
            long position, long upperBound, long secondaryUpperBound) {
    }

    private record MessageCursor(String kind, int version, long tenantId, String sessionId, String rootInputId,
            long eventUpperBound, long messageUpperBound, long beforeEpochSecond, int beforeNano,
            int beforeSource, String beforeMessageId) { }

    private record VisibleMessage(String messageId, String role, String content, String rootInputId,
            String runId, String phase, String state, long messageVersion, Instant createdAt, int source) { }

    private record BreakdownKey(String provider, String model, String callPoint) {
    }

    private record LoopKey(String runId, String loopId, Integer iteration, String associationStatus) {
    }

    private record LoopIdentity(
            String runId, String loopId, int iteration, String operationKey, String actionId) {
    }

    private record RoundRunKey(String rootInputId, String runId) {
    }

    private static final class LoopAggregate {
        private final LoopKey key;
        private long firstSequence = Long.MAX_VALUE;
        private long lastSequence;
        private Instant startedAt;
        private Instant updatedAt;
        private final Set<String> nodeIds = new LinkedHashSet<>();

        private LoopAggregate(LoopKey key) {
            this.key = key;
        }

        void add(long sequence, String eventId, Instant occurredAt) {
            firstSequence = Math.min(firstSequence, sequence);
            lastSequence = Math.max(lastSequence, sequence);
            if (startedAt == null || occurredAt.isBefore(startedAt)) startedAt = occurredAt;
            if (updatedAt == null || occurredAt.isAfter(updatedAt)) updatedAt = occurredAt;
            nodeIds.add(eventId);
        }

        long firstSequence() {
            return firstSequence;
        }

        Loop loop() {
            return new Loop(key.loopId(), key.iteration() == null ? null : key.iteration().toString(), key.associationStatus(),
                    Long.toString(firstSequence), Long.toString(lastSequence), startedAt, updatedAt);
        }

        Set<String> nodeIds() {
            return Set.copyOf(nodeIds);
        }

        LoopGroup view(TraceUsageSummary usageSummary) {
            return new LoopGroup(emptyToNull(key.runId()), key.loopId(),
                    key.iteration() == null ? null : key.iteration().toString(),
                    key.associationStatus(), Long.toString(firstSequence), Long.toString(lastSequence),
                    List.copyOf(nodeIds), usageSummary);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    private record LoopIdentityIndex(
            Map<String, LoopIdentity> byAction, Map<String, LoopIdentity> byOperation) {
        LoopIdentity resolve(TraceRow row, ObjectMapper objectMapper) {
            return resolve(row.runId(), row.actionId(),
                    row.payloadCanonicalJson(), objectMapper);
        }

        LoopIdentity resolve(RoundEventRow row, ObjectMapper objectMapper) {
            return resolve(row.runId(), row.actionId(),
                    row.payloadCanonicalJson(), objectMapper);
        }

        private LoopIdentity resolve(
                String runId, String actionId,
                String payloadCanonicalJson, ObjectMapper objectMapper) {
            LoopIdentity action = actionId == null ? null : byAction.get(actionId);
            if (sameRun(action, runId)) return action;
            String operationKey = operationKey(payloadCanonicalJson, objectMapper);
            LoopIdentity operation = operationKey == null ? null : byOperation.get(operationKey);
            if (sameRun(operation, runId)) return operation;
            return null;
        }

        private static boolean sameRun(LoopIdentity identity, String runId) {
            return identity != null && Objects.equals(identity.runId(), runId);
        }

        private static String operationKey(String payload, ObjectMapper objectMapper) {
            if (payload == null || payload.isBlank()) return null;
            try {
                JsonNode root = objectMapper.readTree(payload);
                JsonNode value = root.get("operationKey");
                return value != null && value.isTextual() ? value.textValue() : null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
