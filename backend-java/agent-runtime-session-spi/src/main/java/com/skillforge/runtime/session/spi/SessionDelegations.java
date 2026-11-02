package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.RunIdentity;
import java.time.Instant;
import java.util.*;

/** 原生委派的宿主适配与持久边界；宿主提供冻结配置和准入，生命周期由 Runtime 拥有。 */
public interface SessionDelegations {
    /** 目标摘要仅包含公开角色与能力，私有配置只保存在 snapshot 内供目标自身执行。 */
    record Target(String handle, AgentId agentId, String name, String roleDescription,
                  List<Capability> capabilities, TypedPayload snapshot) {
        public Target { text(handle); Objects.requireNonNull(agentId); text(name);
            roleDescription = Objects.requireNonNullElse(roleDescription, "");
            capabilities = List.copyOf(capabilities); Objects.requireNonNull(snapshot); }
        @Override public String toString() { return "DelegationTarget[REDACTED]"; }
    }
    record Capability(String type, String name, String description) {
        public Capability { text(type); text(name); description = Objects.requireNonNullElse(description, ""); }
    }
    record Task(String objective, String context, List<String> constraints, String expectedOutput) {
        public Task { text(objective); context = Objects.requireNonNullElse(context, "");
            constraints = List.copyOf(constraints); constraints.forEach(SessionDelegations::text);
            if (new HashSet<>(constraints).size() != constraints.size()) throw new IllegalArgumentException("delegation_task_invalid");
            expectedOutput = Objects.requireNonNullElse(expectedOutput, ""); }
        @Override public String toString() { return "DelegationTask[REDACTED]"; }
    }
    enum State { QUEUED, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED }
    /** 结果状态取自真实 Run；有摘要的失败仍是失败，禁止按文本是否非空推断成功。 */
    record TokenUsage(long reportedTokens, boolean complete) {
        public TokenUsage { if (reportedTokens < 0) throw new IllegalArgumentException("delegation_usage_invalid"); }
        public static TokenUsage known(long tokens) { return new TokenUsage(tokens, true); }
        public static TokenUsage unreported() { return new TokenUsage(0, false); }
    }
    record Result(State state, String summary, String reason, TokenUsage usage, List<String> evidence) {
        public Result { Objects.requireNonNull(state); summary = Objects.requireNonNullElse(summary, "");
            reason = Objects.requireNonNullElse(reason, ""); Objects.requireNonNull(usage); evidence = List.copyOf(evidence);
            if (!terminal(state)) throw new IllegalArgumentException("delegation_result_invalid"); }
        /** 报告下界；只有usage.complete才表示精确累计值。 */
        public long usedTokens() { return usage.reportedTokens(); }
        /** 用于已明确报告的精确数值；存在缺报的执行必须传TokenUsage。 */
        public Result(State state, String summary, String reason, long knownTokens, List<String> evidence) {
            this(state, summary, reason, TokenUsage.known(knownTokens), evidence);
        }
    }
    record Entry(String id, RunIdentity parent, String operationKey, Target target, Task task,
                 RunRef childRun, InputId childInput, Optional<String> previousId, State state,
                 Optional<Result> result, Optional<String> cancelKey, long revision,
                 Instant createdAt, Instant updatedAt) {
        public Entry { text(id); Objects.requireNonNull(parent); text(operationKey); Objects.requireNonNull(target);
            Objects.requireNonNull(task); Objects.requireNonNull(childRun); Objects.requireNonNull(childInput);
            Objects.requireNonNull(previousId); Objects.requireNonNull(state); Objects.requireNonNull(result);
            Objects.requireNonNull(cancelKey); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            if (revision < 1 || !parent.runRef().tenantId().equals(childRun.tenantId())
                    || parent.agentId().equals(target.agentId()) || terminal(state) != result.isPresent()
                    || result.filter(value -> value.state() != state).isPresent() || updatedAt.isBefore(createdAt))
                throw new IllegalArgumentException("delegation_entry_invalid"); }
        @Override public String toString() { return "DelegationEntry[REDACTED]"; }
    }
    /** 等待按父动作固定；终态提交和唤醒采用持久事实，重复投递沿用同一输入标识。 */
    record Wait(String id, RunIdentity parent, List<String> delegationIds, boolean delivered, long revision) {
        public Wait { text(id); Objects.requireNonNull(parent); delegationIds = List.copyOf(delegationIds);
            if (delegationIds.isEmpty() || new HashSet<>(delegationIds).size() != delegationIds.size() || revision < 1)
                throw new IllegalArgumentException("delegation_wait_invalid"); }
    }
    interface Store {
        /** 同键同内容重放；同键异内容必须拒绝，不覆盖首次目标或任务。 */
        Entry create(Entry entry);
        Optional<Entry> read(RunIdentity parent, String id);
        List<Entry> list(RunIdentity parent);
        /** 按完整Session owner读取包含终态的稳定历史；limit为1至1000，独立于恢复扫描批次。 */
        List<Entry> forSession(com.skillforge.runtime.api.port.SessionOwner owner, int limit);
        /** 每批有界并公平轮转；未变化的头批不能阻止后续候选进入恢复。 */
        List<Entry> active(TenantId tenant, int limit);
        /** revision CAS；终态不可逆，调用方只在 false 时重读，不重复执行非幂等操作。 */
        boolean update(Entry entry, long expectedRevision);
        /** 在同一委派写锁内核对取消状态并受理子 Run；callback 只做幂等准入，不执行模型或修改 Entry。 */
        boolean admit(Entry entry, Runnable admission);
        Wait register(Wait wait);
        Optional<Wait> wait(RunIdentity parent, String id);
        /** 与活跃委派独立轮转；仅推进扫描位置，不改变Wait交付状态。 */
        List<Wait> pendingWaits(TenantId tenant, int limit);
        /** 子结果与父等待提交后的定向协调，只读取该父Run的实际业务Wait。 */
        List<Wait> pendingWaits(RunIdentity parent);
        boolean delivered(Wait wait);
    }
    /** 空目录的安全诊断；不包含不可见成员身份或配置。 */
    enum EmptyTargetReason { DISABLED, NO_CONFIGURED_TARGETS, NO_AUTHORIZED_TARGETS, FROZEN_SCOPE_EMPTY }
    interface Host {
        /** 每次准入、补充、等待恢复重新检查当前授权；目录来自父 Run 已冻结的作用域。 */
        List<Target> targets(RunIdentity parent);
        /** 仅在合法查询得到空目录后调用；访问无效必须抛出异常，不能伪装为空目录。 */
        default EmptyTargetReason emptyTargetReason(RunIdentity parent) { return EmptyTargetReason.NO_AUTHORIZED_TARGETS; }
        void requireAccess(RunIdentity parent, Target target);
        /** 读取原委派状态不依赖当前执行开关；宿主仍须核对真实原请求者或成员读取授权。 */
        default void requireReadAccess(RunIdentity parent, Target target) { requireAccess(parent, target); }
        /** 直接委派不能绕过当前计划审批；DAG 准入另由批准的版本证明。 */
        default void requireDirectExecution(RunIdentity parent) { }
        Optional<Result> result(Entry entry);
        State state(Entry entry);
        void send(Entry entry, String inputId, String message);
        void cancel(Entry entry, String inputId);
        /** 只在父 Run 的精确等待已持久化后受理原 Run 恢复；未挂起时返回 false 等待下次领域扫描。 */
        boolean wake(Wait wait, List<Entry> results);
        boolean parentCancelled(RunIdentity parent);
        /** 精确 DAG 工作尚未结束时为空；不得读取其他父 Run 的工作。 */
        Optional<Map<String, Object>> workResult(RunIdentity parent, String workId);
    }
    static boolean terminal(State state) { return state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED; }
    private static void text(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("delegation_text_required"); }
}
