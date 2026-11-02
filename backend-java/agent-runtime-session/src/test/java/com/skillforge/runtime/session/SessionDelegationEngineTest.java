package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import static com.skillforge.runtime.session.SessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.SessionDelegations;
import com.skillforge.runtime.session.spi.SessionDelegations.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 委派状态语义、计划约束和恢复重放的定向生命周期验证，不执行业务模型。 */
class SessionDelegationEngineTest {
    /** 默认委派一次决策登记等待，重建后从真实结果恢复；未知结果不能变成完成。 */
    @Test void defaultDelegateWaitsDurablyAndResumePreservesUnknownResult() {
        var f = new Fixture();
        var command = DelegationCommand.delegate(f.target.handle(), "检查真实结果", false);
        var first = f.engine.execute(IDENTITY, "single-delegate", command);
        assertEquals("waiting", first.value().get("status"));
        assertEquals("delegation-wait:single-delegate", first.waiting().orElseThrow().id());
        assertEquals(1, f.entries.size());
        assertEquals(1, f.waits.size());
        f.engine = new SessionDelegationEngine(f.store, f.host, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(first, f.engine.execute(IDENTITY, "single-delegate", command));
        f.engine.tick(TENANT, 10);
        var entry = f.entries.values().iterator().next();
        f.completed.put(entry.id(), new Result(State.FAILED, "部分结果", "external_effect_outcome_unknown", 17, List.of("real-evidence")));
        f.engine.tick(TENANT, 10);
        f.plan = true;
        f.revoked = true;
        var result = f.engine.resume(IDENTITY, "single-delegate");
        assertTrue(result.waiting().isEmpty());
        assertTrue(RuntimeJson.writeCanonical(result.value()).contains("external_effect_outcome_unknown"));
        assertEquals(0, f.started);
        assertEquals(1, f.entries.size());
        f.readRevoked = true;
        assertThrows(SecurityException.class, () -> f.engine.resume(IDENTITY, "single-delegate"));
    }

    /** Entry 已持久化而 Wait 写失败时，原决策重放补登记，不重复准入子任务。 */
    @Test void delegateReplayRepairsWaitRegistrationAfterCrashWindow() {
        var f = new Fixture();
        var command = DelegationCommand.delegate(f.target.handle(), "独立调查", false);
        f.failWaitRegistration = true;
        assertThrows(IllegalStateException.class, () -> f.engine.execute(IDENTITY, "crash-window", command));
        assertEquals(1, f.entries.size());
        assertTrue(f.waits.isEmpty());
        var original = f.entries.values().iterator().next();
        f.engine = new SessionDelegationEngine(f.store, f.host, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        var replay = f.engine.execute(IDENTITY, "crash-window", command);
        assertEquals(List.of(original.id()), replay.waiting().orElseThrow().delegationIds());
        assertEquals(original, f.entries.values().iterator().next());
        assertEquals(1, f.entries.size());
        f.engine.tick(TENANT, 10);
        assertEquals(0, f.started);
    }

    /** 显式后台不建立父等待，仍返回受理回执供并行安排。 */
    @Test void backgroundDelegateReturnsAcceptanceWithoutWait() {
        var f = new Fixture();
        var reply = f.engine.execute(IDENTITY, "background", DelegationCommand.delegate(f.target.handle(), "并行调查", true));
        assertEquals("accepted", reply.value().get("status"));
        assertTrue(reply.waiting().isEmpty());
        assertTrue(f.waits.isEmpty());
        assertEquals("", f.entries.values().iterator().next().task().expectedOutput());
    }
    /** 六条不同子任务经过分批终态、等待和组件重建后，只唤醒一次原父 Run。 */
    @Test void complexSixChildWaitKeepsExactResultsAcrossRecreationAndPartialCompletion() {
        var f = new Fixture();
        var children = complexChildren(f, 6);
        var ids = children.stream().map(Entry::id).toList();
        var wait = new DelegationCommand(DelegationCommand.Operation.WAIT, "", Optional.empty(), ids, "", true);
        assertTrue(f.engine.execute(IDENTITY, "six-child-wait", wait).waiting().isPresent());
        f.engine.tick(TENANT, 20);
        assertEquals(0, f.started);
        for (int index = 0; index < 5; index++) {
            var state = index == 3 ? State.FAILED : index == 4 ? State.CANCELLED : State.COMPLETED;
            f.completed.put(ids.get(index), new Result(state, "结果-" + index + "-🧭",
                    state == State.COMPLETED ? "" : index == 3 ? "external_effect_outcome_unknown" : "terminal-" + index, 100 + index,
                    List.of("child-event-" + index, "artifact-revision-" + index)));
        }
        f.childStates.put(ids.getLast(), State.WAITING);
        f.engine = new SessionDelegationEngine(f.store, f.host, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        f.engine.tick(TENANT, 20);
        assertEquals(0, f.wakes);
        assertEquals(State.WAITING, f.entries.get(ids.getLast()).state());
        assertEquals(5, f.entries.values().stream().filter(entry -> SessionDelegations.terminal(entry.state())).count());
        f.completed.put(ids.getLast(), new Result(State.COMPLETED, "最后结果", "", 105,
                List.of("child-event-5", "artifact-revision-5")));
        f.engine.tick(TENANT, 20);
        assertEquals(1, f.wakes);
        for (int index = 0; index < children.size(); index++) {
            var original = children.get(index);
            var terminal = f.entries.get(original.id());
            assertEquals(original.childRun(), terminal.childRun());
            assertEquals(original.childInput(), terminal.childInput());
            assertEquals(original.createdAt(), terminal.createdAt());
            assertEquals(100 + index, terminal.result().orElseThrow().usedTokens());
            assertEquals(List.of("child-event-" + index, "artifact-revision-" + index),
                    terminal.result().orElseThrow().evidence());
        }
        var settled = f.engine.execute(IDENTITY, "six-child-wait", wait);
        assertTrue(settled.waiting().isEmpty());
        assertEquals(6, ((List<?>) settled.value().get("results")).size());
        // C05：混合结果按真实终态闭包，不把带部分摘要的失败或 UNKNOWN 改为成功。
        var publicResults = ((List<?>) settled.value().get("results")).stream()
                .map(value -> (Map<?, ?>) value).toList();
        assertEquals(4, publicResults.stream().filter(value -> "completed".equals(value.get("state"))).count());
        assertEquals(1, publicResults.stream().filter(value -> "failed".equals(value.get("state"))).count());
        assertEquals(1, publicResults.stream().filter(value -> "cancelled".equals(value.get("state"))).count());
        var unknown = publicResults.stream().filter(value -> ids.get(3).equals(value.get("delegation_id"))).findFirst().orElseThrow();
        assertEquals("external_effect_outcome_unknown", unknown.get("reason"));
        assertEquals("结果-3-🧭", unknown.get("summary"));
        assertEquals("103", unknown.get("used_tokens_reported"));
        assertEquals(List.of("child-event-3", "artifact-revision-3"), unknown.get("evidence"));
        f.engine.tick(TENANT, 20);
        assertEquals(1, f.wakes);
        assertEquals(0, f.started);
    }

    /** 批量取消不改写已完成子任务，撤销披露后仍收敛原等待而不泄漏新读取。 */
    @Test void complexCancellationSettlesOnlyOwnedActiveChildrenAfterDisclosureRevocation() {
        var f = new Fixture();
        var children = complexChildren(f, 4);
        var ids = children.stream().map(Entry::id).toList();
        f.engine.execute(IDENTITY, "cancel-group-wait", new DelegationCommand(
                DelegationCommand.Operation.WAIT, "", Optional.empty(), ids, "", true));
        f.engine.tick(TENANT, 20);
        f.completed.put(ids.getFirst(), new Result(State.COMPLETED, "已提交", "", 21, List.of("original-event")));
        f.engine.tick(TENANT, 20);
        var preserved = f.entries.get(ids.getFirst());
        f.competeOnUpdate = true;
        f.engine.cancelOwned(IDENTITY, "parent-complex-cancel");
        f.readRevoked = true;
        f.revoked = true;
        f.engine = new SessionDelegationEngine(f.store, f.host, Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));
        for (int index = 1; index < 3; index++)
            f.completed.put(ids.get(index), new Result(State.CANCELLED, "", "cancelled", index,
                    List.of("cancel-event-" + index)));
        f.engine.tick(TENANT, 20);
        assertEquals(0, f.wakes);
        assertFalse(SessionDelegations.terminal(f.entries.get(ids.getLast()).state()));
        for (int index = 1; index < children.size(); index++)
            assertEquals(Optional.of("parent-complex-cancel"), f.entries.get(ids.get(index)).cancelKey());
        assertEquals(preserved, f.entries.get(ids.getFirst()));
        assertThrows(SecurityException.class, () -> f.engine.execute(IDENTITY, "disclosed",
                command(DelegationCommand.Operation.GET, ids.getFirst())));
        assertThrows(IllegalArgumentException.class, () -> f.engine.execute(IDENTITY, "foreign-cancel",
                command(DelegationCommand.Operation.CANCEL, "foreign-parent-child")));
        f.completed.put(ids.getLast(), new Result(State.CANCELLED, "", "cancelled", 3,
                List.of("cancel-event-3")));
        f.engine.tick(TENANT, 20);
        assertEquals(1, f.wakes);
        assertEquals(preserved, f.entries.get(ids.getFirst()));
        assertEquals(0, f.started);
    }

    /** 三个终态任务补充后生成关联新 Run；计划门禁与重放始终保留原身份及结果。 */
    @Test void complexTerminalSupplementsRequireApprovalAndReplayStableRelatedRuns() {
        var f = new Fixture();
        var originals = complexChildren(f, 3);
        f.engine.tick(TENANT, 20);
        for (int index = 0; index < originals.size(); index++) {
            var state = index == 1 ? State.FAILED : State.COMPLETED;
            f.completed.put(originals.get(index).id(), new Result(state, "证据-" + index,
                    state == State.FAILED ? "missing_evidence" : "", 30 + index, List.of("source-" + index)));
        }
        f.engine.tick(TENANT, 20);
        var frozenTerminals = List.copyOf(f.entries.values());
        f.plan = true;
        for (int index = 0; index < originals.size(); index++) {
            var send = new DelegationCommand(DelegationCommand.Operation.SEND, "", Optional.empty(), List.of(originals.get(index).id()), "补齐第 " + index + " 项，保留来源", true);
            assertThrows(IllegalArgumentException.class,
                    () -> f.engine.execute(IDENTITY, "denied-supplement", send));
        }
        assertEquals(3, f.entries.size());
        f.plan = false;
        for (int index = 0; index < originals.size(); index++) {
            var send = new DelegationCommand(DelegationCommand.Operation.SEND, "", Optional.empty(), List.of(originals.get(index).id()), "补齐第 " + index + " 项，保留来源", true);
            var accepted = f.engine.execute(IDENTITY, "supplement-" + index, send);
            f.engine = new SessionDelegationEngine(f.store, f.host, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
            assertEquals(accepted, f.engine.execute(IDENTITY, "supplement-" + index, send));
            var related = f.entries.get(accepted.value().get("delegation_id"));
            assertEquals(Optional.of(originals.get(index).id()), related.previousId());
            assertNotEquals(originals.get(index).childRun(), related.childRun());
            assertTrue(related.task().context().contains("source-" + index));
            assertEquals(originals.get(index).task().constraints(), related.task().constraints());
        }
        assertEquals(6, f.entries.size());
        f.engine.tick(TENANT, 20);
        assertEquals(0, f.started);
        for (var terminal : frozenTerminals) assertEquals(terminal, f.entries.get(terminal.id()));
    }

    private static List<Entry> complexChildren(Fixture fixture, int count) {
        for (int index = 0; index < count; index++)
            fixture.engine.execute(IDENTITY, "complex-model-" + index, DelegationCommand.start(
                    fixture.target.handle(), new DelegationCommand.Task("校验项目-" + index,
                            "中文上下文-🧭-" + index, List.of("保留来源", "不得泄漏私有快照"),
                            "结果、来源及交付修订-" + index)));
        return List.copyOf(fixture.entries.values());
    }
    /** 格式失败不撤销此前已受理的 START；下一轮和进程恢复仍返回原 child 身份。 */
    @Test void identicalStartAcrossModelIterationsReusesChildAfterEngineRecreation() {
        var f = new Fixture();
        var first = f.start("model-iteration-1");
        f.engine = new SessionDelegationEngine(f.store, f.host, Clock.fixed(NOW, ZoneOffset.UTC));
        var repeated = f.start("model-iteration-3-after-invalid-decision");
        assertEquals(first.value(), repeated.value());
        assertEquals(1, f.entries.size());
        f.engine.tick(TENANT, 10);
        assertEquals(0, f.started);
        f.revoked = true;
        assertThrows(SecurityException.class, () -> f.start("model-iteration-4"));
        assertEquals(1, f.entries.size());
    }

    /** 完整结构参与身份；只改目标内容、输入、约束或预期都不能复用旧任务。 */
    @Test void differentTaskFieldsCreateIndependentChildren() {
        var f = new Fixture();
        f.start("first");
        for (var task : List.of(
                new DelegationCommand.Task("新目标", "输入", List.of("边界"), "输出及标准"),
                new DelegationCommand.Task("目标", "新输入", List.of("边界"), "输出及标准"),
                new DelegationCommand.Task("目标", "输入", List.of("新边界"), "输出及标准"),
                new DelegationCommand.Task("目标", "输入", List.of("边界"), "新输出"))) {
            f.engine.execute(IDENTITY, "same-model-key", DelegationCommand.start(f.target.handle(), task));
        }
        assertEquals(5, f.entries.size());
    }
    @Test void acceptanceIsNotCompletionAndWaitingSurvivesCoordinatorRecreation() {
        var f=new Fixture();var first=f.start("one");
        assertEquals("accepted",first.value().get("status"));assertEquals(0,f.started);
        var entry=f.entries.values().iterator().next();
        assertEquals(State.QUEUED,entry.state());assertTrue(entry.result().isEmpty());
        var pending=f.engine.execute(IDENTITY,"wait-one",command(DelegationCommand.Operation.WAIT,entry.id()));
        assertTrue(pending.waiting().isPresent());assertEquals(1,f.waits.size());
        f.engine=new SessionDelegationEngine(f.store,f.host,Clock.fixed(NOW,ZoneOffset.UTC));
        f.engine.tick(TENANT,10);assertEquals(0, f.started);assertEquals(0,f.wakes);
        f.completed.put(entry.id(),new Result(State.COMPLETED,"evidence checked","",120000,List.of("real-run-event")));
        f.engine.tick(TENANT,10);
        assertEquals(1,f.wakes);assertTrue(f.waits.values().iterator().next().delivered());
        var done=f.engine.execute(IDENTITY,"wait-one",command(DelegationCommand.Operation.WAIT,entry.id()));
        assertTrue(done.waiting().isEmpty());assertTrue(RuntimeJson.writeCanonical(done.value()).contains("120000"));
        f.engine.tick(TENANT,10);assertEquals(1,f.wakes);
    }
    @Test void replayKeepsChildIdentityAndTerminalSupplementCreatesRelatedRun() {
        var f=new Fixture();f.start("one");f.start("one");assertEquals(1,f.entries.size());
        var original=f.entries.values().iterator().next();
        f.completed.put(original.id(),new Result(State.FAILED,"partial","budget_exhausted",100,List.of()));f.engine.tick(TENANT,10);
        var response=f.engine.execute(IDENTITY,"follow",new DelegationCommand(DelegationCommand.Operation.SEND,"",Optional.empty(),List.of(original.id()),"补齐证据", true));
        assertEquals(original.id(),response.value().get("previous_delegation_id"));
        assertEquals(2,f.entries.size());assertNotEquals(original.id(),response.value().get("delegation_id"));
        assertEquals(State.FAILED,f.entries.get(original.id()).state());
    }
    @Test void planGateAndScopeCannotBeBypassedByStartOrSupplement() {
        var f=new Fixture();f.plan=true;
        assertThrows(IllegalArgumentException.class,()->f.start("one"));assertTrue(f.entries.isEmpty());
        f.plan=false;f.start("one");var entry=f.entries.values().iterator().next();f.plan=true;
        assertThrows(IllegalArgumentException.class,()->f.engine.execute(IDENTITY,"send",new DelegationCommand(DelegationCommand.Operation.SEND,"",Optional.empty(),List.of(entry.id()),"do it", true)));
        f.plan=false;f.revoked=true;
        assertEquals(entry.id(),f.engine.execute(IDENTITY,"get",command(DelegationCommand.Operation.GET,entry.id())).value().get("delegation_id"));
        assertThrows(SecurityException.class,()->f.engine.execute(IDENTITY,"send",new DelegationCommand(DelegationCommand.Operation.SEND,"",Optional.empty(),List.of(entry.id()),"do it", true)));
        assertEquals("cancel_requested",f.engine.execute(IDENTITY,"cancel",command(DelegationCommand.Operation.CANCEL,entry.id())).value().get("status"));
        assertThrows(IllegalArgumentException.class,()->f.engine.execute(IDENTITY,"other",command(DelegationCommand.Operation.GET,"other-parent-id")));
    }
    @Test void disclosureRevocationBlocksNewReadsWithoutBlockingRegisteredWaitSettlement() {
        var f=new Fixture();f.start("one");var entry=f.entries.values().iterator().next();
        f.engine.execute(IDENTITY,"wait-one",command(DelegationCommand.Operation.WAIT,entry.id()));
        f.engine.tick(TENANT,10);assertEquals(0, f.started);
        f.readRevoked=true;f.revoked=true;
        assertThrows(SecurityException.class,()->f.engine.execute(IDENTITY,"get",command(DelegationCommand.Operation.GET,entry.id())));
        assertThrows(SecurityException.class,()->f.engine.execute(IDENTITY,"wait-two",command(DelegationCommand.Operation.WAIT,entry.id())));
        f.engine.execute(IDENTITY,"cancel",command(DelegationCommand.Operation.CANCEL,entry.id()));
        f.completed.put(entry.id(),new Result(State.CANCELLED,"private result","cancelled",42,List.of("private evidence")));
        f.engine.tick(TENANT,10);
        assertEquals(State.CANCELLED,f.entries.get(entry.id()).state());assertEquals(1,f.wakes);
        assertThrows(SecurityException.class,()->f.engine.execute(IDENTITY,"wait-one",command(DelegationCommand.Operation.WAIT,entry.id())));
    }
    @Test void parentCancellationSettlesOwnedChildrenWithoutTreatingReceiptAsStop() {
        var f=new Fixture();f.start("one");var entry=f.entries.values().iterator().next();f.engine.tick(TENANT,10);
        f.cancelled=true;f.engine.tick(TENANT,10);
        assertTrue(f.entries.get(entry.id()).cancelKey().isPresent());
        assertFalse(SessionDelegations.terminal(f.entries.get(entry.id()).state()));assertEquals(1,f.cancels);
        f.completed.put(entry.id(),new Result(State.CANCELLED,"","cancelled",0,List.of()));f.engine.tick(TENANT,10);
        assertEquals(State.CANCELLED,f.entries.get(entry.id()).state());
    }
    @Test void publicDirectoryNeverContainsPrivateSnapshotAndWorkWaitKeepsOriginalParent() {
        var f=new Fixture();var directory=f.engine.execute(IDENTITY,"targets",new DelegationCommand(DelegationCommand.Operation.TARGETS,"",Optional.empty(),List.of(),"", true));
        var text=RuntimeJson.writeCanonical(directory.value());assertTrue(text.contains("能力"));assertTrue(text.contains("target_handle"));assertFalse(text.contains("secret"));
        var waiting=f.engine.execute(IDENTITY,"wait-work",command(DelegationCommand.Operation.WAIT,"work:input"));
        assertEquals(IDENTITY,waiting.waiting().orElseThrow().parent());
        f.work=Optional.of(Map.of("work_id","input","status","completed"));f.engine.tick(TENANT,10);assertEquals(1,f.wakes);
    }
    @Test void emptyDirectoryIsACompletedReadOnlyResult() {
        var f=new Fixture();f.emptyDirectory=true;f.emptyReason=EmptyTargetReason.FROZEN_SCOPE_EMPTY;

        var reply=f.engine.execute(IDENTITY,"targets",command(DelegationCommand.Operation.TARGETS,""));

        assertEquals("completed",reply.value().get("status"));assertEquals(false,reply.value().get("available"));
        assertEquals("frozen_scope_empty",reply.value().get("reason"));assertEquals(List.of(),reply.value().get("targets"));
        assertTrue(reply.waiting().isEmpty());assertTrue(f.entries.isEmpty());assertEquals(0,f.started);assertEquals(0,f.planSubmissions);
    }
    @Test void emptyDirectoryStillRejectsStart() {
        var f=new Fixture();f.emptyDirectory=true;

        assertThrows(IllegalArgumentException.class,()->f.start("start"));
        assertTrue(f.entries.isEmpty());assertTrue(f.plans.isEmpty());assertEquals(0,f.planSubmissions);
    }
    @Test void invalidDirectoryAuthorizationIsNotReportedAsAnEmptyDirectory() {
        var f=new Fixture();f.invalidDirectoryAuthorization=true;

        assertThrows(SecurityException.class,()->f.engine.execute(IDENTITY,"targets",command(DelegationCommand.Operation.TARGETS,"")));
        assertTrue(f.entries.isEmpty());assertEquals(0,f.started);assertEquals(0,f.planSubmissions);
    }
    @Test void nativeSchemaDefinesStatusSemanticsAndTaskFieldsWithoutChildConfiguration() {
        var tools = SessionNativeConversation.actionTools(true, Set.of());
        var byName = tools.stream().collect(java.util.stream.Collectors.toMap(ModelPort.ModelTool::wireName, value -> value));
        String delegate = byName.get("delegate").inputSchema().canonicalJson();
        assertTrue(delegate.contains("instruction"));assertTrue(delegate.contains("background"));
        assertFalse(delegate.contains("expected_output"));assertFalse(delegate.contains("oneOf"));assertFalse(delegate.contains("max_tokens"));
        assertTrue(byName.containsKey("wait_delegates"));assertTrue(byName.containsKey("message_delegate"));
        assertTrue(byName.containsKey("cancel_delegate"));assertTrue(byName.containsKey("delegate_status"));
        String plan = byName.get("plan_submit").inputSchema().canonicalJson();
        assertTrue(plan.contains("expected_revision"));assertTrue(plan.contains("source_task"));assertTrue(plan.contains("expected_output"));
        assertTrue(SessionNativeConversation.actionTools(false, Set.of()).stream().noneMatch(value -> value.wireName().equals("delegate")));
    }
    @Test void cancellationRetriesCompetingStateAdvanceAndRejectsSupplementUntilStopped() {
        var f = new Fixture(); f.start("one"); var entry = f.entries.values().iterator().next();
        f.competeOnUpdate = true;
        var reply = f.engine.execute(IDENTITY, "cancel", command(DelegationCommand.Operation.CANCEL, entry.id()));
        assertEquals("cancel_requested", reply.value().get("status"));
        assertEquals(Optional.of("delegation-cancel:cancel"), f.entries.get(entry.id()).cancelKey());
        assertEquals(1, f.cancels);
        assertThrows(IllegalArgumentException.class, () -> f.engine.execute(IDENTITY, "send",
                new DelegationCommand(DelegationCommand.Operation.SEND, "", Optional.empty(), List.of(entry.id()), "继续", true)));
    }
    static DelegationCommand command(DelegationCommand.Operation operation,String id){return new DelegationCommand(operation,"",Optional.empty(),id.isEmpty()?List.of():List.of(id),"", true);}
    static final class Fixture {
        Map<String,Entry> entries=new LinkedHashMap<>();Map<String,Wait> waits=new LinkedHashMap<>();Map<String,Result> completed=new HashMap<>();Map<String,State> childStates=new HashMap<>();
        int started,wakes,cancels,planSubmissions;boolean cancelled,revoked,readRevoked,plan,competeOnUpdate,emptyDirectory,invalidDirectoryAuthorization,failWaitRegistration;EmptyTargetReason emptyReason=EmptyTargetReason.NO_AUTHORIZED_TARGETS;Optional<Map<String,Object>> work=Optional.empty();
        Map<String,Map<String,Object>> plans=new HashMap<>();
        Target target=new Target("member:b",new AgentId("b"),"成员","公开角色",List.of(new Capability("tool","能力","说明")),new TypedPayload("private","{\"secret\":true}"));
        Store store=(Store)Proxy.newProxyInstance(Store.class.getClassLoader(),new Class<?>[]{Store.class},(p,m,a)->switch(m.getName()) {
            case "create"->{var e=(Entry)a[0];yield entries.computeIfAbsent(e.id(),id->e);}
            case "read"->Optional.ofNullable(entries.get((String)a[1])).filter(e->e.parent().equals(a[0]));
            case "list","forSession"->List.copyOf(entries.values());
            case "active"->entries.values().stream().filter(e->!SessionDelegations.terminal(e.state())).toList();
            case "update"->{var e=(Entry)a[0];
                if(competeOnUpdate){competeOnUpdate=false;var old=entries.get(e.id());entries.put(e.id(),new Entry(old.id(),old.parent(),old.operationKey(),old.target(),old.task(),old.childRun(),old.childInput(),old.previousId(),State.RUNNING,Optional.empty(),Optional.empty(),old.revision()+1,old.createdAt(),old.updatedAt()));yield false;}
                if(entries.get(e.id()).revision()!=(long)a[1])yield false;entries.put(e.id(),e);yield true;}
            case "register"->{if(failWaitRegistration){failWaitRegistration=false;throw new IllegalStateException("simulated_wait_write_interruption");}var w=(Wait)a[0];yield waits.computeIfAbsent(w.id(),id->w);}
            case "wait"->Optional.ofNullable(waits.get((String)a[1]));
            case "pendingWaits"->waits.values().stream().filter(w->!w.delivered()).toList();
            case "delivered"->{var w=(Wait)a[0];waits.put(w.id(),w);yield true;}
            default->throw new UnsupportedOperationException(m.getName());
        });
        Host host=new Host(){
            public List<Target> targets(RunIdentity parent){if(invalidDirectoryAuthorization)throw new SecurityException("delegation_parent_missing");return emptyDirectory?List.of():List.of(target);}
            public EmptyTargetReason emptyTargetReason(RunIdentity parent){return emptyReason;}
            public void requireAccess(RunIdentity parent,Target value){if(revoked||!target.equals(value)||!IDENTITY.equals(parent))throw new SecurityException();}
            public void requireReadAccess(RunIdentity parent,Target value){if(readRevoked||!target.equals(value)||!IDENTITY.equals(parent))throw new SecurityException();}
            public void requireDirectExecution(RunIdentity parent){if(plan)throw new IllegalArgumentException("plan approval required");}
            public void start(Entry entry){started++;}
            public Optional<Result> result(Entry entry){return Optional.ofNullable(completed.get(entry.id()));}
            public State state(Entry entry){return childStates.getOrDefault(entry.id(),State.RUNNING);}
            public void send(Entry entry,String input,String message){}
            public void cancel(Entry entry,String input){cancels++;}
            public boolean wake(Wait wait,List<Entry> results){assertEquals(IDENTITY,wait.parent());wakes++;return true;}
            public boolean parentCancelled(RunIdentity parent){return cancelled;}
            public Optional<Map<String,Object>> workResult(RunIdentity parent,String workId){return work;}
        };
        SessionDelegationEngine engine=new SessionDelegationEngine(store,host,Clock.fixed(NOW,ZoneOffset.UTC));
        SessionDelegationEngine.Reply start(String key){return engine.execute(IDENTITY,key,DelegationCommand.start(target.handle(),new DelegationCommand.Task("目标","输入",List.of("边界"),"输出及标准")));}
    }
}
