package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 离线测试全批次发送门禁；不启动供应商、不读取来源库、不依赖真实时间等待。 */
class MemoryEvaluationBudgetsTest {
    @Test void controlledPlatformBatchRejectsObserveOnlyBeforeDelegateMutation() throws Exception {
        var fixture = fixture(Category.BUSINESS_MODEL, 1000, 2048);
        var run = fixture.demand().run();
        var registration = new Registration(run, run, Role.PRIMARY,
                new Policy.ObserveOnly(Scenario.STANDARD, "desktop-observe-only.v1"));
        assertEquals("budget_policy_not_supported", assertThrows(IllegalArgumentException.class,
                () -> fixture.budget().register(registration)).getMessage());
        verify(fixture.delegate(), never()).register(any());
    }

    /** 非测试夹具租户在登记和预留处均不能进入全局真实发送范围。 */
    @Test void rejectsUnauthorizedTenantBeforeDelegateMutation() throws Exception {
        var fixture = fixture(Category.BUSINESS_MODEL, 1000, 2048);
        var foreign = new RunRef(new TenantId("other"), "run:foreign");
        var registration = registration(foreign);
        assertThrows(IllegalStateException.class, () -> fixture.budget().register(registration));
        assertEquals("LIVE_MEMORY_CONTROLLED_EXECUTION_STOPPED",assertThrows(IllegalStateException.class,()->fixture.budget().reserve(new Demand(foreign, "foreign", "foreign", Category.BUSINESS_MODEL, Resources.call(Category.BUSINESS_MODEL, 1000, 2048), false))).getMessage());
        verify(fixture.delegate(), never()).register(any());
        verify(fixture.delegate(), never()).reserve(any());
    }

    /** 两条模型链的预留共用 100 万上限，不能分别耗尽同一预算。 */
    @Test void businessAndAuxiliaryReservationsShareOneMillionTokens() throws Exception {
        var fixture = fixture(Category.BUSINESS_MODEL, 599_000, 1000);
        assertInstanceOf(Reservation.Granted.class, fixture.budget().reserve(fixture.demand()));
        var auxiliary = new Demand(fixture.demand().run(), "auxiliary", "auxiliary", Category.AUXILIARY_MODEL, Resources.call(Category.AUXILIARY_MODEL, 449_000, 1000), false);
        var denied=assertInstanceOf(Reservation.Denied.class, fixture.budget().reserve(auxiliary));
        assertEquals(Optional.of(Dimension.TOTAL_TOKENS),denied.exhaustedDimension());
        assertEquals("execution_budget_root_total_tokens_limit",denied.reasonCode());
        assertEquals("LIVE_MEMORY_BUDGET_EXHAUSTED", fixture.budget().stopCode());
        assertEquals(600_000, fixture.budget().report().chargedOrReservedTokens());
        verify(fixture.delegate(), never()).reserve(auxiliary);
    }

    /** 时间由批次单调时钟控制；加入新租户不能重置两小时窗口。 */
    @Test void elapsedBatchTimeRejectsNewSendingAndCannotRestart() throws Exception {
        var fixture = fixture(Category.BUSINESS_MODEL, 1000, 2048);
        fixture.nanos().set(LiveMemoryBudgets.MAX_DURATION.toNanos());
        fixture.budget().authorizeTenant("another-sample");
        assertEquals("LIVE_MEMORY_CONTROLLED_EXECUTION_STOPPED",assertThrows(IllegalStateException.class,()->fixture.budget().reserve(fixture.demand())).getMessage());
        assertEquals("LIVE_MEMORY_TIME_EXHAUSTED", fixture.budget().stopCode());
        assertThrows(IllegalStateException.class, fixture.budget()::begin);
        verify(fixture.delegate(), never()).reserve(any());
    }

    /** 未报告 input/output 的已发送调用保持完整预留；恢复和新 attempt 都不能再发送。 */
    @Test void unknownUsageKeepsReservationAndNeverReplaysSentAttempt() throws Exception {
        var fixture = fixture(Category.BUSINESS_MODEL, 12_000, 2048);
        assertInstanceOf(Reservation.Granted.class, fixture.budget().reserve(fixture.demand()));
        assertTrue(fixture.budget().markSent(fixture.demand().run(), fixture.demand().attemptId()));
        fixture.budget().saveResult(fixture.demand().run(), fixture.demand().attemptId(), Usage.unreported(), "result:unknown");
        fixture.budget().recover(fixture.demand().run());
        assertEquals("LIVE_MEMORY_USAGE_UNKNOWN", fixture.budget().stopCode());
        assertEquals(14_048, fixture.budget().report().chargedOrReservedTokens());
        assertEquals(1, fixture.budget().report().unknownUsageAttempts());
        assertFalse(fixture.budget().markSent(fixture.demand().run(), fixture.demand().attemptId()));
        var next = new Demand(fixture.demand().run(), "next", "next", Category.AUXILIARY_MODEL, Resources.call(Category.AUXILIARY_MODEL, 100, 100), false);
        assertEquals("LIVE_MEMORY_CONTROLLED_EXECUTION_STOPPED",assertThrows(IllegalStateException.class,()->fixture.budget().reserve(next)).getMessage());
        verify(fixture.delegate(), times(1)).markSent(any(), anyString());
        verifyNoInteractions(fixture.recording());
    }

    /** 完整报告仅计 input+output，重复报告不得重复扣减；部分输出报告继续保守占用。 */
    @Test void reportedUsageReplacesUpperBoundExactlyOnce() throws Exception {
        var fixture = fixture(Category.AUXILIARY_MODEL, 12_000, 2048);
        fixture.budget().reserve(fixture.demand());
        fixture.budget().markSent(fixture.demand().run(), fixture.demand().attemptId());
        var usage = Usage.reported(OptionalLong.of(321), OptionalLong.of(87));
        fixture.budget().providerUsage(fixture.demand().run(), fixture.demand().attemptId(), usage);
        fixture.budget().providerUsage(fixture.demand().run(), fixture.demand().attemptId(), usage);
        assertEquals(408, fixture.budget().report().reportedTokens());
        assertEquals(408, fixture.budget().report().chargedOrReservedTokens());
        assertEquals(0, fixture.budget().report().unknownUsageAttempts());
        assertNull(fixture.budget().stopCode());
        verify(fixture.recording(), times(1)).beginThreadAttempt(any(), anyString());
    }

    /** 辅助提取没有业务审计回调；正式数据库input/output与terminal仍进入同一个权威账本。 */
    @Test void persistedAuxiliaryUsageIsIncludedWithoutBusinessAuditCallback() throws Exception {
        var fixture = fixture(Category.AUXILIARY_MODEL, 12_000, 2048,
                Usage.reported(OptionalLong.of(321), OptionalLong.of(87)), "SUCCEEDED");
        fixture.budget().reserve(fixture.demand());
        assertTrue(fixture.budget().runtimeCalls().isEmpty(), "未发送的预留不能被报告为模型调用");
        fixture.budget().markSent(fixture.demand().run(), fixture.demand().attemptId());
        var first = fixture.budget().runtimeCalls();
        assertEquals(1, first.size());
        var call = first.getFirst();
        assertEquals("fixture", call.tenantId());
        assertEquals("AUXILIARY_MODEL", call.category());
        assertEquals("attempt-budget", call.attemptId());
        assertEquals("SUCCEEDED", call.terminal());
        assertEquals(321L, call.usage().get("inputTokens"));
        assertEquals(87L, call.usage().get("outputTokens"));
        assertEquals(408, fixture.budget().report().reportedTokens());
        assertEquals(first, fixture.budget().runtimeCalls(), "重复快照不能重复累计同一attempt");
        verify(fixture.recording(), times(1)).terminalAttempt("attempt-budget", "SUCCEEDED");
    }

    /** 缺少输出时仅保留已知输入，账本不从总预留或wire推算缺失Runtime事实。 */
    @Test void partialRuntimeUsageKeepsMissingDimensionNullAndFullReservationCharged() throws Exception {
        var fixture = fixture(Category.BUSINESS_MODEL, 12_000, 2048);
        fixture.budget().reserve(fixture.demand()); fixture.budget().markSent(fixture.demand().run(), fixture.demand().attemptId());
        fixture.budget().saveResult(fixture.demand().run(), fixture.demand().attemptId(),
                Usage.reported(OptionalLong.of(321), OptionalLong.empty()), "result:partial");
        var call = fixture.budget().runtimeCalls().getFirst();
        assertEquals(321L, call.usage().get("inputTokens"));
        assertNull(call.usage().get("outputTokens"));
        assertEquals("STARTED", call.terminal());
        assertEquals(14_048, fixture.budget().report().chargedOrReservedTokens());
        assertEquals("LIVE_MEMORY_USAGE_UNKNOWN", fixture.budget().stopCode());
    }

    /** 供应商报告超过预留是预算证明失效，立即封门而不缩减事实。 */
    @Test void usageBeyondReservationStopsFurtherCalls() throws Exception {
        var fixture = fixture(Category.BUSINESS_MODEL, 100, 100);
        fixture.budget().reserve(fixture.demand()); fixture.budget().markSent(fixture.demand().run(), fixture.demand().attemptId());
        fixture.budget().providerUsage(fixture.demand().run(), fixture.demand().attemptId(), Usage.reported(OptionalLong.of(101), OptionalLong.of(100)));
        assertEquals("LIVE_MEMORY_USAGE_EXCEEDS_RESERVATION", fixture.budget().stopCode());
        assertFalse(fixture.budget().markSent(fixture.demand().run(), fixture.demand().attemptId()));
        assertEquals(201, fixture.budget().report().chargedOrReservedTokens());
    }

    /** 冻结数据集尺寸错误及重复场景编号在任何样本发送前拒绝。 */
    @Test void rejectsIncompleteOrDuplicateFrozenSuite() throws Exception {
        var json = new ObjectMapper();
        assertThrows(AssertionError.class, () -> MemoryLiveAcceptance.validateSuite(json.readTree("{\"schemaVersion\":1,\"repeats\":3,\"scenarios\":[]}")));
        var suite = json.createObjectNode().put("schemaVersion", 1).put("repeats", 3);
        var scenarios = suite.putArray("scenarios");
        for (int index = 1; index <= 24; index++) scenarios.addObject().put("id", String.format("M%02d", index)).putObject("expected");
        MemoryLiveAcceptance.validateSuite(suite);
        ((com.fasterxml.jackson.databind.node.ObjectNode) scenarios.get(23)).put("id", "M01");
        assertThrows(AssertionError.class, () -> MemoryLiveAcceptance.validateSuite(suite));
    }

    /** 只用本地mock供应正式查询结果；发送决策仍执行待评测的真实预算包装器。 */
    private Fixture fixture(Category category, long input, long output) throws Exception {
        return fixture(category, input, output, Usage.unreported(), null);
    }

    /** 持久化辅助回执与回调直接报告使用同一预算夹具，不提供真实网络客户端。 */
    private Fixture fixture(Category category, long input, long output, Usage persisted, String terminal) throws Exception {
        var run = new RunRef(new TenantId("fixture"), "run:budget");
        var demand = new Demand(run, "attempt-budget", "fingerprint-budget", category, Resources.call(category, input, output), false);
        var delegate = mock(ExecutionBudgetPort.class);
        when(delegate.find(any())).thenReturn(Optional.of(registration(run)));
        when(delegate.reserve(any())).thenAnswer(call -> new Reservation.Granted(new Permit(call.getArgument(0), AttemptState.RESERVED)));
        when(delegate.markSent(any(), anyString())).thenReturn(true);
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0), demand, persisted, terminal));
        var recording = mock(MemoryRecordingHttpClient.class); var nanos = new AtomicLong();
        var budget = new LiveMemoryBudgets(source, recording, delegate, Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC), nanos::get);
        budget.begin(); budget.authorizeTenant("fixture");
        return new Fixture(budget, delegate, recording, demand, nanos);
    }

    /** 正式根登记拥有足量资源；离线测试关心外层批次保护而非JDBC余额实现。 */
    private static Registration registration(RunRef run) {
        var resources = new Resources(1200, 1200, 1200, 1200, 1_000_000, 1_000_000, 1_000_000);
        return new Registration(run, run, Role.PRIMARY, new Policy.Enforced(
                new Limits(Scenario.STANDARD, resources, resources, resources, 7200, 7200, "offline-test-v1")));
    }

    /** SQL按正式固定投影返回数据，未知usage和terminal保留空，避免用默认0伪造已结算。 */
    private PreparedStatement statement(String sql, Demand demand, Usage persisted, String terminal) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(statement.executeQuery()).thenAnswer(ignored -> {
            if (sql.startsWith("SELECT fingerprint")) return result(List.of(List.of(demand.fingerprint(), demand.category().name(), demand.closing())));
            if (sql.startsWith("SELECT dimension,reserved")) {
                List<List<Object>> rows = new ArrayList<>();
                for (Dimension dimension : Dimension.values()) rows.add(List.of(dimension.name(), demand.resources().value(dimension)));
                return result(rows);
            }
            if (sql.startsWith("SELECT dimension,reported")) {
                List<List<Object>> rows = new ArrayList<>();
                if (persisted.inputTokens().isPresent()) rows.add(List.of("INPUT_TOKENS", persisted.inputTokens().getAsLong()));
                if (persisted.outputTokens().isPresent()) rows.add(List.of("OUTPUT_TOKENS", persisted.outputTokens().getAsLong()));
                return result(rows);
            }
            if (sql.startsWith("SELECT status FROM nw_external_invocations") && terminal != null) return result(List.of(List.of(terminal)));
            return result(List.of());
        });
        return statement;
    }

    /** 本地ResultSet具有独立游标，每次恢复读取相同不可变事实。 */
    private ResultSet result(List<List<Object>> rows) throws Exception {
        var result = mock(ResultSet.class); var row = new AtomicInteger(-1);
        when(result.next()).thenAnswer(ignored -> row.incrementAndGet() < rows.size());
        when(result.getString(anyInt())).thenAnswer(call -> String.valueOf(rows.get(row.get()).get(call.<Integer>getArgument(0) - 1)));
        when(result.getLong(anyInt())).thenAnswer(call -> ((Number) rows.get(row.get()).get(call.<Integer>getArgument(0) - 1)).longValue());
        when(result.getBoolean(anyInt())).thenAnswer(call -> rows.get(row.get()).get(call.<Integer>getArgument(0) - 1));
        return result;
    }

    /** 单条发送与可控时钟组成离线证据夹具，完全无网络发送权。 */
    private record Fixture(LiveMemoryBudgets budget, ExecutionBudgetPort delegate, MemoryRecordingHttpClient recording, Demand demand, AtomicLong nanos) { }
}
