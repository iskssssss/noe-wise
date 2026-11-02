package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionExecutionPlans;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Plan 原生指令的本地采纳边界：真实 DAG 变更、原回执和 Run 上下文共用一个事务。
 * 不创建 Provider Attempt 或预算占用；重复原模型指令只采纳已保存回执。
 */
public final class AgentRuntimeExecutionPlans implements ExecutionCoordinator.LocalPlans {
    private final JdbcExecutionStore store;
    private final AgentRuntimeExecutionSources sources;
    private final ObjectProvider<InstitutionPlanHost> plans;
    private final ObjectProvider<TeamWorkService> teams;
    private final ObjectMapper json;
    private final SessionExecutionPlans codec;

    /** 由生产装配注入真实业务端口，不提供放行授权或空计划替代实现。 */
    public AgentRuntimeExecutionPlans(JdbcExecutionStore store, AgentRuntimeExecutionSources sources,
            ObjectProvider<InstitutionPlanHost> plans, ObjectProvider<TeamWorkService> teams,
            ObjectMapper json, AgentRuntimeProperties properties) {
        this.store = Objects.requireNonNull(store); this.sources = Objects.requireNonNull(sources);
        this.plans = Objects.requireNonNull(plans); this.teams = Objects.requireNonNull(teams);
        this.json = Objects.requireNonNull(json);
        codec = new SessionExecutionPlans(new CodecLimits(properties.getMaxPayloadCodeUnits(),
                properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
    }

    /**
     * 存储首先检查原成功 MODEL、当前写权与权限，再在 guard 内执行真实计划业务和纯采纳。
     * 任一来源、DAG、上下文或公开事件冲突都会回滚整个事务。
     */
    @Override public <M> ExecutionCoordinator.LocalPlanResult<M> execute(ExecutionStore.Run run,
            ExecutionCoordinator.Step.PlanLocal command,
            BiFunction<ExecutionStore.Run, ExecutionStore.Material, ExecutionCoordinator.Adoption<M>> adopter) {
        Objects.requireNonNull(run); Objects.requireNonNull(command); Objects.requireNonNull(adopter);
        var request = codec.decodeRequest(command.request().content());
        if (!request.command().equals(command.command()) || !command.actor().owner().equals(run.identity().owner()))
            throw new SecurityException("execution_plan_request_mismatch");
        var binding = sources.runBinding(run.identity().runRef());
        var adoption = new AtomicReference<ExecutionCoordinator.Adoption<M>>();
        var transition = new ExecutionStore.PlanTransition(run.identity().runRef(), run.revision(), run.lease().orElseThrow(),
                command.sourceModelAction(), command.request());
        var committed = store.adoptPlan(transition, (session, current, saved) -> {
            var actual = saved.orElseGet(() -> store.participate(session, current.identity().runRef().tenantId(), () ->
                    plans.getObject().participate(session, current.identity(), binding, request.supplementalIncludedThrough(), () ->
                            teams.getObject().participatePlan(current.identity(), binding, () -> {
                                var reply = plans.getObject().execute(current.identity(), command.sourceModelAction().actionId(), request.command());
                                try {
                                    var value = new TypedPayload("application/json", json.writeValueAsString(reply.value()));
                                    Optional<ExecutionStore.PlanWait> waiting = reply.waitHandle().map(handle -> {
                                        var body = json.valueToTree(reply.value());
                                        var planId = body.path("work_id"); var revision = body.path("plan_revision");
                                        if (!planId.isTextual() || planId.asText().isBlank() || !revision.isIntegralNumber()
                                                || !revision.canConvertToLong() || revision.longValue() < 1)
                                            throw new IllegalStateException("execution_plan_wait_source_invalid");
                                        return new ExecutionStore.PlanWait(planId.asText(), revision.longValue(), handle);
                                    });
                                    return new ExecutionStore.Material(new ResultRef("execution-plan-result:" + command.sourceModelAction().actionId()),
                                            codec.encodeResult(new SessionExecutionPlans.Result(value, waiting, plans.getObject().state(current.identity()))));
                                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                                    throw new IllegalStateException("execution_plan_result_encoding_failed", failure);
                                }
                            }))));
            var proposed = Objects.requireNonNull(adopter.apply(current, actual));
            adoption.set(proposed);
            return new ExecutionStore.PlanChange(actual, proposed.next(), proposed.context(), proposed.events(), proposed.waiting());
        });
        // 成员准入会再次锁预算根，只能在本次Plan事务成功返回、释放Run/DAG锁后推进。
        if (committed.advanced()) teams.getObject().changed(committed.run().identity().runRef());
        return new ExecutionCoordinator.LocalPlanResult<>(committed.run(), Optional.ofNullable(adoption.get()), committed.advanced());
    }
}
