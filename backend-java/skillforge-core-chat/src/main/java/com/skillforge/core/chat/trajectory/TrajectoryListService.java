package com.skillforge.core.chat.trajectory;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Comparator;
import java.util.PriorityQueue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 基于同一公开投影筛选后计数和分页，不为检索解封私有模型材料。 */
@Service
public class TrajectoryListService {
    private static final int CANDIDATE_BATCH_SIZE = 100;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final Set<Integer> PAGE_SIZES = Set.of(20, 50, 100);
    private final TrajectoryListMapper mapper;
    private final TrajectoryProjectionService projection;
    private final SessionObservationAuthorizer authorizer;
    private final PlatformObservationAccess reads;

    public TrajectoryListService(TrajectoryListMapper mapper, TrajectoryProjectionService projection,
                                 SessionObservationAuthorizer authorizer, PlatformObservationAccess reads) {
        this.mapper = mapper;
        this.projection = projection;
        this.authorizer = authorizer;
        this.reads = reads;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TrajectoryView.ListPage list(SessionObservationScope scope, TrajectoryListQuery query) {
        validate(query);
        List<String> visible = authorizer.visibleSessionIds(scope);
        if (query.tenantId() != null && scope.kind() == SessionObservationScope.Kind.TENANT
                && !query.tenantId().equals(scope.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "目标机构不在当前观测范围内");
        Instant readAt = mapper.readAt();
        Instant through = query.startedTo() == null ? readAt : query.startedTo();
        Instant from = query.startedFrom() == null ? through.minus(DEFAULT_WINDOW) : query.startedFrom();
        if (!from.isBefore(through)) throw badRequest("started_to 必须晚于 started_from");
        if (visible != null && visible.isEmpty())
            return new TrajectoryView.ListPage(List.of(), query.page(), query.pageSize(), 0, readAt);

        long requestedOffset = ((long) query.page() - 1) * query.pageSize();
        long matched = 0;
        List<TrajectoryView.Summary> items = new ArrayList<>();
        var order = Comparator.comparingLong(TrajectoryListMapper.Candidate::startedSecond).reversed()
                .thenComparing(Comparator.comparingInt(TrajectoryListMapper.Candidate::startedNano).reversed())
                .thenComparingLong(TrajectoryListMapper.Candidate::tenantId)
                .thenComparing(TrajectoryListMapper.Candidate::sessionId).thenComparing(TrajectoryListMapper.Candidate::rootInputId);
        var candidates = new PriorityQueue<CandidateCursor>(Comparator.comparing(CandidateCursor::current, order));
        Long target = scope.kind() == SessionObservationScope.Kind.TENANT ? scope.tenantId() : query.tenantId();
        if (target != null) addCursor(candidates, target, scope, query, visible, from, through);
        else {
            long after = 0;
            for (;;) {
                var targets = reads.targets(null, after, CANDIDATE_BATCH_SIZE);
                for (long tenant : targets) addCursor(candidates, tenant, scope, query, visible, from, through);
                if (targets.size() < CANDIDATE_BATCH_SIZE) break;
                after = targets.getLast();
            }
        }
        // 每个目标只持有当前有界候选页，通过有序流归并保留跨机构全局顺序。
        while (!candidates.isEmpty()) {
            var cursor = candidates.remove();
            var candidate = cursor.current();
            var summary = projection.summary(scope, new TrajectoryQuery.Resource(candidate.tenantId(),
                    candidate.sessionId(), candidate.rootInputId()));
            if (matches(summary, query)) {
                if (matched >= requestedOffset && items.size() < query.pageSize()) items.add(summary);
                matched = Math.incrementExact(matched);
            }
            if (cursor.advance()) candidates.add(cursor);
        }
        return new TrajectoryView.ListPage(items, query.page(), query.pageSize(), matched, readAt);
    }

    private void addCursor(PriorityQueue<CandidateCursor> heap, long target, SessionObservationScope scope,
            TrajectoryListQuery query, List<String> visible, Instant from, Instant through) {
        var cursor = new CandidateCursor(target, scope.tenantId(), query, visible, from, through);
        if (!cursor.page.isEmpty()) heap.add(cursor);
    }

    private final class CandidateCursor {
        private final long target;
        private final Long scopeTenant;
        private final TrajectoryListQuery query;
        private final List<String> visible;
        private final Instant from;
        private final Instant through;
        private List<TrajectoryListMapper.Candidate> page;
        private long offset;
        private int index;
        private CandidateCursor(long target, Long scopeTenant, TrajectoryListQuery query, List<String> visible,
                Instant from, Instant through) {
            this.target=target;this.scopeTenant=scopeTenant;this.query=query;this.visible=visible;this.from=from;this.through=through;
            fetch();
        }
        private TrajectoryListMapper.Candidate current() { return page.get(index); }
        private void fetch() {
            page=reads.read(target, () -> mapper.candidates(new TrajectoryListMapper.Candidates(scopeTenant,target,
                    normalized(query.sessionId()),query.agentId(),visible,from.getEpochSecond(),from.getNano(),
                    through.getEpochSecond(),through.getNano(),offset,CANDIDATE_BATCH_SIZE)));
            index=0;
        }
        private boolean advance() {
            if (++index < page.size()) return true;
            if (page.size() < CANDIDATE_BATCH_SIZE) return false;
            offset=Math.addExact(offset,CANDIDATE_BATCH_SIZE);fetch();return !page.isEmpty();
        }
    }

    /** 所有文本条件都在当前授权公开投影上执行字面匹配，百分号和下划线没有通配含义。 */
    static boolean matches(TrajectoryView.Summary summary, TrajectoryListQuery query) {
        String trace = normalized(query.traceId());
        String model = normalized(query.model());
        String tool = normalized(query.tool());
        String status = normalized(query.status());
        if (trace != null && !summary.traceIds().contains(trace)) return false;
        if (model != null && !summary.models().contains(model)) return false;
        if (tool != null && !summary.tools().contains(tool)) return false;
        if (status != null && !status.toUpperCase(Locale.ROOT).equals(summary.status())) return false;
        if (!contains(summary.input(), normalized(query.inputKeyword()))
                || !contains(summary.output(), normalized(query.outputKeyword()))) return false;
        if (query.durationMinMs() != null || query.durationMaxMs() != null) {
            if (summary.finishedAt() == null || summary.durationMs() == null) return false;
            BigInteger duration = new BigInteger(summary.durationMs());
            if (query.durationMinMs() != null && duration.compareTo(BigInteger.valueOf(query.durationMinMs())) < 0
                    || query.durationMaxMs() != null && duration.compareTo(BigInteger.valueOf(query.durationMaxMs())) > 0)
                return false;
        }
        return true;
    }

    private static boolean contains(String value, String keyword) {
        return keyword == null || value != null && value.contains(keyword);
    }

    private static void validate(TrajectoryListQuery query) {
        if (query == null || query.page() <= 0 || !PAGE_SIZES.contains(query.pageSize()))
            throw badRequest("page 必须为正数，page_size 仅支持 20、50、100");
        if (query.tenantId() != null && query.tenantId() <= 0 || query.agentId() != null && query.agentId() <= 0)
            throw badRequest("机构和智能体 ID 必须为正数");
        if (query.durationMinMs() != null && query.durationMinMs() < 0
                || query.durationMaxMs() != null && query.durationMaxMs() < 0
                || query.durationMinMs() != null && query.durationMaxMs() != null
                    && query.durationMinMs() > query.durationMaxMs())
            throw badRequest("耗时范围必须非负，且最大值不小于最小值");
    }

    private static String normalized(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
