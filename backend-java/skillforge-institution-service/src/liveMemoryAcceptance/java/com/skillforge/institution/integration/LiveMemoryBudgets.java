package com.skillforge.institution.integration;

import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.core.billing.metering.MeterScope;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import javax.sql.DataSource;

/** 独立记忆批次的统一发送预算；聊天和系统提取共享根上限，未知用量保留完整预留。 */
final class LiveMemoryBudgets implements ExecutionBudgetPort {
    static final int MAX_ATTEMPTS = 1200;
    static final long MAX_TOKENS = 1_000_000;
    static final Duration MAX_DURATION = Duration.ofHours(2);
    private final DataSource source;
    private final ExecutionBudgetPort delegate;
    private final MemoryRecordingHttpClient recording;
    private final java.time.Clock clock;
    private final java.util.function.LongSupplier nanos;
    private final Map<AttemptKey, Attempt> attempts = new LinkedHashMap<>();
    private final java.util.Set<String> tenants = new java.util.HashSet<>();
    private Instant deadline;
    private long deadlineNanos;
    private String stopCode;
    private boolean closed;
    private long sentSequence;

    LiveMemoryBudgets(DataSource source, MemoryRecordingHttpClient recording) {
        this(source, recording, new JdbcExecutionBudgets(source), java.time.Clock.systemUTC(), System::nanoTime);
    }
    /** 离线负例使用同一发送判定，时钟与数据库正式端口通过构造器提供。 */
    LiveMemoryBudgets(DataSource source, MemoryRecordingHttpClient recording, ExecutionBudgetPort delegate,
            java.time.Clock clock, java.util.function.LongSupplier nanos) {
        this.source = source; this.recording = recording; this.delegate = delegate; this.clock = clock; this.nanos = nanos;
    }

    /** 全批次只初始化一次；加入新样本租户不能重置时间或累计用量。 */
    synchronized void begin() {
        if (deadline != null || closed) throw new IllegalStateException("LIVE_MEMORY_SCOPE_ALREADY_STARTED");
        deadline = clock.instant().plus(MAX_DURATION);
        deadlineNanos = nanos.getAsLong() + MAX_DURATION.toNanos();
    }
    /** 身份夹具登记自己创建的租户；来源库租户不能进入发送白名单。 */
    synchronized void authorizeTenant(String tenant) { tenants.add(tenant); }
    synchronized Instant deadline() { return deadline; }
    synchronized long remainingMillis() {
        if (deadline == null) return 0;
        return Math.max(0, (deadlineNanos - nanos.getAsLong()) / 1_000_000);
    }
    synchronized void stop(String code) { if (stopCode == null) stopCode = code; }
    synchronized String stopCode() { refresh(); timeGate(); return stopCode; }
    synchronized void close() { closed = true; }
    private boolean timeGate() {
        if (deadline != null && nanos.getAsLong() >= deadlineNanos) stop("LIVE_MEMORY_TIME_EXHAUSTED");
        return !closed && deadline != null && stopCode == null;
    }

    @Override public synchronized Registration register(Registration registration) {
        registration.policy().requireEnforced();
        if (!tenants.contains(registration.run().tenantId().value()))
            throw new IllegalStateException("LIVE_MEMORY_TENANT_NOT_AUTHORIZED");
        return delegate.register(registration);
    }
    @Override public Optional<Registration> find(RunRef run) { return delegate.find(run); }
    @Override public synchronized Reservation reserve(Demand demand) {
        if (!model(demand.category())) return delegate.reserve(demand);
        Attempt existing = attempts.get(key(demand));
        if (existing != null) return existing.demand.equals(demand) ? delegate.reserve(demand)
                : new Reservation.Denied(Refusal.REQUEST_CONFLICT,Optional.empty());
        var gate=reservationGate(demand);
        if(gate==ReservationGate.TOKEN_LIMIT)return new Reservation.Denied(Refusal.ROOT_LIMIT,Optional.of(Dimension.TOTAL_TOKENS));
        if(gate==ReservationGate.CONTROLLED_STOP)throw new IllegalStateException("LIVE_MEMORY_CONTROLLED_EXECUTION_STOPPED");
        Reservation decision = delegate.reserve(demand);
        if (decision instanceof Reservation.Granted) attempts.put(key(demand), new Attempt(demand));
        return decision;
    }
    /** ActionLedger 同事务准入不经过外层 reserve；发送前重读已冻结的正式 Demand。 */
    @Override public synchronized boolean markSent(RunRef run, String id) {
        Demand demand = persistentDemand(run, id);
        if (!model(demand.category())) return delegate.markSent(run, id);
        Attempt attempt = attempts.get(key(demand));
        if (attempt == null) {
            if (!canReserve(demand)) return false;
            attempt = new Attempt(demand); attempts.put(key(demand), attempt);
        }
        if (attempt.sent || !timeGate()) return false;
        if (!delegate.markSent(run, id)) return false;
        attempt.sent = true; attempt.sequence = ++sentSequence;
        if (demand.category() == Category.AUXILIARY_MODEL) {
            var context = MeterScope.current();
            String point = context == null ? "auxiliary.model" : context.callPoint();
            attempt.callPoint = point != null && point.matches("[a-zA-Z0-9_.-]{1,80}") ? point : "auxiliary.model";
            try { recording.beginThreadAttempt(new MemoryRecordingHttpClient.AttemptContext(id, run.runId(),
                    Category.AUXILIARY_MODEL.name(), "MODEL_GENERATE", "HOST_AUXILIARY", "NONE"), attempt.callPoint); }
            catch (RuntimeException invalid) { stop("LIVE_MEMORY_AUXILIARY_CORRELATION_FAILED"); throw invalid; }
        }
        return true;
    }
    private enum ReservationGate { OPEN, TOKEN_LIMIT, CONTROLLED_STOP }
    private boolean canReserve(Demand demand) { return reservationGate(demand)==ReservationGate.OPEN; }
    private ReservationGate reservationGate(Demand demand) {
        if (!timeGate() || !tenants.contains(demand.run().tenantId().value()) || delegate.find(demand.run()).isEmpty()) return ReservationGate.CONTROLLED_STOP;
        refresh();
        long bound = demand.resources().totalTokens();
        if (bound <= 0 || demand.resources().businessModels() + demand.resources().auxiliaryModels() != 1
                || attempts.size() >= MAX_ATTEMPTS) {
            stop("LIVE_MEMORY_BUDGET_EXHAUSTED"); return ReservationGate.CONTROLLED_STOP;
        }
        if(bound > MAX_TOKENS-charged()) {stop("LIVE_MEMORY_BUDGET_EXHAUSTED");return ReservationGate.TOKEN_LIMIT;}
        return timeGate()?ReservationGate.OPEN:ReservationGate.CONTROLLED_STOP;
    }
    synchronized void providerUsage(RunRef run, String id, Usage usage) {
        Attempt attempt = attempts.get(new AttemptKey(run, id));
        if (attempt == null || !attempt.sent) throw new IllegalStateException("LIVE_MEMORY_ATTEMPT_NOT_SENT");
        if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) stop("LIVE_MEMORY_USAGE_UNKNOWN");
        adoptUsage(attempt, usage);
    }
    private void adoptUsage(Attempt attempt, Usage usage) {
        Long suppliedInput = usage.inputTokens().isPresent() ? usage.inputTokens().getAsLong() : null;
        Long suppliedOutput = usage.outputTokens().isPresent() ? usage.outputTokens().getAsLong() : null;
        boolean conflict = (attempt.inputTokens != null && suppliedInput != null && !attempt.inputTokens.equals(suppliedInput))
                || (attempt.outputTokens != null && suppliedOutput != null && !attempt.outputTokens.equals(suppliedOutput));
        if (suppliedInput != null) attempt.inputTokens = attempt.inputTokens == null ? suppliedInput : Math.max(attempt.inputTokens, suppliedInput);
        if (suppliedOutput != null) attempt.outputTokens = attempt.outputTokens == null ? suppliedOutput : Math.max(attempt.outputTokens, suppliedOutput);
        if (conflict) { stop("LIVE_MEMORY_USAGE_CONFLICT"); closed = true; }
        if (attempt.inputTokens == null || attempt.outputTokens == null) return;
        long input = attempt.inputTokens, output = attempt.outputTokens;
        long total = Math.addExact(input, output);
        if (input < 0 || output < 0 || input > attempt.demand.resources().inputTokens()
                || output > attempt.demand.resources().outputTokens()) {
            attempt.reportedTokens = total; stop("LIVE_MEMORY_USAGE_EXCEEDS_RESERVATION"); closed = true; return;
        }
        if (attempt.reportedTokens != null && attempt.reportedTokens != total) {
            attempt.reportedTokens = Math.max(attempt.reportedTokens, total);
            stop("LIVE_MEMORY_USAGE_CONFLICT"); closed = true; return;
        }
        attempt.reportedTokens = total;
    }
    /** 仅供应商完整 input/output 报告可替换保守预留；未知状态、TTL 和取消不释放预算。 */
    private void refresh() {
        for (Attempt attempt : attempts.values()) {
            if (!attempt.sent) continue;
            var facts = withTenant(attempt.demand.run(), connection -> {
                OptionalLong input = OptionalLong.empty(), output = OptionalLong.empty();
                try (var query = connection.prepareStatement("SELECT dimension,reported FROM agent_runtime_budget_attempt_amounts "
                        + "WHERE tenant_id=? AND attempt_id=? AND dimension IN ('INPUT_TOKENS','OUTPUT_TOKENS')")) {
                    query.setString(1, attempt.demand.run().tenantId().value()); query.setString(2, attempt.demand.attemptId());
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            long value = rows.getLong(2); if (rows.wasNull()) continue;
                            if ("INPUT_TOKENS".equals(rows.getString(1))) input = OptionalLong.of(value); else output = OptionalLong.of(value);
                        }
                    }
                }
                String terminal = null;
                try (var query = connection.prepareStatement("SELECT status FROM nw_external_invocations WHERE tenant_id=CAST(? AS bigint) AND attempt_id=?")) {
                    query.setString(1, attempt.demand.run().tenantId().value()); query.setString(2, attempt.demand.attemptId());
                    try (var rows = query.executeQuery()) { if (rows.next()) terminal = rows.getString(1); }
                }
                return new PersistedFacts(Usage.reported(input, output), terminal);
            });
            adoptUsage(attempt, facts.usage());
            if (facts.terminal() != null && TERMINAL_STATUSES.contains(facts.terminal())) {
                if (attempt.demand.category() == Category.AUXILIARY_MODEL && attempt.terminal == null)
                    recording.terminalAttempt(attempt.demand.attemptId(), facts.terminal());
                attempt.terminal = facts.terminal();
                if ("UNRESOLVED".equals(facts.terminal())) stop("LIVE_MEMORY_MODEL_OUTCOME_UNKNOWN");
                if ("FAILED".equals(facts.terminal()) && attempt.reportedTokens == null)
                    stop("LIVE_MEMORY_FAILED_USAGE_UNKNOWN");
            }
        }
    }
    private long charged() { return attempts.values().stream().mapToLong(value -> value.reportedTokens == null
            ? value.demand.resources().totalTokens() : value.reportedTokens).sum(); }
    @Override public synchronized void saveResult(RunRef run, String id, Usage usage, String result) {
        delegate.saveResult(run, id, usage, result);
        Attempt attempt = attempts.get(new AttemptKey(run, id));
        if (attempt != null && attempt.sent) {
            if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) stop("LIVE_MEMORY_USAGE_UNKNOWN");
            adoptUsage(attempt, usage);
        }
    }
    @Override public synchronized void settle(RunRef run, String id) { delegate.settle(run, id); refresh(); }
    @Override public synchronized void recover(RunRef run) { delegate.recover(run); refresh(); }
    @Override public Snapshot snapshot(RunRef run) { return delegate.snapshot(run); }
    @Override public void updateClosing(RunRef run, Closing closing) { delegate.updateClosing(run, closing); }
    @Override public void admitClosing(RunRef run, ClosingAdmission admission) { delegate.admitClosing(run, admission); }
    @Override public void allowClosingTarget(RunRef run, String handle) { delegate.allowClosingTarget(run, handle); }
    @Override public void enterClosing(RunRef run) { delegate.enterClosing(run); }
    @Override public ActivityObservation recordActivity(RunRef run, String id, Instant from, Instant through) { return delegate.recordActivity(run, id, from, through); }
    synchronized Report report() {
        refresh();
        var values = attempts.values().stream().map(value -> new AttemptEvidence(value.demand.run().runId(),
                value.demand.attemptId(), value.demand.category().name(), value.demand.closing(), value.sent,
                value.demand.resources().totalTokens(), value.reportedTokens, value.callPoint, value.terminal)).toList();
        return new Report(MAX_ATTEMPTS, MAX_TOKENS, MAX_DURATION.toSeconds(), attempts.size(),
                (int) attempts.values().stream().filter(value -> value.sent).count(),
                attempts.values().stream().filter(value -> value.reportedTokens != null).mapToLong(value -> value.reportedTokens).sum(),
                charged(), (int) attempts.values().stream().filter(value -> value.sent && value.reportedTokens == null).count(), stopCode, values);
    }
    /** 统一正式预算记录是业务聊天与辅助提取的唯一费用来源；未发送的预留不冒充调用。 */
    synchronized List<RuntimeCallEvidence> runtimeCalls() {
        refresh();
        return attempts.values().stream().filter(value -> value.sent).map(value -> {
            var usage = new LinkedHashMap<String, Object>();
            usage.put("inputTokens", value.inputTokens); usage.put("outputTokens", value.outputTokens);
            return new RuntimeCallEvidence(value.sequence, value.demand.attemptId(), value.demand.run().runId(),
                    value.demand.run().tenantId().value(), value.demand.category().name(),
                    value.terminal == null ? "STARTED" : value.terminal, value.callPoint, usage);
        }).sorted(java.util.Comparator.comparingLong(RuntimeCallEvidence::sequence)).toList();
    }
    private Demand persistentDemand(RunRef run, String id) {
        return withTenant(run, connection -> {
            String fingerprint; Category category; boolean closing;
            try (var query = connection.prepareStatement("SELECT fingerprint,category,closing FROM agent_runtime_budget_attempts "
                    + "WHERE tenant_id=? AND run_id=? AND attempt_id=?")) {
                query.setString(1, run.tenantId().value()); query.setString(2, run.runId()); query.setString(3, id);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("LIVE_MEMORY_PERSISTENT_DEMAND_MISSING");
                    fingerprint = rows.getString(1); category = Category.valueOf(rows.getString(2)); closing = rows.getBoolean(3);
                }
            }
            long[] values = new long[Dimension.values().length];
            try (var query = connection.prepareStatement("SELECT dimension,reserved FROM agent_runtime_budget_attempt_amounts WHERE tenant_id=? AND attempt_id=?")) {
                query.setString(1, run.tenantId().value()); query.setString(2, id);
                try (var rows = query.executeQuery()) {
                    int count = 0; while (rows.next()) { values[Dimension.valueOf(rows.getString(1)).ordinal()] = rows.getLong(2); count++; }
                    if (count != Dimension.values().length) throw new IllegalStateException("LIVE_MEMORY_DEMAND_INCOMPLETE");
                }
            }
            return new Demand(run, id, fingerprint, category, Resources.from(values), closing);
        });
    }
    private <T> T withTenant(RunRef run, Query<T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) { bind.setString(1, run.tenantId().value()); bind.execute(); }
            T result = work.read(connection); connection.rollback(); return result;
        } catch (SQLException unavailable) { throw new IllegalStateException("LIVE_MEMORY_BUDGET_FACT_READ_FAILED"); }
    }
    private static boolean model(Category category) { return category == Category.BUSINESS_MODEL || category == Category.AUXILIARY_MODEL; }
    private static AttemptKey key(Demand demand) { return new AttemptKey(demand.run(), demand.attemptId()); }
    private record AttemptKey(RunRef run, String attemptId) { }
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of("SUCCEEDED", "FAILED", "UNRESOLVED");
    private record PersistedFacts(Usage usage, String terminal) { }
    private static final class Attempt {
        final Demand demand; boolean sent; long sequence; Long reportedTokens, inputTokens, outputTokens; String callPoint, terminal;
        Attempt(Demand demand) { this.demand = demand; }
    }
    @FunctionalInterface private interface Query<T> { T read(Connection connection) throws SQLException; }
    record AttemptEvidence(String runId, String attemptId, String category, boolean closing, boolean sent,
                           long reservedTokens, Long reportedTokens, String callPoint, String terminal) { }
    record RuntimeCallEvidence(long sequence, String attemptId, String runId, String tenantId, String category,
                               String terminal, String callPoint, Map<String, Object> usage) { }
    record Report(int maximumAttempts, long maximumTokens, long maximumSeconds, int reservedSlots, int sentAttempts,
                  long reportedTokens, long chargedOrReservedTokens, int unknownUsageAttempts, String stopCode, List<AttemptEvidence> attempts) { }
}
