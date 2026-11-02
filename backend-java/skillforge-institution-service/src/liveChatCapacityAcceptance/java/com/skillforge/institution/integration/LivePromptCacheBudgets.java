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

/** 单 Session 真实验收的全局上限；正式 JDBC 根账保留，未知用量继续占用完整预留。 */
final class LivePromptCacheBudgets implements ExecutionBudgetPort {
    static final int MAX_ATTEMPTS = 18;
    static final long MAX_TOKENS = 400_000;
    static final Duration MAX_DURATION = Duration.ofMinutes(10);
    private final DataSource source;
    private final ExecutionBudgetPort delegate;
    private final PromptCacheRecordingHttpClient recording;
    private final int maximumAttempts;
    private final java.util.function.BiConsumer<RunRef, String> auxiliaryGate;
    private final Map<AttemptKey, Attempt> attempts = new LinkedHashMap<>();
    private String tenant;
    private Instant deadline;
    private long deadlineNanos;
    private String stopCode;
    private boolean closed;

    LivePromptCacheBudgets(DataSource source, PromptCacheRecordingHttpClient recording) {
        this(source, recording, MAX_ATTEMPTS, (run, id) -> { });
    }

    /** 其他隔离live入口复用同一真实预算账；辅助发送可补所属用途的精确来源门禁。 */
    LivePromptCacheBudgets(DataSource source, PromptCacheRecordingHttpClient recording, int maximumAttempts,
            java.util.function.BiConsumer<RunRef, String> auxiliaryGate) {
        this(source, recording, maximumAttempts, auxiliaryGate, new JdbcExecutionBudgets(source));
    }

    LivePromptCacheBudgets(DataSource source, PromptCacheRecordingHttpClient recording, int maximumAttempts,
            java.util.function.BiConsumer<RunRef, String> auxiliaryGate, ExecutionBudgetPort delegate) {
        if (maximumAttempts < 1 || maximumAttempts > 24) throw new IllegalArgumentException("LIVE_ATTEMPT_BOUND_INVALID");
        this.source = source; this.recording = recording; this.maximumAttempts = maximumAttempts;
        this.auxiliaryGate = java.util.Objects.requireNonNull(auxiliaryGate); this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    synchronized void begin(String tenant) {
        if (this.tenant != null || closed) throw new IllegalStateException("LIVE_CACHE_SCOPE_ALREADY_STARTED");
        this.tenant = tenant; deadline = Instant.now().plus(MAX_DURATION);
        deadlineNanos = System.nanoTime() + MAX_DURATION.toNanos();
    }
    synchronized Instant deadline() { return deadline; }
    synchronized long remainingMillis() {
        if (deadline == null) return 0;
        return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000);
    }
    synchronized void stop(String code) { if (stopCode == null) stopCode = code; }
    synchronized String stopCode() { refresh(); timeGate(); return stopCode; }
    synchronized void close() { closed = true; }
    private boolean timeGate() {
        if (deadline != null && System.nanoTime() >= deadlineNanos) stop("LIVE_CACHE_TIME_EXHAUSTED");
        return !closed && deadline != null && stopCode == null;
    }

    @Override public synchronized Registration register(Registration registration) {
        registration.policy().requireEnforced();
        if (tenant == null || !tenant.equals(registration.run().tenantId().value()))
            throw new IllegalStateException("LIVE_CACHE_TENANT_NOT_AUTHORIZED");
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
        if(gate==ReservationGate.CONTROLLED_STOP)throw new IllegalStateException("LIVE_CACHE_CONTROLLED_EXECUTION_STOPPED");
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
        if (demand.category() == Category.AUXILIARY_MODEL) {
            try { auxiliaryGate.accept(run, id); }
            catch (RuntimeException | AssertionError rejected) { stop("LIVE_AUXILIARY_SOURCE_REJECTED"); throw rejected; }
        }
        if (!delegate.markSent(run, id)) return false;
        attempt.sent = true;
        if (demand.category() == Category.AUXILIARY_MODEL) {
            var context = MeterScope.current();
            String point = context == null ? "auxiliary.model" : context.callPoint();
            attempt.callPoint = point != null && point.matches("[a-zA-Z0-9_.-]{1,80}") ? point : "auxiliary.model";
            try { recording.beginThreadAttempt(new PromptCacheRecordingHttpClient.AttemptContext(id, run.runId(),
                    Category.AUXILIARY_MODEL.name(), "MODEL_GENERATE", "HOST_AUXILIARY", "NONE"), attempt.callPoint); }
            catch (RuntimeException invalid) { stop("LIVE_CACHE_AUXILIARY_CORRELATION_FAILED"); throw invalid; }
        }
        return true;
    }

    /** 正式事务已登记并标记SENT；live只附加全局发送门禁，不重放预留、markSent或结算。 */
    synchronized void beforeProviderSend(RunRef run, String id, Category expected) {
        Demand demand = persistentDemand(run, id, true);
        if (!model(expected) || demand.category() != expected)
            throw new IllegalStateException("LIVE_CACHE_SEND_CATEGORY_MISMATCH");
        Attempt existing = attempts.get(key(demand));
        if (existing != null && (!existing.demand.equals(demand) || existing.sent))
            throw new IllegalStateException("LIVE_CACHE_DUPLICATE_PROVIDER_SEND");
        if (existing == null) {
            if (!canReserve(demand)) throw new IllegalStateException("LIVE_CACHE_CONTROLLED_EXECUTION_STOPPED");
            existing = new Attempt(demand); attempts.put(key(demand), existing);
        }
        if (!timeGate()) throw new IllegalStateException("LIVE_CACHE_CONTROLLED_EXECUTION_STOPPED");
        if (expected == Category.AUXILIARY_MODEL) {
            try { auxiliaryGate.accept(run, id); }
            catch (RuntimeException | AssertionError rejected) { stop("LIVE_AUXILIARY_SOURCE_REJECTED"); throw rejected; }
        }
        existing.sent = true;
        if (expected == Category.AUXILIARY_MODEL) {
            var context = MeterScope.current();
            String point = context == null ? "auxiliary.model" : context.callPoint();
            existing.callPoint = point != null && point.matches("[a-zA-Z0-9_.-]{1,80}") ? point : "auxiliary.model";
            try { recording.beginThreadAttempt(new PromptCacheRecordingHttpClient.AttemptContext(id, run.runId(),
                    Category.AUXILIARY_MODEL.name(), "MODEL_GENERATE", "HOST_AUXILIARY", "NONE"), existing.callPoint); }
            catch (RuntimeException invalid) { stop("LIVE_CACHE_AUXILIARY_CORRELATION_FAILED"); throw invalid; }
        }
    }
    private enum ReservationGate { OPEN, TOKEN_LIMIT, CONTROLLED_STOP }
    private boolean canReserve(Demand demand) { return reservationGate(demand)==ReservationGate.OPEN; }
    private ReservationGate reservationGate(Demand demand) {
        if (!timeGate() || !tenant.equals(demand.run().tenantId().value()) || delegate.find(demand.run()).isEmpty()) return ReservationGate.CONTROLLED_STOP;
        refresh();
        long bound = demand.resources().totalTokens();
        if (bound <= 0 || demand.resources().businessModels() + demand.resources().auxiliaryModels() != 1
                || attempts.size() >= maximumAttempts) {
            stop("LIVE_CACHE_BUDGET_EXHAUSTED"); return ReservationGate.CONTROLLED_STOP;
        }
        if(bound > MAX_TOKENS-charged()) {stop("LIVE_CACHE_BUDGET_EXHAUSTED");return ReservationGate.TOKEN_LIMIT;}
        return timeGate()?ReservationGate.OPEN:ReservationGate.CONTROLLED_STOP;
    }
    synchronized void providerUsage(RunRef run, String id, Usage usage) {
        Attempt attempt = attempts.get(new AttemptKey(run, id));
        if (attempt == null || !attempt.sent) throw new IllegalStateException("LIVE_CACHE_ATTEMPT_NOT_SENT");
        if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) stop("LIVE_CACHE_USAGE_UNKNOWN");
        adoptUsage(attempt, usage);
    }
    private void adoptUsage(Attempt attempt, Usage usage) {
        if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) return;
        long input = usage.inputTokens().getAsLong(), output = usage.outputTokens().getAsLong();
        long total = Math.addExact(input, output);
        if (input < 0 || output < 0 || input > attempt.demand.resources().inputTokens()
                || output > attempt.demand.resources().outputTokens()) {
            attempt.reportedTokens = total; stop("LIVE_CACHE_USAGE_EXCEEDS_RESERVATION"); closed = true; return;
        }
        if (attempt.reportedTokens != null && attempt.reportedTokens != total) {
            attempt.reportedTokens = Math.max(attempt.reportedTokens, total);
            stop("LIVE_CACHE_USAGE_CONFLICT"); closed = true; return;
        }
        attempt.reportedTokens = total;
    }
    /** 仅供应商完整 input/output 报告可替换保守预留；未知状态、TTL 和取消不释放预算。 */
    private void refresh() {
        for (Attempt attempt : attempts.values()) {
            if (!attempt.sent) continue;
            var facts = withTenant(attempt.demand.run(), connection -> {
                OptionalLong input = OptionalLong.empty(), output = OptionalLong.empty();
                AttemptState state;
                // 同一SQL快照读取预算完成状态和维度，不能将旧空维度与新完成事实拼成最终缺报。
                try (var query = connection.prepareStatement("SELECT attempt.state, "
                        + "max(CASE WHEN amount.dimension='INPUT_TOKENS' THEN amount.reported END), "
                        + "max(CASE WHEN amount.dimension='OUTPUT_TOKENS' THEN amount.reported END) "
                        + "FROM agent_runtime_budget_attempts attempt JOIN agent_runtime_budget_attempt_amounts amount "
                        + "ON amount.tenant_id=attempt.tenant_id AND amount.attempt_id=attempt.attempt_id "
                        + "WHERE attempt.tenant_id=? AND attempt.run_id=? AND attempt.attempt_id=? GROUP BY attempt.state")) {
                    query.setString(1, tenant); query.setString(2, attempt.demand.run().runId()); query.setString(3, attempt.demand.attemptId());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) throw new IllegalStateException("LIVE_CACHE_PERSISTENT_DEMAND_MISSING");
                        state = AttemptState.valueOf(rows.getString(1));
                        long value = rows.getLong(2); if (!rows.wasNull()) input = OptionalLong.of(value);
                        value = rows.getLong(3); if (!rows.wasNull()) output = OptionalLong.of(value);
                    }
                }
                String terminal = null;
                try (var query = connection.prepareStatement("SELECT status FROM nw_external_invocations WHERE tenant_id=CAST(? AS bigint) AND attempt_id=?")) {
                    query.setString(1, tenant); query.setString(2, attempt.demand.attemptId());
                    try (var rows = query.executeQuery()) { if (rows.next()) terminal = rows.getString(1); }
                }
                return new PersistedFacts(Usage.reported(input, output), state, terminal);
            });
            if (attempt.reportedTokens == null) adoptUsage(attempt, facts.usage());
            if (facts.terminal() != null && TERMINAL_STATUSES.contains(facts.terminal())) {
                if (attempt.demand.category() == Category.AUXILIARY_MODEL && attempt.terminal == null)
                    recording.terminalAttempt(attempt.demand.attemptId(), facts.terminal());
                attempt.terminal = facts.terminal();
                if (!"SUCCEEDED".equals(facts.terminal())) stop("LIVE_CACHE_MODEL_" + facts.terminal());
            }
            if ((facts.state() == AttemptState.RESULT_SAVED || facts.state() == AttemptState.SETTLED)
                    && attempt.reportedTokens == null) stop("LIVE_CACHE_USAGE_UNKNOWN");
        }
    }
    private long charged() { return attempts.values().stream().mapToLong(value -> value.reportedTokens == null
            ? value.demand.resources().totalTokens() : value.reportedTokens).sum(); }
    @Override public synchronized void saveResult(RunRef run, String id, Usage usage, String result) {
        delegate.saveResult(run, id, usage, result);
        Attempt attempt = attempts.get(new AttemptKey(run, id));
        if (attempt != null && attempt.sent) {
            if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) stop("LIVE_CACHE_USAGE_UNKNOWN");
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
        return new Report(maximumAttempts, MAX_TOKENS, MAX_DURATION.toSeconds(), attempts.size(),
                (int) attempts.values().stream().filter(value -> value.sent).count(),
                attempts.values().stream().filter(value -> value.reportedTokens != null).mapToLong(value -> value.reportedTokens).sum(),
                charged(), (int) attempts.values().stream().filter(value -> value.sent && value.reportedTokens == null).count(), stopCode, values);
    }
    private Demand persistentDemand(RunRef run, String id) {
        return persistentDemand(run, id, false);
    }
    private Demand persistentDemand(RunRef run, String id, boolean sentRequired) {
        return withTenant(run, connection -> {
            String fingerprint; Category category; boolean closing;
            try (var query = connection.prepareStatement("SELECT fingerprint,category,closing,state FROM agent_runtime_budget_attempts "
                    + "WHERE tenant_id=? AND run_id=? AND attempt_id=?")) {
                query.setString(1, run.tenantId().value()); query.setString(2, run.runId()); query.setString(3, id);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("LIVE_CACHE_PERSISTENT_DEMAND_MISSING");
                    fingerprint = rows.getString(1); category = Category.valueOf(rows.getString(2)); closing = rows.getBoolean(3);
                    if (sentRequired && !"SENT".equals(rows.getString(4)))
                        throw new IllegalStateException("LIVE_CACHE_PERSISTENT_SEND_REQUIRED");
                }
            }
            long[] values = new long[Dimension.values().length];
            try (var query = connection.prepareStatement("SELECT dimension,reserved FROM agent_runtime_budget_attempt_amounts WHERE tenant_id=? AND attempt_id=?")) {
                query.setString(1, run.tenantId().value()); query.setString(2, id);
                try (var rows = query.executeQuery()) {
                    int count = 0; while (rows.next()) { values[Dimension.valueOf(rows.getString(1)).ordinal()] = rows.getLong(2); count++; }
                    if (count != Dimension.values().length) throw new IllegalStateException("LIVE_CACHE_DEMAND_INCOMPLETE");
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
        } catch (SQLException unavailable) { throw new IllegalStateException("LIVE_CACHE_BUDGET_FACT_READ_FAILED"); }
    }
    private static boolean model(Category category) { return category == Category.BUSINESS_MODEL || category == Category.AUXILIARY_MODEL; }
    private static AttemptKey key(Demand demand) { return new AttemptKey(demand.run(), demand.attemptId()); }
    private record AttemptKey(RunRef run, String attemptId) { }
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of("SUCCEEDED", "FAILED", "UNRESOLVED");
    private record PersistedFacts(Usage usage, AttemptState state, String terminal) { }
    private static final class Attempt {
        final Demand demand; boolean sent; Long reportedTokens; String callPoint, terminal;
        Attempt(Demand demand) { this.demand = demand; }
    }
    @FunctionalInterface private interface Query<T> { T read(Connection connection) throws SQLException; }
    record AttemptEvidence(String runId, String attemptId, String category, boolean closing, boolean sent,
                           long reservedTokens, Long reportedTokens, String callPoint, String terminal) { }
    record Report(int maximumAttempts, long maximumTokens, long maximumSeconds, int reservedSlots, int sentAttempts,
                  long reportedTokens, long chargedOrReservedTokens, int unknownUsageAttempts, String stopCode, List<AttemptEvidence> attempts) { }
}
