package com.skillforge.institution.runtime;

import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.embedding.EmbeddingClient;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 精确平台模型到通用 Memory embedding 的唯一宿主桥接，责任来自原调用参数。 */
@Component
public final class MemorySearchEmbeddingBridge implements MemorySearchEmbedding {
    private final PlatformModelCatalogService models;
    private final EmbeddingClient client;
    private final ExecutionBudgetPort budgets;
    private final Clock clock;

    public MemorySearchEmbeddingBridge(PlatformModelCatalogService models,EmbeddingClient client,
            ExecutionBudgetPort budgets,Clock clock) {
        this.models=Objects.requireNonNull(models);this.client=Objects.requireNonNull(client);
        this.budgets=Objects.requireNonNull(budgets);this.clock=Objects.requireNonNull(clock);
    }

    @Override public Outcome embed(Request request) {
        var context=request.context();var run=context.runIdentity();
        if(!context.identity().owner().equals(run.owner()))return rejected("memory_embedding_identity_mismatch");
        Duration remaining=Duration.between(clock.instant(),context.control().deadlineAt());
        if(remaining.isZero()||remaining.isNegative()||context.control().cancellationSignal().isCancellationRequested())
            return rejected("memory_embedding_control_stopped");
        final PlatformModelConfig model;
        try {
            // 当前停用即时阻止新派发；实际连接、凭据和计价仍取原冻结revision。
            models.requireEnabled(request.model().modelConfigId(),"EMBEDDING");
            model=models.readRevision(request.model().modelConfigId(),request.model().revision());
        }
        catch(com.skillforge.core.shared.exception.ApiException unavailable) {return rejected("memory_embedding_model_unavailable");}
        if(!model.id().equals(request.model().modelConfigId())||model.revision()!=request.model().revision()
                ||!"EMBEDDING".equals(model.kind())||!model.enabled()||model.dimensions()==null
                ||model.dimensions()!=request.model().dimensions())return rejected("memory_embedding_model_mismatch");
        Optional<ExecutionBudgetScope.Binding> binding=ExecutionBudgetScope.current();
        if(binding.isPresent()&&!binding.orElseThrow().run().equals(run.runRef()))
            return rejected("memory_embedding_budget_identity_mismatch");
        if(binding.isEmpty())binding=budgets.find(run.runRef()).map(registration->new ExecutionBudgetScope.Binding(budgets,registration.run()));
        var trace=run.trace();
        CostContext cost=CostContext.builder().eventId(request.invocationId()).requestId(run.causationInputId().value())
                .rootInputId(trace.rootInputId().value()).runId(run.runRef().runId()).sessionId(run.owner().sessionRef().sessionId())
                .endUserId(run.requester() instanceof RunRequester.EndUser user?user.endUserId().value():null)
                .agentId(Long.parseLong(run.agentId().value())).callPoint("memory.embedding")
                .traceContext(new TraceContext(run.causationInputId().value(),trace.rootInputId().value(),trace.traceId(),trace.spanId(),
                        request.invocationId(),trace.causedByEventId().map(event->event.value()).orElse(null))).build();
        try(var meter=MeterScope.openIsolated(cost)) {
            if(binding.isPresent()) {
                try(var budget=ExecutionBudgetScope.open(binding.orElseThrow())) {return invoke(request,model,remaining);}
            }
            // 原调用没有Runtime预算注册：共享辅助模型守卫显式使用稳定invocation维护根。
            // 成本责任仍是原Run/请求者；不把该维护根说成父Run消耗。
            return invoke(request,model,remaining);
        }
    }

    private Outcome invoke(Request request,PlatformModelConfig model,Duration remaining) {
        var result=client.embedBatch(request.context().runIdentity().runRef().tenantId().value(),model,request.invocationId(),
                List.of(request.text()),remaining,request.context().control().cancellationSignal()::isCancellationRequested);
        return switch(result) {
            case EmbeddingClient.Outcome.Available available -> {
                var vectors=available.vectors();
                if(vectors.size()!=1||vectors.getFirst().length!=request.model().dimensions())yield rejected("memory_embedding_dimensions_mismatch");
                var converted=new ArrayList<Double>(request.model().dimensions());
                for(float value:vectors.getFirst()) {
                    if(!Float.isFinite(value))yield rejected("memory_embedding_nonfinite");
                    converted.add((double)value);
                }
                yield new Outcome.Available(converted);
            }
            case EmbeddingClient.Outcome.Rejected rejected -> rejected("memory_embedding_call_rejected");
            case EmbeddingClient.Outcome.OutcomeUnknown unknown -> new Outcome.OutcomeUnknown("memory_embedding_outcome_unknown");
        };
    }

    private static Outcome.Rejected rejected(String reason){return new Outcome.Rejected(reason);}
}
