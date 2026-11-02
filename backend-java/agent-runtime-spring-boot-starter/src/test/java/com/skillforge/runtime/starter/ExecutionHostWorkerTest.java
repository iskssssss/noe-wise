package com.skillforge.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 共享调度的实际竞争拓扑：两个来源共二十槽，未知/冲突来源在任何claim之前拒绝。 */
class ExecutionHostWorkerTest {
    @Test void actualExecutionThreadOwnsJdbcObservationScope() throws Exception {
        var store = mock(ExecutionStore.class); var source = host("observed", store);
        var run = new RunRef(new TenantId("1"), "observed-run");
        var persistedRun = persisted(run);
        when(store.load(run)).thenReturn(Optional.of(persistedRun));
        when(source.owns(any())).thenReturn(true); when(store.due(any(), anyInt())).thenReturn(List.of());
        var dataSource = mock(javax.sql.DataSource.class); var connection = mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        var observed = AgentRuntimeJdbcObservation.observe(dataSource);
        var fields = new java.util.concurrent.CompletableFuture<java.util.Map<String, Object>>();
        when(source.drive(any())).thenAnswer(call -> {
            try (var borrowed = observed.getConnection()) {
                var current = AgentRuntimeJdbcObservation.class.getDeclaredField("CURRENT");
                current.setAccessible(true);
                var scope = (AgentRuntimeJdbcObservation.Scope) ((ThreadLocal<?>) current.get(null)).get();
                fields.complete(scope.fields());
            }
            return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED, Optional.empty(), "observed_complete");
        });
        try (var scheduler = new ExecutionHostScheduler();
             var worker = new ExecutionHostWorker(new ExecutionHostRegistry(List.of(source)), (after, limit) -> List.of(),
                     scheduler, (tenant, work) -> work.run(), "observed", Duration.ofSeconds(30), false)) {
            assertThat(worker.wake(run)).isTrue();
            assertThat(fields.get(10, TimeUnit.SECONDS)).containsEntry("connections", "1")
                    .containsEntry("jdbcScope", "current-thread-inclusive");
        }
    }

    @Test void workerInstancesHaveDistinctPhysicalLeaseOwnersWithTheConfiguredWorkerPrefix() throws Exception {
        var tenant = new TenantId("1"); var store = mock(ExecutionStore.class); var source = host("fixture", store);
        when(store.load(any(RunRef.class))).thenAnswer(call -> Optional.of(persisted(call.getArgument(0))));
        when(source.owns(any())).thenReturn(true); when(store.due(any(), anyInt())).thenReturn(List.of());
        when(store.nextDueAt(tenant)).thenReturn(Optional.empty());
        var scheduler = mock(ExecutionHostScheduler.class);
        when(scheduler.scheduleControl(any(), any())).thenReturn(mock(ScheduledFuture.class));
        var claims = java.util.Collections.synchronizedList(new java.util.ArrayList<String>()); var entered = new CountDownLatch(2);
        when(source.drive(any())).thenAnswer(call -> {
            claims.add(((ExecutionStore.Claim) call.getArgument(0)).workerId()); entered.countDown();
            return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED, Optional.empty(), "fixture_complete");
        });
        var properties = new AgentRuntimeProperties(); properties.setWorkerId("stable-worker");
        var configuration = new AgentRuntimeAutoConfiguration(); var registry = new ExecutionHostRegistry(List.of(source));
        try (var first = configuration.executionHostWorker(registry, (after, limit) -> List.of("1"), scheduler, (owner, work) -> work.run(), properties);
             var second = configuration.executionHostWorker(registry, (after, limit) -> List.of("1"), scheduler, (owner, work) -> work.run(), properties)) {
            assertThat(first.wake(new RunRef(tenant, "first"))).isTrue();
            assertThat(second.wake(new RunRef(tenant, "second"))).isTrue();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(claims).hasSize(2).doesNotHaveDuplicates().allMatch(id -> id.startsWith("stable-worker:execution:"));
            assertThat(properties.getWorkerId()).isEqualTo("stable-worker");
        }
    }

    @Test
    void unknownAndOverlappingPersistedOriginsCannotChooseAHost() {
        var store = mock(ExecutionStore.class); var persisted = mock(ExecutionStore.Run.class);
        var first = host("institution", store); var second = host("system", store);
        var registry = new ExecutionHostRegistry(List.of(first, second));
        assertThatThrownBy(() -> registry.host(persisted)).hasMessage("execution_host_origin_unknown");
        when(first.owns(persisted)).thenReturn(true); when(second.owns(persisted)).thenReturn(true);
        assertThatThrownBy(() -> registry.host(persisted)).hasMessage("execution_host_origin_ambiguous");
    }

    @Test
    void twoHostsShareTwentyPlatformSlotsAndReleasedThreadDrainsThePersistedTwentyFirst() throws Exception {
        var tenant = new TenantId("1"); var store = mock(ExecutionStore.class);
        var institution = host("institution", store); var system = host("system", store);
        when(store.load(any(RunRef.class))).thenAnswer(call -> Optional.of(persisted(call.getArgument(0))));
        when(institution.owns(any())).thenAnswer(call -> ((ExecutionStore.Run) call.getArgument(0)).identity().runRef().runId().startsWith("institution:"));
        when(system.owns(any())).thenAnswer(call -> ((ExecutionStore.Run) call.getArgument(0)).identity().runRef().runId().startsWith("system:"));
        var scheduler = mock(ExecutionHostScheduler.class);
        when(scheduler.scheduleControl(any(), any())).thenReturn(mock(ScheduledFuture.class));
        var pending = new AtomicReference<List<RunRef>>(List.of());
        when(store.due(eq(tenant), anyInt())).thenAnswer(call -> pending.getAndSet(List.of()));
        when(store.nextDueAt(tenant)).thenReturn(Optional.empty());
        var entered = new CountDownLatch(20); var releaseFirst = new CountDownLatch(1); var releaseRest = new CountDownLatch(1);
        var replacementEntered = new CountDownLatch(1); var threads = new ConcurrentHashMap<String, Thread>();
        var active = new AtomicInteger(); var peak = new AtomicInteger(); var replacement = new RunRef(tenant, "system:replacement");
        org.mockito.stubbing.Answer<ExecutionCoordinator.Outcome> actualDrive = call -> {
            var claim = (ExecutionStore.Claim) call.getArgument(0); String id = claim.run().runId();
            threads.put(id, Thread.currentThread()); peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (id.equals(replacement.runId())) replacementEntered.countDown();
                else {
                    entered.countDown();
                    if (!(id.equals("institution:0") ? releaseFirst : releaseRest).await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("test_release_timeout");
                }
                return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED, Optional.empty(), "fixture_complete");
            } finally { active.decrementAndGet(); }
        };
        when(institution.drive(any())).thenAnswer(actualDrive); when(system.drive(any())).thenAnswer(actualDrive);
        try (var worker = new ExecutionHostWorker(new ExecutionHostRegistry(List.of(institution, system)),
                (after, limit) -> List.of("1"), scheduler, (owner, work) -> work.run(), "shared-worker", Duration.ofSeconds(30), true)) {
            try {
                for (int index = 0; index < 10; index++) {
                    assertThat(worker.wake(new RunRef(tenant, "institution:" + index))).isTrue();
                    assertThat(worker.wake(new RunRef(tenant, "system:" + index))).isTrue();
                }
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(worker.wake(replacement)).isFalse();
                pending.set(List.of(replacement)); releaseFirst.countDown();
                assertThat(replacementEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(peak.get()).isEqualTo(20);
                assertThat(threads.values()).allMatch(thread -> !thread.isVirtual());
                assertThat(threads.get(replacement.runId())).isSameAs(threads.get("institution:0"));
            } finally { releaseFirst.countDown(); releaseRest.countDown(); }
        }
    }

    private static ExecutionHostRegistry.Host host(String id, ExecutionStore store) {
        var host = mock(ExecutionHostRegistry.Host.class); when(host.id()).thenReturn(id); when(host.store()).thenReturn(store); return host;
    }

    /** 二十父都持槽时，每个RPC子都在原父线程完成；不存在排队等第21物理槽的循环等待。 */
    @Test
    void twentyParentsEachDriveAnAdmittedProgramChildOnTheirOwnPhysicalThread() throws Exception {
        var tenant = new TenantId("1"); var store = mock(ExecutionStore.class); var host = host("fixture", store);
        var saved = new ConcurrentHashMap<RunRef, ExecutionStore.Run>();
        var sources = new ConcurrentHashMap<RunRef, ExecutionStore.ProgramChildSource>();
        var actions = new ConcurrentHashMap<com.skillforge.runtime.api.port.ActionRef, ExecutionStore.Action>();
        var attempts = new ConcurrentHashMap<String, ExecutionStore.Attempt>();
        for (int index = 0; index < 20; index++) {
            var parentRef = new RunRef(tenant, "parent:" + index); var parent = persisted(parentRef);
            var actionRef = new com.skillforge.runtime.api.port.ActionRef(tenant, "parent-tool:" + index);
            var source = new ExecutionStore.ProgramChildSource(actionRef, "parent-attempt:" + index, 1);
            when(parent.status()).thenReturn(com.skillforge.runtime.api.RunStatus.RUNNING);
            when(parent.phase()).thenReturn(ExecutionStore.Phase.AWAITING_RESULT); when(parent.fence()).thenReturn(1L);
            when(parent.currentAction()).thenReturn(Optional.of(actionRef));
            when(parent.lease()).thenReturn(Optional.of(new com.skillforge.runtime.api.port.LeaseFence(tenant, "claim:" + index,
                    "shared-worker", 1, java.time.Instant.now().plusSeconds(30))));
            var action = mock(ExecutionStore.Action.class); when(action.kind()).thenReturn(ExecutionStore.Kind.TOOL);
            when(action.run()).thenReturn(parentRef); when(action.currentAttempt()).thenReturn(source.attemptId());
            var attempt = mock(ExecutionStore.Attempt.class); when(attempt.action()).thenReturn(actionRef);
            when(attempt.fence()).thenReturn(1L); when(attempt.result()).thenReturn(Optional.empty());
            saved.put(parentRef, parent); saved.put(new RunRef(tenant, "child:" + index), persisted(new RunRef(tenant, "child:" + index)));
            sources.put(parentRef, source); actions.put(actionRef, action); attempts.put(source.attemptId(), attempt);
        }
        when(store.load(any(RunRef.class))).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        when(store.action(any())).thenAnswer(call -> Optional.ofNullable(actions.get(call.getArgument(0))));
        when(store.attempt(any(), any())).thenAnswer(call -> Optional.ofNullable(attempts.get(call.getArgument(1))));
        when(host.owns(any())).thenReturn(true); when(store.due(any(), anyInt())).thenReturn(List.of()); when(store.nextDueAt(any())).thenReturn(Optional.empty());
        var scheduler = mock(ExecutionHostScheduler.class); when(scheduler.scheduleControl(any(), any())).thenReturn(mock(ScheduledFuture.class));
        var parentsEntered = new CountDownLatch(20); var release = new CountDownLatch(1); var childrenCompleted = new CountDownLatch(20);
        var parentThreads = new ConcurrentHashMap<String, Thread>(); var childThreads = new ConcurrentHashMap<String, Thread>();
        var controller = new AtomicReference<ExecutionHostWorker>();
        when(host.drive(any())).thenAnswer(call -> {
            var claim = (ExecutionStore.Claim) call.getArgument(0); String id = claim.run().runId();
            if (id.startsWith("child:")) {
                childThreads.put(id.substring(6), Thread.currentThread());
                assertThatThrownBy(() -> controller.get().driveProgramChild(saved.get(claim.run()).identity(),
                        sources.get(new RunRef(tenant, "parent:" + id.substring(6))), claim.run())).hasMessage("execution_inline_parent_slot_required");
                childrenCompleted.countDown();
            } else {
                parentThreads.put(id.substring(7), Thread.currentThread()); parentsEntered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test_release_timeout");
                controller.get().driveProgramChild(saved.get(claim.run()).identity(), sources.get(claim.run()), new RunRef(tenant, "child:" + id.substring(7)));
            }
            return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED, Optional.empty(), "fixture_complete");
        });
        try (var worker = new ExecutionHostWorker(new ExecutionHostRegistry(List.of(host)), (after, limit) -> List.of("1"), scheduler,
                (owner, work) -> work.run(), "shared-worker", Duration.ofSeconds(30), true)) {
            controller.set(worker);
            try {
                for (int index = 0; index < 20; index++) assertThat(worker.wake(new RunRef(tenant, "parent:" + index))).isTrue();
                assertThat(parentsEntered.await(10, TimeUnit.SECONDS)).isTrue(); release.countDown();
                assertThat(childrenCompleted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(parentThreads).hasSize(20); assertThat(childThreads).hasSize(20);
                for (String key : parentThreads.keySet()) assertThat(childThreads.get(key)).isSameAs(parentThreads.get(key));
                assertThat(parentThreads.values()).allMatch(thread -> !thread.isVirtual());
            } finally { release.countDown(); }
        }
    }
    private static ExecutionStore.Run persisted(RunRef reference) {
        var identity = mock(RunIdentity.class); when(identity.runRef()).thenReturn(reference);
        var persisted = mock(ExecutionStore.Run.class); when(persisted.identity()).thenReturn(identity); return persisted;
    }
}
