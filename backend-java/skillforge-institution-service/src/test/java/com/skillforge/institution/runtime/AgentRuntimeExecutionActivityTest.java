package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 活动控制使用确定回调核验单一租约、内存停止和作用域释放，不把Mock当作真实数据库证据。 */
class AgentRuntimeExecutionActivityTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final RunRef RUN = new RunRef(TENANT, "execution-active");
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    @Test void heartbeatUsesTenSecondsForDefaultLeaseAndAnEarlierThirdForShortLeases() throws Exception {
        for (Duration lease : java.util.List.of(Duration.ofSeconds(30), Duration.ofMillis(300))) {
            var fixture = new Fixture(lease);
            try {
                long expected = lease.equals(Duration.ofSeconds(30)) ? Duration.ofSeconds(10).toNanos() : Duration.ofMillis(100).toNanos();
                verify(fixture.timer).scheduleWithFixedDelay(any(Runnable.class), eq(expected), eq(expected), eq(TimeUnit.NANOSECONDS));
            } finally { fixture.activity.close(); }
        }
    }
    private static final Duration LEASE = Duration.ofSeconds(30);

    /** 节点按服务端精确配置进入自身frame，共用控制器且释放只执行一次。 */
    @Test
    void workflowNodeUsesSameActivityControlWithOwnFrameAndClosesExactlyOnce() throws Exception {
        var fixture = new Fixture(); var original = run(false, NOW.plusSeconds(30));
        var node = new ExecutionStore.Run(original.identity(), WorkflowNodeRequests.reference(RUN), original.status(), original.revision(),
                original.fence(), original.cancelled(), original.phase(), original.progress(), original.lease(), original.currentAction(),
                original.currentBatch(), original.waiting(), original.actorVerificationRef(), original.nextDueAt(), original.createdAt(), original.updatedAt());
        var control = fixture.activity.open(node);
        var sequence = inOrder(fixture.nodes);
        sequence.verify(fixture.nodes).begin(RUN); sequence.verify(fixture.nodes).bindControl(RUN, control);
        verifyNoInteractions(fixture.sources);
        control.close(); control.close(); fixture.activity.close();
        sequence.verify(fixture.nodes).end(RUN); verifyNoMoreInteractions(fixture.nodes);
    }

    /** 一个Run加载预算基线期间，另一已claim Run可并行加载，生命周期锁不能串行数据库读取。 */
    @Test
    void slowBaselineForOneRunDoesNotSerializeOtherClaimedRuns() throws Exception {
        var fixture = new Fixture(); var firstEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1); var secondRun = new RunRef(TENANT, "execution-second");
        doAnswer(call -> {
            RunRef reference = call.getArgument(0);
            if (reference.equals(RUN)) {
                firstEntered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test_baseline_release_timeout");
            } else secondEntered.countDown();
            return null;
        }).when(fixture.sources).bindControl(any(), any());
        var slots = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("control-baseline-test-", 0).factory());
        try {
            var first = slots.submit(() -> fixture.activity.open(run(false, NOW.plusSeconds(30))));
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = slots.submit(() -> fixture.activity.open(run(secondRun, false, NOW.plusSeconds(30))));
            assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
            second.get(10, TimeUnit.SECONDS).close(); release.countDown(); first.get(10, TimeUnit.SECONDS).close();
        } finally {
            release.countDown(); slots.shutdownNow(); assertThat(slots.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            fixture.activity.close();
        }
    }

    /** 续租返回新期限但保持同一fence；getter不再调用存储，释放后旧定时回调没有副作用。 */
    @Test
    void oneControlRenewsSameLeaseAndClosesBeforeAnyFurtherActivity() throws Exception {
        var fixture = new Fixture(); var initial = run(false, NOW.plusSeconds(30));
        var extended = run(false, NOW.plusSeconds(35));
        when(fixture.store.renew(eq(RUN), any(), eq(LEASE))).thenAnswer(call -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT.value()); return extended;
        });
        var control = fixture.activity.open(initial);
        verify(fixture.sources).bindControl(RUN, control);
        fixture.tick.get().run(); fixture.tick.get().run();
        verify(fixture.store).renew(RUN, initial.lease().orElseThrow(), LEASE);
        verify(fixture.store).renew(RUN, extended.lease().orElseThrow(), LEASE);
        assertThat(control.cancelled()).isFalse(); assertThat(control.ownershipLost()).isFalse();
        assertThat(TenantContext.getTenantId()).isNull();
        control.close(); fixture.tick.get().run();
        verifyNoMoreInteractions(fixture.store);
        fixture.activity.close();
        verify(fixture.future).cancel(false); verify(fixture.timer).shutdownNow();
        verify(fixture.timer).awaitTermination(30, TimeUnit.SECONDS);
    }

    /** 本进程持久取消入口只改变内存信号，之后不再续租或从true恢复为false。 */
    @Test
    void localCancelIsImmediateAndDoesNotNeedAnotherDatabaseGetter() throws Exception {
        var fixture = new Fixture(); var control = fixture.activity.open(run(false, NOW.plusSeconds(30)));
        fixture.activity.cancelled(RUN);
        assertThat(control.cancelled()).isTrue(); assertThat(control.ownershipLost()).isFalse();
        fixture.tick.get().run(); assertThat(control.cancelled()).isTrue();
        verifyNoInteractions(fixture.store); control.close(); fixture.activity.close();
    }

    /** 跨进程取消由同一租约事务返回，不能被误当作普通成功续租继续发送。 */
    @Test
    void heartbeatObservesDurableCancellationAndKeepsPureMemorySignal() throws Exception {
        var fixture = new Fixture(); var initial = run(false, NOW.plusSeconds(30));
        when(fixture.store.renew(RUN, initial.lease().orElseThrow(), LEASE)).thenReturn(run(true, NOW.plusSeconds(30)));
        var control = fixture.activity.open(initial); fixture.tick.get().run();
        assertThat(control.cancelled()).isTrue(); assertThat(control.ownershipLost()).isFalse();
        fixture.tick.get().run(); verify(fixture.store, times(1)).renew(any(), any(), any());
        control.close(); fixture.activity.close();
    }

    /** 无法确认当前租约即停止新派发，原Provider真实结果的保存不由控制器重放或伪造。 */
    @Test
    void failedRenewalLosesOwnershipOnceWithoutRetryOrReplay() throws Exception {
        var fixture = new Fixture(); var initial = run(false, NOW.plusSeconds(30));
        when(fixture.store.renew(any(), any(), any())).thenThrow(new IllegalStateException("execution_lease_changed"));
        var control = fixture.activity.open(initial); fixture.tick.get().run();
        assertThat(control.ownershipLost()).isTrue(); assertThat(control.cancelled()).isFalse();
        fixture.tick.get().run(); verify(fixture.store, times(1)).renew(any(), any(), any());
        control.close(); fixture.activity.close();
    }

    /** 构造当前合法运行事实，不创建任何发送许可或数据库租约。 */
    private static ExecutionStore.Run run(boolean cancelled, Instant until) {
        return run(RUN, cancelled, until);
    }

    /** 并发测试使用独立Run身份，其他合法租约字段保持同一测试基线。 */
    private static ExecutionStore.Run run(RunRef run, boolean cancelled, Instant until) {
        var owner = SessionOwner.user(new SessionRef(TENANT, "active-session"), new AgentId("active-agent"), new EndUserId(7));
        var identity = new RunIdentity(owner, new AgentId((owner).target().id()), new InputId("active-input"), run, new TraceEnvelope("active-trace", "active-span", new InputId("active-input"), Optional.empty()), new ResultRef("active-actor"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("active-input")).value()));
        return new ExecutionStore.Run(identity, new ResultRef("active-config"), cancelled ? RunStatus.CANCELLED : RunStatus.RUNNING,
                2, 1, cancelled, cancelled ? ExecutionStore.Phase.FINISHED : ExecutionStore.Phase.READY,
                new ExecutionStore.Progress(0, 0, 0, new ResultRef("active-context")),
                cancelled ? Optional.empty() : Optional.of(new LeaseFence(TENANT, "active-claim", "active-worker", 1, until)),
                Optional.empty(), Optional.empty(), Optional.empty(), new ResultRef("active-actor"), Optional.empty(), NOW, NOW);
    }

    /** 定时器只捕获实际生产回调，由测试明确触发，不依赖sleep或全局时钟修改。 */
    private static final class Fixture {
        private final ExecutionStore store = mock(ExecutionStore.class);
        private final AgentRuntimeExecutionSources sources = mock(AgentRuntimeExecutionSources.class);
        private final AgentRuntimeWorkflowNodeExecutions nodes = mock(AgentRuntimeWorkflowNodeExecutions.class);
        private final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
        private final AtomicReference<Runnable> tick = new AtomicReference<>();
        private final AgentRuntimeExecutionActivity activity;

        /** 独立定时器作用域由各测试关闭，并确认所属执行器已静止。 */
        private Fixture() throws Exception { this(LEASE); }
        private Fixture(Duration lease) throws Exception {
            doAnswer(call -> { tick.set(call.getArgument(0)); return future; }).when(timer)
                    .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS));
            when(timer.awaitTermination(anyLong(), any())).thenReturn(true);
            activity = new AgentRuntimeExecutionActivity(store, sources, nodes, lease, timer);
        }
    }
}
