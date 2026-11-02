package com.skillforge.institution.integration;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import javax.sql.DataSource;

/**
 * 两场景共享的真实发送门禁。预留使用可信 Demand，未知用量不归还；与生产 JDBC 根账并存。
 * Ledger 的同事务准入可能不经过外层 reserve，markSent 必须重新读取原持久 Demand 后取得本门禁。
 */
final class LiveCapacityBudgets implements ExecutionBudgetPort {
    static final int MAX_ATTEMPTS = 16;
    static final long MAX_TOKENS = 160_000;
    enum Scene { WEBSITE, DELEGATION }
    private final DataSource source;
    private final ExecutionBudgetPort delegate;
    private final Map<RunRef, Scene> roots = new HashMap<>();
    private final Map<AttemptKey, Attempt> attempts = new HashMap<>();
    private final Map<Scene, String> stopped = new java.util.EnumMap<>(Scene.class);
    private Scene activeScene;
    private String tenant;
    private boolean closed;

    LiveCapacityBudgets(DataSource source) { this.source = source; delegate = new JdbcExecutionBudgets(source); }
    synchronized void begin(Scene scene, String tenant) {
        if (closed || activeScene != null || roots.containsValue(scene)) throw new IllegalStateException("LIVE_SCENE_INVALID");
        this.tenant = tenant; activeScene = scene;
    }
    synchronized void end(Scene scene) {
        stopped.putIfAbsent(scene, "SCENE_FINISHED");
        if (activeScene == scene) activeScene = null;
    }
    synchronized void close() { closed = true; }
    synchronized void stop(RunRef run, String reason) { scene(run).ifPresent(value -> stopped.put(value, reason)); }
    synchronized String stopCode(Scene scene) {
        String code = stopped.get(scene);
        return "SCENE_FINISHED".equals(code) ? null : code;
    }
    @Override public synchronized Registration register(Registration registration) {
        registration.policy().requireEnforced();
        if (tenant == null || !tenant.equals(registration.run().tenantId().value()))
            throw new IllegalStateException("LIVE_BUDGET_TENANT_INVALID");
        Scene scene = roots.get(registration.root());
        if (scene == null) {
            if (activeScene == null || !registration.run().equals(registration.root()))
                throw new IllegalStateException("LIVE_ROOT_NOT_AUTHORIZED");
            scene = activeScene;
        }
        Registration frozen = delegate.register(registration);
        roots.putIfAbsent(registration.root(), scene);
        return frozen;
    }
    @Override public Optional<Registration> find(RunRef run) { return delegate.find(run); }

    @Override public synchronized Reservation reserve(Demand demand) {
        if (!model(demand.category())) return delegate.reserve(demand);
        Attempt existing = attempts.get(key(demand));
        if (existing != null) return existing.demand.equals(demand) ? delegate.reserve(demand)
                : new Reservation.Denied(Refusal.REQUEST_CONFLICT,Optional.empty());
        var gate=reservationGate(demand);
        if(gate==ReservationGate.TOKEN_LIMIT)return new Reservation.Denied(Refusal.ROOT_LIMIT,Optional.of(Dimension.TOTAL_TOKENS));
        if(gate==ReservationGate.CONTROLLED_STOP)throw new IllegalStateException("LIVE_BUDGET_CONTROLLED_EXECUTION_STOPPED");
        Reservation decision = delegate.reserve(demand);
        if (decision instanceof Reservation.Granted) attempts.put(key(demand), new Attempt(demand, requiredScene(demand.run())));
        return decision;
    }
    @Override public synchronized boolean markSent(RunRef run, String id) {
        Demand demand = persistentDemand(run, id);
        if (!model(demand.category())) return delegate.markSent(run, id);
        Attempt attempt = attempts.get(key(demand));
        if (attempt == null) {
            if (!canReserve(demand)) return false;
            attempt = new Attempt(demand, requiredScene(run));
            attempts.put(key(demand), attempt);
        }
        if (attempt.sent || closed || stopped.containsKey(attempt.scene)) return false;
        if (!delegate.markSent(run, id)) return false;
        attempt.sent = true;
        return true;
    }
    private enum ReservationGate { OPEN, TOKEN_LIMIT, CONTROLLED_STOP }
    private boolean canReserve(Demand demand) { return reservationGate(demand)==ReservationGate.OPEN; }
    private ReservationGate reservationGate(Demand demand) {
        Scene scene = requiredScene(demand.run());
        if (closed || stopped.containsKey(scene)) return ReservationGate.CONTROLLED_STOP;
        refresh();
        long bound = demand.resources().totalTokens();
        if (bound <= 0 || demand.resources().businessModels() + demand.resources().auxiliaryModels() != 1
                || attempts.size() >= MAX_ATTEMPTS) {
            stopped.put(scene, "LIVE_BUDGET_EXHAUSTED"); return ReservationGate.CONTROLLED_STOP;
        }
        if(bound > MAX_TOKENS-charged()) {stopped.put(scene,"LIVE_BUDGET_EXHAUSTED");return ReservationGate.TOKEN_LIMIT;}
        return closed?ReservationGate.CONTROLLED_STOP:ReservationGate.OPEN;
    }
    private Optional<Scene> scene(RunRef run) {
        return delegate.find(run).map(value -> roots.get(value.root()));
    }
    private Scene requiredScene(RunRef run) {
        return scene(run).orElseThrow(() -> new IllegalStateException("LIVE_ATTEMPT_SCOPE_INVALID"));
    }
    synchronized void providerUsage(RunRef run, String id, Usage usage) {
        Attempt attempt = attempts.get(new AttemptKey(run, id));
        if (attempt == null || !attempt.sent) throw new IllegalStateException("LIVE_ATTEMPT_NOT_SENT");
        adoptUsage(attempt, usage);
    }
    private void adoptUsage(Attempt attempt, Usage usage) {
        if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) return;
        long input = usage.inputTokens().getAsLong(), output = usage.outputTokens().getAsLong();
        long total = Math.addExact(input, output);
        if (input < 0 || output < 0 || input > attempt.demand.resources().inputTokens()
                || output > attempt.demand.resources().outputTokens()) {
            attempt.reportedTokens = total;
            stopped.put(attempt.scene, "USAGE_EXCEEDS_RESERVATION"); closed = true; return;
        }
        if (attempt.reportedTokens != null && attempt.reportedTokens != total) {
            attempt.reportedTokens = Math.max(attempt.reportedTokens, total);
            stopped.put(attempt.scene, "USAGE_CONFLICT"); closed = true; return;
        }
        attempt.reportedTokens = total;
    }
    /** 部分及未知报告保持完整保守预留；状态名、TTL 和失败标签都不能释放。 */
    private void refresh() {
        for (Attempt attempt : attempts.values()) {
            if (!attempt.sent || attempt.reportedTokens != null) continue;
            Usage usage = withTenant(attempt.demand.run(), connection -> {
                OptionalLong input = OptionalLong.empty(), output = OptionalLong.empty();
                try (var query = connection.prepareStatement("""
                        SELECT dimension,reported FROM agent_runtime_budget_attempt_amounts
                        WHERE tenant_id=? AND attempt_id=? AND dimension IN ('INPUT_TOKENS','OUTPUT_TOKENS')
                        """)) {
                    query.setString(1, tenant); query.setString(2, attempt.demand.attemptId());
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            long value = rows.getLong(2);
                            if (rows.wasNull()) continue;
                            if ("INPUT_TOKENS".equals(rows.getString(1))) input = OptionalLong.of(value);
                            else output = OptionalLong.of(value);
                        }
                    }
                }
                return Usage.reported(input, output);
            });
            adoptUsage(attempt, usage);
        }
    }
    private long charged() {
        return attempts.values().stream().mapToLong(value -> value.reportedTokens == null
                ? value.demand.resources().totalTokens() : value.reportedTokens).sum();
    }
    @Override public synchronized void saveResult(RunRef run, String id, Usage usage, String result) {
        delegate.saveResult(run, id, usage, result);
        Attempt attempt = attempts.get(new AttemptKey(run, id));
        if (attempt != null && attempt.sent) adoptUsage(attempt, usage);
    }
    @Override public synchronized void settle(RunRef run, String id) { delegate.settle(run, id); refresh(); }
    @Override public synchronized void recover(RunRef run) { delegate.recover(run); refresh(); }
    @Override public Snapshot snapshot(RunRef run) { return delegate.snapshot(run); }
    @Override public void updateClosing(RunRef run, Closing closing) { delegate.updateClosing(run, closing); }
    @Override public void admitClosing(RunRef run, ClosingAdmission admission) { delegate.admitClosing(run, admission); }
    @Override public void allowClosingTarget(RunRef run, String handle) { delegate.allowClosingTarget(run, handle); }
    @Override public void enterClosing(RunRef run) { delegate.enterClosing(run); }
    @Override public ActivityObservation recordActivity(RunRef run, String id, Instant from, Instant through) {
        return delegate.recordActivity(run, id, from, through);
    }
    synchronized Report report() {
        refresh();
        var reports = new ArrayList<SceneReport>();
        for (Scene scene : Scene.values()) {
            var values = attempts.values().stream().filter(value -> value.scene == scene).toList();
            reports.add(new SceneReport(scene, (int) values.stream().filter(value -> value.sent).count(),
                    values.stream().filter(value -> value.reportedTokens != null).mapToLong(value -> value.reportedTokens).sum(),
                    (int) values.stream().filter(value -> value.sent && value.reportedTokens == null).count(), stopCode(scene)));
        }
        return new Report(MAX_ATTEMPTS, MAX_TOKENS, attempts.size(),
                reports.stream().mapToInt(SceneReport::actualAttempts).sum(),
                reports.stream().mapToLong(SceneReport::reportedTokens).sum(), charged(), List.copyOf(reports));
    }
    private Demand persistentDemand(RunRef run, String id) {
        return withTenant(run, connection -> {
            String fingerprint; Category category; boolean closing;
            try (var query = connection.prepareStatement("""
                    SELECT fingerprint,category,closing FROM agent_runtime_budget_attempts
                    WHERE tenant_id=? AND run_id=? AND attempt_id=?
                    """)) {
                query.setString(1, run.tenantId().value()); query.setString(2, run.runId()); query.setString(3, id);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("LIVE_PERSISTENT_DEMAND_MISSING");
                    fingerprint = rows.getString(1); category = Category.valueOf(rows.getString(2)); closing = rows.getBoolean(3);
                }
            }
            long[] values = new long[Dimension.values().length];
            try (var query = connection.prepareStatement("""
                    SELECT dimension,reserved FROM agent_runtime_budget_attempt_amounts WHERE tenant_id=? AND attempt_id=?
                    """)) {
                query.setString(1, run.tenantId().value()); query.setString(2, id);
                try (var rows = query.executeQuery()) {
                    int count = 0;
                    while (rows.next()) { values[Dimension.valueOf(rows.getString(1)).ordinal()] = rows.getLong(2); count++; }
                    if (count != Dimension.values().length) throw new IllegalStateException("LIVE_DEMAND_INCOMPLETE");
                }
            }
            return new Demand(run, id, fingerprint, category, Resources.from(values), closing);
        });
    }
    private <T> T withTenant(RunRef run, Query<T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                bind.setString(1, run.tenantId().value()); bind.execute();
            }
            T result = work.read(connection); connection.rollback(); return result;
        } catch (SQLException unavailable) { throw new IllegalStateException("LIVE_BUDGET_FACT_READ_FAILED"); }
    }
    private static boolean model(Category category) { return category == Category.BUSINESS_MODEL || category == Category.AUXILIARY_MODEL; }
    private static AttemptKey key(Demand demand) { return new AttemptKey(demand.run(), demand.attemptId()); }
    private record AttemptKey(RunRef run, String attemptId) { }
    private static final class Attempt {
        final Demand demand; final Scene scene; boolean sent; Long reportedTokens;
        Attempt(Demand demand, Scene scene) { this.demand = demand; this.scene = scene; }
    }
    @FunctionalInterface private interface Query<T> { T read(Connection connection) throws SQLException; }
    record SceneReport(Scene scene, int actualAttempts, long reportedTokens, int unknownUsageAttempts, String stopCode) { }
    record Report(int maximumAttempts, long maximumTokens, int reservedSlots, int actualAttempts,
                  long reportedTokens, long chargedOrReservedTokens, List<SceneReport> scenes) { }
}
