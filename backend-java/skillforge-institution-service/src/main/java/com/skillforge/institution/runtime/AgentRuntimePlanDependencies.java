package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.SessionExecutionPlans;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Plan业务提交只通知原Run；真实等待解除必须由同事务原事件、DAG及任务状态证明。 */
public final class AgentRuntimePlanDependencies {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentRuntimePlanDependencies.class);
    private final JdbcExecutionStore store;
    private final ObjectProvider<InstitutionPlanHost> plans;
    private final AgentRunBindings bindings;
    private final AgentRuntimeHostProvisioner provisioner;
    private final JdbcIdentityPolicyPort identities;
    private final ObjectProvider<AgentRuntimeExecutionWorker> workers;
    private final Mapper mapper;
    private final ObjectMapper json;
    private final SessionExecutionPlans codec;
    private final String workerId;
    /** 只抑制同一通知的并发读取，不保存待执行工作或创建第二队列。 */
    private final Set<RunRef> resolving = ConcurrentHashMap.newKeySet();
    /** 仅保存各租户上次已检查的Run ID，确保未满足的旧等待不饿死后续等待。 */
    private final Map<TenantId, String> cursors = new ConcurrentHashMap<>();

    public AgentRuntimePlanDependencies(JdbcExecutionStore store, ObjectProvider<InstitutionPlanHost> plans, AgentRunBindings bindings,
            AgentRuntimeHostProvisioner provisioner, JdbcIdentityPolicyPort identities, ObjectProvider<AgentRuntimeExecutionWorker> workers,
            Mapper mapper, ObjectMapper json, SessionExecutionPlans codec, String workerId) {
        this.store = Objects.requireNonNull(store); this.plans = Objects.requireNonNull(plans); this.bindings = Objects.requireNonNull(bindings);
        this.provisioner = Objects.requireNonNull(provisioner); this.identities = Objects.requireNonNull(identities); this.workers = Objects.requireNonNull(workers);
        this.mapper = Objects.requireNonNull(mapper); this.json = com.skillforge.institution.runtime.SessionSubjectJson.configure(Objects.requireNonNull(json).copy()).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.codec = Objects.requireNonNull(codec); this.workerId = Objects.requireNonNull(workerId);
    }

    /** Store工厂预先注册领域用途映射，运行事务内不修改MyBatis Configuration。 */
    public static void configure(Configuration configuration) {
        String resource = "mapper/AgentRuntimePlanDependenciesMapper.xml";
        if (configuration.hasStatement(Mapper.class.getName() + ".source", false)) return;
        try (var xml = AgentRuntimePlanDependencies.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("plan_dependency_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException invalid) { throw new IllegalStateException("plan_dependency_mapper_invalid", invalid); }
    }

    /** 有Spring事务时只在成功提交后处理；自有Store事务调用者必须在其返回之后通知。 */
    public void changed(RunRef run) {
        Objects.requireNonNull(run);
        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { changedNow(run); }
            });
        } else changedNow(run);
    }

    /** 仅由现有Worker启动及五秒兜底调用，有界键集扫描不派发任何新Plan命令。 */
    public void recover(TenantId tenant, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("plan_dependency_limit_invalid");
        TenantContext.runWithTenant(tenant.value(), () -> {
            String after = cursors.getOrDefault(tenant, "");
            var runs = mapper.waiting(tenant.value(), after, limit);
            if (runs.size() > limit) throw new IllegalStateException("plan_dependency_scan_unbounded");
            if (runs.isEmpty()) { cursors.remove(tenant); return; }
            for (var id : runs) changedNow(new RunRef(tenant, id));
            cursors.put(tenant, runs.getLast());
        });
    }

    private void changedNow(RunRef reference) {
        if (!resolving.add(reference)) return;
        try {
            TenantContext.runWithTenant(reference.tenantId().value(), () -> resolve(reference));
        } catch (ExecutionStore.Rejection stale) {
            // 通知与另一已提交响应竞争时，原等待事实保留；下一次定向通知或既有兜底重读。
            LOG.debug("Plan依赖通知未推进 run={} reason={}", reference.runId(), stale.reason());
        } catch (RuntimeException unavailable) {
            LOG.warn("Plan依赖观察暂缓 tenant={} run={} failureType={}", reference.tenantId().value(), reference.runId(), unavailable.getClass().getSimpleName(), unavailable);
        } finally { resolving.remove(reference); }
    }

    private void resolve(RunRef reference) {
        var found = store.load(reference); if (found.isEmpty()) return;
        var parent = found.orElseThrow();
        if (parent.cancelled() || parent.phase() != ExecutionStore.Phase.WAITING || parent.waiting().isEmpty()) return;
        var waiting = store.waiting(reference, parent.waiting().orElseThrow()).orElseThrow(AgentRuntimePlanDependencies::rejected);
        if (waiting.kind() != ExecutionStore.WaitKind.PLAN) return;
        if (!(waiting.internal().orElseThrow() instanceof ExecutionStore.PlanWait dependency)) throw rejected();
        var binding = bindings.find(reference).orElseThrow(AgentRuntimePlanDependencies::rejected);
        requireBinding(parent.identity(), binding);
        String event = eventId(dependency);
        var source = mapper.source(parent.identity(), dependency, event, false);
        var through = includedThrough(source, dependency);
        var candidate = plans.getObject().pollWait(parent.identity(), dependency.businessWaitId());
        if (candidate.isEmpty()) return;
        var response = response(candidate.orElseThrow(), plans.getObject().state(parent.identity()));
        var input = new InputId("plan-response:" + AgentExecutionCodec.digest(waiting.reference().action().actionId() + ":" + waiting.reference().generation()));
        var actor = verifySystem(parent.identity(), input);
        var material = new ExecutionStore.Material(new ResultRef("execution-response:" + reference.runId() + ":" + input.value()), response);
        var accepted = store.acceptDependency(new ExecutionStore.Resume(actor, reference, waiting.reference(), parent.revision(), input,
                material, AgentExecutionCodec.digest(response.canonicalJson()), Map.of(), List.of(resolved(parent, input, waiting.reference()))),
                (session, current, actualWait, children, actualResponse) -> {
                    if (!current.identity().equals(parent.identity()) || !actualWait.reference().equals(waiting.reference())
                            || actualWait.kind() != ExecutionStore.WaitKind.PLAN || !actualWait.internal().equals(Optional.of(dependency)) || !children.isEmpty()) throw rejected();
                    String actualSource = session.getMapper(Mapper.class).source(current.identity(), dependency, event, true);
                    long actualThrough = includedThrough(actualSource, dependency);
                    if (!source.equals(actualSource) || through != actualThrough) throw rejected();
                    var exact = store.participate(session, reference.tenantId(), () -> plans.getObject().participate(session, current.identity(), binding, actualThrough, () -> {
                        var reply = plans.getObject().pollWait(current.identity(), dependency.businessWaitId()).orElseThrow(AgentRuntimePlanDependencies::rejected);
                        return response(reply, plans.getObject().state(current.identity()));
                    }));
                    if (!actualResponse.content().equals(exact)) throw rejected();
                });
        if (accepted.accepted() || accepted.run().phase() == ExecutionStore.Phase.READY) workers.getObject().wake(reference);
    }

    private static void requireBinding(RunIdentity run, AgentRunBindings.Binding binding) {
        if (!binding.owner().equals(run.owner()) || !binding.actor().equals(run.agentId()) || binding.role() != AgentRunBindings.Role.PRIMARY) throw rejected();
    }

    private static String eventId(ExecutionStore.PlanWait dependency) {
        String prefix = "delegation-wait:";
        if (!dependency.businessWaitId().startsWith(prefix) || dependency.businessWaitId().length() == prefix.length()) throw rejected();
        return "plan-wait:" + dependency.businessWaitId().substring(prefix.length());
    }

    /** 原事件精确绑定计划版本、任务清单和模型补充位置；当前任务状态由锁定的DAG快照判断。 */
    private long includedThrough(String source, ExecutionStore.PlanWait dependency) {
        if (source == null) throw rejected();
        try {
            var value = json.readTree(source); var revision = value.path("plan_revision"); var through = value.path("supplemental_included_through");
            if (!value.isObject() || !value.path("work_id").isTextual() || !dependency.planId().equals(value.path("work_id").textValue())
                    || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() != dependency.planRevision()
                    || !through.isIntegralNumber() || !through.canConvertToLong() || through.longValue() < 0 || !value.path("task_ids").isArray()) throw rejected();
            var ids = new java.util.HashSet<String>();
            for (var id : value.path("task_ids")) if (!id.isTextual() || id.textValue().isBlank() || !ids.add(id.textValue())) throw rejected();
            return through.longValue();
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("plan_dependency_source_invalid", invalid); }
    }

    private TypedPayload response(PlanPort.Reply reply, PlanPort.State state) {
        if (reply.waitHandle().isPresent() || !Boolean.TRUE.equals(reply.value().get("wait_satisfied"))) throw rejected();
        try { return codec.encodeResponse(new TypedPayload("application/json", json.writeValueAsString(reply.value())), state); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("plan_dependency_response_invalid", invalid); }
    }

    private ExecutionStore.Event resolved(ExecutionStore.Run run, InputId input, ExecutionStore.WaitRef waiting) {
        var original = AgentExecutionProgram.resolved(run, input, waiting);
        try {
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.data().canonicalJson()); value.put("dependencyKind", "plan");
            return new ExecutionStore.Event(original.id(), original.type(), new TypedPayload(original.data().schemaId(), json.writeValueAsString(value)));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("plan_dependency_event_invalid", invalid); }
    }

    private VerifiedIdentity verifySystem(RunIdentity run, InputId input) {
        provisioner.provisionAccess(run.runRef().tenantId().value());
        var authentication = identities.registerTrusted(new PrincipalRef.SystemPrincipal(run.runRef().tenantId(), workerId), run.owner(), "chat", "plan", Duration.ofHours(1));
        return switch (identities.verify(new IdentityPolicyPort.IdentityRequest(authentication, run.owner(), "chat", "plan", new RequestId(input.value()),
                Optional.of(input), run.trace().traceId(), run.trace().spanId()))) {
            case IdentityPolicyPort.IdentityResult.Verified verified -> verified.identity();
            case IdentityPolicyPort.IdentityResult.Rejected denied -> throw new SecurityException(denied.failure().reasonCode());
        };
    }

    private static ExecutionStore.Rejection rejected() { return new ExecutionStore.Rejection("plan_dependency_not_satisfied"); }

    @org.apache.ibatis.annotations.Mapper
    public interface Mapper {
        List<String> waiting(@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit);
        String source(@Param("run") RunIdentity run, @Param("dependency") ExecutionStore.PlanWait dependency, @Param("event") String event, @Param("lock") boolean lock);
    }
}
