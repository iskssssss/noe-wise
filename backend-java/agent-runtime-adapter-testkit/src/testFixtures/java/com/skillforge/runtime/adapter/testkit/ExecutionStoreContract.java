package com.skillforge.runtime.adapter.testkit;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;

/** 当前 typed ExecutionStore 的最小共享契约；宿主夹具保留身份、schema 与连接所有权。 */
public abstract class ExecutionStoreContract {
    protected abstract ExecutionStore firstStore();
    protected abstract ExecutionStore rebuiltStore();
    protected abstract ExecutionStore.Admission newAdmission(String suffix);

    @org.junit.jupiter.api.Test
    public final void publishedAdmissionReconstructionContract() {
        admissionSurvivesReconstruction(firstStore(), rebuiltStore(), newAdmission("reconstruction"));
    }
    @org.junit.jupiter.api.Test
    public final void publishedMaterialOrderingContract() {
        materialsKeepRequestedOrder(firstStore(), newAdmission("materials"));
    }
    @org.junit.jupiter.api.Test
    public final void publishedClaimExclusivityContract() {
        liveClaimIsExclusive(firstStore(), rebuiltStore(), newAdmission("claim"));
    }

    /** 同一输入只准入一次；重建 store 后恢复完整原始输入与身份。 */
    public static void admissionSurvivesReconstruction(ExecutionStore first, ExecutionStore rebuilt,
            ExecutionStore.Admission command) {
        var admitted = first.admit(command);
        assertTrue(admitted.created());
        assertFalse(rebuilt.admit(command).created());
        assertEquals(admitted.run(), rebuilt.load(command.run().runRef()).orElseThrow());
        assertEquals(command.input(), rebuilt.originalInput(command.run().runRef()).orElseThrow());
    }

    /** 一次批读保留输入顺序、重复引用和缺失位置，不能按 SQL 返回顺序拼接。 */
    public static void materialsKeepRequestedOrder(ExecutionStore store, ExecutionStore.Admission command) {
        store.admit(command);
        var input = command.input(); var context = command.context();
        assertEquals(java.util.List.of(java.util.Optional.of(context), java.util.Optional.empty(),
                        java.util.Optional.of(input), java.util.Optional.of(context)),
                store.materials(command.run().runRef().tenantId(), java.util.List.of(context.reference(),
                        new com.skillforge.runtime.api.ResultRef("contract-missing-material"), input.reference(), context.reference())));
        assertEquals(java.util.List.of(), store.materials(command.run().runRef().tenantId(), java.util.List.of()));
    }

    /** 同一未到期租约只能被一个 worker 领取，第二个 store 不能凭读取获得执行权。 */
    public static void liveClaimIsExclusive(ExecutionStore first, ExecutionStore rebuilt,
            ExecutionStore.Admission command) {
        first.admit(command);
        var run = command.run().runRef();
        assertTrue(first.claim(new ExecutionStore.Claim(run, "contract-a", "contract-claim-a", Duration.ofMinutes(1))).isPresent());
        assertTrue(rebuilt.claim(new ExecutionStore.Claim(run, "contract-b", "contract-claim-b", Duration.ofMinutes(1))).isEmpty());
    }
    /** 根与正文叶同事务落库；重建、保序批读和同引用冲突均遵循原派发边界。 */
    public static void requestPartsAreAtomicAndExact(ExecutionStore store, ExecutionStore rebuilt,
            ExecutionStore.Dispatch first, ExecutionStore.Dispatch conflict) {
        var leaf = new ExecutionStore.Material(new ResultRef(first.attemptId() + "-leaf"), new TypedPayload("test-part-v1", "{\"value\":1}"));
        var other = new ExecutionStore.Material(new ResultRef(first.attemptId() + "-other"), new TypedPayload("test-part-v1", "{\"value\":2}"));
        var command = parts(first, List.of(leaf, other));
        assertInstanceOf(ExecutionStore.DispatchResult.Registered.class, store.dispatch(command));
        assertInstanceOf(ExecutionStore.DispatchResult.Existing.class, rebuilt.dispatch(command));
        assertEquals(List.of(Optional.of(other), Optional.of(leaf), Optional.of(other), Optional.empty()),
                rebuilt.materials(first.run().tenantId(), List.of(other.reference(), leaf.reference(), other.reference(), new ResultRef("missing-part"))));
        assertEquals(first.action().request(), rebuilt.material(first.run().tenantId(), first.action().request().reference()).orElseThrow());
        assertTrue(rebuilt.materials(first.run().tenantId(), List.of()).isEmpty());
        var changed = new ExecutionStore.Material(leaf.reference(), new TypedPayload("test-part-v1", "{\"value\":9}"));
        assertThrows(RuntimeException.class, () -> rebuilt.dispatch(parts(first, List.of(changed, other))));
        var fresh = new ExecutionStore.Material(new ResultRef(conflict.attemptId() + "-fresh"), new TypedPayload("test-part-v1", "{\"fresh\":true}"));
        var before = store.load(conflict.run()).orElseThrow();
        assertThrows(RuntimeException.class, () -> store.dispatch(parts(conflict, List.of(fresh, changed))));
        assertEquals(before, rebuilt.load(conflict.run()).orElseThrow());
        assertEquals(List.of(Optional.empty(), Optional.empty()), rebuilt.materials(conflict.run().tenantId(), List.of(fresh.reference(), conflict.action().request().reference())));
        assertTrue(rebuilt.attempt(conflict.run(), conflict.attemptId()).isEmpty());
        assertEquals(leaf, rebuilt.material(first.run().tenantId(), leaf.reference()).orElseThrow());
    }

    /** 终态材料与事件精确重放，计数不变；不同证据、版本和普通终态语义分别核对。 */
    public static void terminalContextIsAtomicAndReplayExact(ExecutionStore store, ExecutionStore rebuilt,
            ExecutionStore.Admission admission) {
        var run = store.admit(admission).run();
        var claimed = store.claim(new ExecutionStore.Claim(run.identity().runRef(), "terminal-worker", "terminal-claim", Duration.ofMinutes(1))).orElseThrow();
        var context = new ExecutionStore.Material(new ResultRef(run.identity().runRef().runId() + "-terminal-context"),
                new TypedPayload("terminal-context-v1", "{\"private\":true}"));
        var event = new ExecutionStore.Event(new com.skillforge.runtime.api.EventId(run.identity().runRef().runId() + "-terminal"),
                "terminal", new TypedPayload("terminal-event-v1", "{}"));
        var finish = new ExecutionStore.Finish(run.identity().runRef(), claimed.revision(), claimed.lease().orElseThrow(),
                com.skillforge.runtime.api.RunStatus.TERMINATED, List.of(event), Optional.of(context));
        var stale = new ExecutionStore.Finish(finish.run(), finish.revision() + 1, finish.lease(), finish.status(), finish.events(), finish.terminalContext());
        assertThrows(ExecutionStore.Rejection.class, () -> store.finish(stale));
        assertEquals(claimed, rebuilt.load(finish.run()).orElseThrow());
        assertTrue(rebuilt.material(finish.run().tenantId(), context.reference()).isEmpty());
        var stopped = store.finish(finish);
        assertEquals(claimed.progress().decisions(), stopped.progress().decisions());
        assertEquals(claimed.progress().toolCalls(), stopped.progress().toolCalls());
        assertEquals(claimed.progress().knowledgeCalls(), stopped.progress().knowledgeCalls());
        assertEquals(context.reference(), stopped.progress().context());
        assertEquals(context, rebuilt.material(finish.run().tenantId(), context.reference()).orElseThrow());
        assertEquals(stopped, rebuilt.finish(finish));
        assertEquals(List.of(event), rebuilt.events(admission.run().owner().sessionRef(), 1, 100).stream().map(ExecutionStore.SavedEvent::event).toList());
        var changed = new ExecutionStore.Material(context.reference(), new TypedPayload("terminal-context-v1", "{\"private\":false}"));
        assertThrows(ExecutionStore.Rejection.class, () -> rebuilt.finish(new ExecutionStore.Finish(finish.run(), finish.revision(), finish.lease(), finish.status(), finish.events(), Optional.of(changed))));
        assertThrows(ExecutionStore.Rejection.class, () -> rebuilt.finish(new ExecutionStore.Finish(finish.run(), finish.revision(), finish.lease(), finish.status(), List.of(), finish.terminalContext())));
        assertThrows(ExecutionStore.Rejection.class, () -> rebuilt.finish(stale));
        assertEquals(stopped, rebuilt.finish(new ExecutionStore.Finish(finish.run(), finish.revision(), finish.lease(), finish.status(), List.of())));
    }

    /** 两个不同终态材料竞争同一 revision 时，只允许一个事实提交。 */
    public static void competingTerminalContextsCommitOnce(ExecutionStore first, ExecutionStore second,
            ExecutionStore.Admission admission) throws Exception {
        var run = first.admit(admission).run();
        var claimed = first.claim(new ExecutionStore.Claim(run.identity().runRef(), "race-worker", "race-claim", Duration.ofMinutes(1))).orElseThrow();
        var a = new ExecutionStore.Material(new ResultRef(run.identity().runRef().runId() + "-race-a"), new TypedPayload("terminal-context-v1", "{}"));
        var b = new ExecutionStore.Material(new ResultRef(run.identity().runRef().runId() + "-race-b"), new TypedPayload("terminal-context-v1", "{}"));
        var one = new ExecutionStore.Finish(run.identity().runRef(), claimed.revision(), claimed.lease().orElseThrow(), com.skillforge.runtime.api.RunStatus.TERMINATED, List.of(), Optional.of(a));
        var two = new ExecutionStore.Finish(one.run(), one.revision(), one.lease(), one.status(), List.of(), Optional.of(b));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var f1 = executor.submit(() -> { start.await(); try { first.finish(one); return true; } catch (ExecutionStore.Rejection conflict) { return false; } });
            var f2 = executor.submit(() -> { start.await(); try { second.finish(two); return true; } catch (ExecutionStore.Rejection conflict) { return false; } });
            start.countDown();
            assertNotEquals(f1.get(), f2.get());
        }
        var stopped = first.load(one.run()).orElseThrow();
        assertEquals(claimed.revision() + 1, stopped.revision());
        var present = first.materials(one.run().tenantId(), List.of(a.reference(), b.reference()));
        assertNotEquals(present.get(0).isPresent(), present.get(1).isPresent());
        assertEquals(present.stream().flatMap(Optional::stream).findFirst().orElseThrow().reference(), stopped.progress().context());
    }

    private static ExecutionStore.Dispatch parts(ExecutionStore.Dispatch command, List<ExecutionStore.Material> parts) {
        var original = command.action();
        var prepared = new ExecutionStore.Prepared(original.action(), original.ordinal(), original.kind(), original.target(), original.capability(), original.request(), original.fingerprint(), parts);
        return new ExecutionStore.Dispatch(command.run(), command.revision(), command.lease(), prepared, command.attemptId(), command.budget(), command.actor(), command.continuation(), command.events());
    }

}
