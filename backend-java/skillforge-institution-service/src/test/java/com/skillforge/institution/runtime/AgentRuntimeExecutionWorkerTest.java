package com.skillforge.institution.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.ExecutionHostRegistry;
import com.skillforge.runtime.starter.ExecutionHostWorker;
import com.skillforge.runtime.starter.ExecutionHostScheduler;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 五十个真实平台线程槽位及持久到期补位的同步测试，不用延时猜测并发是否发生。 */
class AgentRuntimeExecutionWorkerTest {
    /** 空租户也计入每轮扫描上限，下一轮从上次文本ID继续，不能从第一页无限重扫。 */
    @Test
    void emptyTenantPagesStillBoundEachSweepToOneHundredTenants() {
        var ids = IntStream.range(0, 250).mapToObj(index -> String.format("%03d", index)).toList();
        var pages = new ArrayList<String>();
        var fixture = new Fixture((after, limit) -> {
            pages.add(after);
            assertThat(limit).isEqualTo(100);
            return ids.stream().filter(id -> id.compareTo(after) > 0).limit(limit).toList();
        });
        var scheduled = new ArrayList<Scheduled>();
        doAnswer(call -> { scheduled.add(new Scheduled(call.getArgument(0), call.getArgument(1))); return mock(ScheduledFuture.class); })
                .when(fixture.scheduler).scheduleControl(any(), any());
        try (var worker = fixture.worker) {
            fixture.shared.run(null); scheduled.getFirst().task().run();
            verify(fixture.store, times(100)).due(any(), eq(50));
            verify(fixture.store, times(100)).nextDueAt(any());
            scheduled.get(1).task().run(); scheduled.get(2).task().run();
            verify(fixture.store, times(200)).due(any(), eq(50));
            verify(fixture.store, times(200)).nextDueAt(any());
            assertThat(pages).containsExactly("", "099");
        }
    }

    /** 一个scope关闭后可重新观察，Worker关闭自动解除最后scope并拒绝新增观察。 */
    @Test
    void observationScopesAreExclusiveAndReleasedByScopeOrWorkerClose() {
        var fixture = new Fixture();
        var first = fixture.worker.observeQueue(fixture.tenant, 1);
        assertThatThrownBy(() -> fixture.worker.observeQueue(fixture.tenant, 1))
                .isInstanceOf(IllegalStateException.class).hasMessage("execution_queue_observation_already_active");
        first.close();
        var second = fixture.worker.observeQueue(fixture.tenant, 1);
        fixture.worker.close();
        second.received(new RunRef(fixture.tenant, "late"), 1);
        assertThat(second.samples()).isEmpty();
        assertThatThrownBy(() -> fixture.worker.observeQueue(fixture.tenant, 1))
                .isInstanceOf(IllegalStateException.class).hasMessage("execution_worker_closed");
        second.close();
    }

    /** claim前技术异常不改变持久due；本轮必须退出，不能立即补读同一Run再次尝试。 */
    @Test
    void technicalFailureBeforeClaimStopsInlineDrainEvenWhenRunRemainsDue() throws Exception {
        var fixture = new Fixture(); var released = new CountDownLatch(1);
        var run = new RunRef(fixture.tenant, "failed-before-claim");
        when(fixture.coordinator.drive(any())).thenThrow(new IllegalStateException("fixture_claim_database_failure"));
        when(fixture.store.due(eq(fixture.tenant), anyInt())).thenReturn(List.of(run));
        doAnswer(call -> { released.countDown(); return null; }).when(fixture.sources).end(run);
        try (var worker = fixture.worker) {
            assertThat(worker.wake(run)).isTrue(); assertThat(released.await(10, TimeUnit.SECONDS)).isTrue();
        }
        verify(fixture.coordinator, times(1)).drive(any());
        verify(fixture.store, never()).due(any(), anyInt());
        verify(fixture.scheduler, never()).scheduleControl(any(), any());
    }

    /** 启动只有一次即时恢复和一个五秒兜底，未来due另外只保留一个精确定时。 */
    @Test
    void startupUsesSingleFiveSecondFallbackAndSchedulesNearestDurableDue() {
        var fixture = new Fixture(); var scheduled = new ArrayList<Scheduled>();
        doAnswer(call -> { scheduled.add(new Scheduled(call.getArgument(0), call.getArgument(1))); return mock(ScheduledFuture.class); })
                .when(fixture.scheduler).scheduleControl(any(), any());
        when(fixture.store.nextDueAt(fixture.tenant)).thenReturn(Optional.of(Instant.now().plusSeconds(20)));
        try (var worker = fixture.worker) {
            fixture.shared.run(null); fixture.shared.run(null);
            assertThat(scheduled).hasSize(2);
            assertThat(scheduled.get(0).delay()).isZero();
            assertThat(scheduled.get(1).delay()).isEqualTo(Duration.ofSeconds(5));
            scheduled.getFirst().task().run();
            verify(fixture.store).due(fixture.tenant, 50); verify(fixture.store).nextDueAt(fixture.tenant);
            assertThat(scheduled).hasSize(3);
            assertThat(scheduled.get(2).delay()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(20));
        }
    }

    /** 首批五十个任务占满槽位，第五十一个仍在持久due；释放槽位后原线程直接补位。 */
    @Test
    void saturatedFiftyPlatformSlotsRefillFromDurableDueInReleasedThread() throws Exception {
        var fixture = new Fixture();
        var allStarted = new CountDownLatch(50); var releaseFirst = new CountDownLatch(1); var releaseRest = new CountDownLatch(1);
        var replacementStarted = new CountDownLatch(1);
        var threads = new ConcurrentHashMap<String, Thread>();
        var replacement = new RunRef(fixture.tenant, "run-50");
        var pending = new AtomicReference<List<RunRef>>(List.of());
        when(fixture.store.due(eq(fixture.tenant), anyInt())).thenAnswer(call -> pending.getAndSet(List.of()));
        when(fixture.coordinator.drive(any())).thenAnswer(call -> {
            ExecutionStore.Claim claim = call.getArgument(0); String id = claim.run().runId();
            fixture.claimed.get().accept(claim.run(), System.nanoTime());
            threads.put(id, Thread.currentThread());
            if (id.equals("run-50")) replacementStarted.countDown();
            else {
                allStarted.countDown();
                if (!((id.equals("run-0") ? releaseFirst : releaseRest).await(10, TimeUnit.SECONDS)))
                    throw new IllegalStateException("test_release_timeout");
            }
            return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED, Optional.empty(), "fixture_complete");
        });
        try (var observation = fixture.worker.observeQueue(fixture.tenant, 51); var worker = fixture.worker) {
            for (int index = 0; index < 50; index++) assertThat(worker.wake(new RunRef(fixture.tenant, "run-" + index))).isTrue();
            assertThat(allStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.wake(replacement)).isFalse();
            pending.set(List.of(replacement)); releaseFirst.countDown();
            assertThat(replacementStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(threads.get("run-50")).isSameAs(threads.get("run-0"));
            assertThat(threads.values()).allMatch(thread -> !thread.isVirtual());
            assertThat(observation.overflowed()).isFalse();
            assertThat(observation.samples()).hasSize(51).allMatch(sample -> sample.claimedNanos().isPresent());
            long queueNanos = observation.samples().stream().filter(sample -> sample.run().equals(replacement))
                    .findFirst().orElseThrow().queueNanos().orElseThrow();
            assertThat(queueNanos).isPositive();
            verify(fixture.coordinator, times(1)).drive(argThat(claim -> claim.run().equals(replacement)));
            releaseRest.countDown();
        } finally { releaseFirst.countDown(); releaseRest.countDown(); }
        verify(fixture.sources).begin(replacement); verify(fixture.sources).end(replacement);
    }

    /** 同一Run通知不会形成第二执行；已提交取消通过内存控制器直接传播。 */
    @Test
    void duplicateWakeDoesNotQueueAnotherDriveAndCancelUsesMemoryControl() throws Exception {
        var fixture = new Fixture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var run = new RunRef(fixture.tenant, "only-run");
        when(fixture.coordinator.drive(any())).thenAnswer(call -> {
            entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test_release_timeout");
            return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED, Optional.empty(), "fixture_complete");
        });
        try (var worker = fixture.worker) {
            assertThat(worker.wake(run)).isTrue(); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.wake(run)).isFalse(); worker.cancelled(run);
            verify(fixture.activity).cancelled(run); release.countDown();
        } finally { release.countDown(); }
        verify(fixture.coordinator, times(1)).drive(any());
        assertThat(fixture.worker.wake(run)).isFalse();
    }

    /** 取消后的领域清理同步发生在准确租户；清理暂时失败不撤销持久取消或内存停止信号。 */
    @Test
    void committedCancellationUsesTenantAndPreservesMemorySignalWhenCleanupFails() {
        var fixture = new Fixture(); var run = new RunRef(fixture.tenant, "cancelled-workflow");
        String previous = com.skillforge.common.context.TenantContext.getTenantId();
        doAnswer(call -> {
            verify(fixture.activity).cancelled(run);
            assertThat(com.skillforge.common.context.TenantContext.getTenantId()).isEqualTo(fixture.tenant.value());
            throw new IllegalStateException("cleanup_temporarily_unavailable");
        }).when(fixture.committed).accept(run);
        try (var worker = fixture.worker) { worker.cancelled(run); }
        verify(fixture.committed).accept(run);
        assertThat(com.skillforge.common.context.TenantContext.getTenantId()).isEqualTo(previous);
        verify(fixture.store, never()).due(any(), anyInt());
    }

    /** 父已取消或已过冻结deadline时，RPC不能进入共享child派发，更不能通过Supplier建新控制。 */
    @Test
    void cancelledOrExpiredProgramParentNeverEntersChildDrive() {
        var shared = mock(ExecutionHostWorker.class); var activity = mock(AgentRuntimeExecutionActivity.class);
        var coordinator = mock(ExecutionCoordinator.class);
        var parent = mock(com.skillforge.core.capability.connector.PlatformToolExecutionContext.class);
        var source = new ExecutionStore.ProgramChildSource(new com.skillforge.runtime.api.port.ActionRef(new TenantId("1"), "parent-tool"), "attempt", 1);
        var child = new RunRef(new TenantId("1"), "child");
        try (var worker = new AgentRuntimeExecutionWorker(shared, coordinator, activity,
                () -> { throw new AssertionError("关闭的父scope不能创建子控制"); })) {
            when(parent.cancellationSignal()).thenReturn(() -> true); when(parent.deadlineAt()).thenReturn(Instant.now().plusSeconds(30));
            assertThatThrownBy(() -> worker.driveProgramChild(parent, source, child)).hasMessage("program_scope_closed");
            when(parent.cancellationSignal()).thenReturn(() -> false); when(parent.deadlineAt()).thenReturn(Instant.now().minusSeconds(1));
            assertThatThrownBy(() -> worker.driveProgramChild(parent, source, child)).hasMessage("program_scope_closed");
            verifyNoInteractions(shared);
        }
    }

    /** 每个测试拥有独立Worker线程池，所有阻塞由latch释放后close等待静止。 */
    private static final class Fixture {
        private final TenantId tenant = new TenantId("1");
        @SuppressWarnings("unchecked")
        private final ExecutionCoordinator<AgentExecutionProgram.Materials> coordinator = mock(ExecutionCoordinator.class);
        private final AgentRuntimeExecutionSources sources = mock(AgentRuntimeExecutionSources.class);
        private final AgentRuntimeExecutionProjection projection = mock(AgentRuntimeExecutionProjection.class);
        private final ExecutionStore store = mock(ExecutionStore.class);
        private final AgentRuntimeExecutionActivity activity = mock(AgentRuntimeExecutionActivity.class);
        @SuppressWarnings("unchecked") private final Consumer<RunRef> committed = mock(Consumer.class);
        private final AtomicReference<BiConsumer<RunRef, Long>> claimed = new AtomicReference<>();
        private final AgentRuntimeExecutionWorker worker;
        private final ExecutionHostScheduler scheduler = mock(ExecutionHostScheduler.class);
        private final ExecutionHostWorker shared;

        /** 非运行定时器只隔离本测试的直接wake；另外的Activity测试核验生产心跳回调。 */
        private Fixture() { this((after, limit) -> after.isEmpty() ? List.of("1") : List.of()); }

        /** 可注入分页事实源以核验空页也有界；不创建生产测试分支。 */
        private Fixture(BiFunction<String, Integer, List<String>> discovery) {
            when(store.due(any(), anyInt())).thenReturn(List.of());
            when(store.nextDueAt(any())).thenReturn(Optional.empty());
            doReturn(mock(ScheduledFuture.class)).when(scheduler).scheduleControl(any(), any());
            doAnswer(call -> { claimed.set(call.getArgument(0)); return null; }).when(activity).observeClaims(any());
            when(store.load(any(RunRef.class))).thenAnswer(call -> {
                RunRef reference = call.getArgument(0);
                var identity = mock(com.skillforge.runtime.api.port.RunIdentity.class);
                when(identity.runRef()).thenReturn(reference);
                var persisted = mock(ExecutionStore.Run.class); when(persisted.identity()).thenReturn(identity);
                return Optional.of(persisted);
            });
            var host = new ExecutionHostRegistry.Host() {
                @Override public String id() { return "fixture"; }
                @Override public boolean owns(ExecutionStore.Run persisted) { return true; }
                @Override public ExecutionStore store() { return store; }
                @Override public void begin(RunRef run) { sources.begin(run); }
                @Override public void end(RunRef run) { sources.end(run); }
                @Override public ExecutionCoordinator.Outcome drive(ExecutionStore.Claim claim) { return coordinator.drive(claim); }
                @Override public void committed(ExecutionStore.Run persisted) { committed.accept(persisted.identity().runRef()); }
                @Override public void recover(TenantId tenant, int limit) { }
                @Override public void cancelled(RunRef run) { activity.cancelled(run); }
            };
            shared = new ExecutionHostWorker(new ExecutionHostRegistry(List.of(host)), discovery::apply, scheduler,
                    (owner, work) -> com.skillforge.common.context.TenantContext.runWithTenant(owner.value(), work),
                    "test-worker", Duration.ofSeconds(30), true);
            worker = new AgentRuntimeExecutionWorker(shared, coordinator, activity);
        }
    }

    /** 捕获的定时任务只描述控制回调，不表示排队Run。 */
    private record Scheduled(Runnable task, Duration delay) { }
}
