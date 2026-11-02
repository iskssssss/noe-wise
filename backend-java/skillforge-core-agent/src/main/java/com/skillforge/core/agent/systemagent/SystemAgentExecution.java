package com.skillforge.core.agent.systemagent;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.*;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 系统任务单次执行协调：先持久受理，再领取，外调不持有配置事务。 */
@Service
public class SystemAgentExecution {
    public static final String MAX_OUTPUT_TOKENS_FIELD = "maxOutputTokens";
    public static final String REMAINING_MILLIS_FIELD = "remainingMillis";
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SystemAgentExecution.class);
    private final SystemAgentService agents;
    private final LlmService llm;
    private final ObjectProvider<SystemAgentRunner> runner;
    private final ObjectMapper json;

    public SystemAgentExecution(SystemAgentService agents,LlmService llm,
                                ObjectProvider<SystemAgentRunner> runner,ObjectMapper json) {
        this.agents=agents; this.llm=llm; this.runner=runner; this.json=json;
    }

    public RunView executePurpose(String purpose,String tenant,String key,String input) {
        return execute(agents.acceptPurpose(purpose, tenant, key, input));
    }

    /** 后台领域门禁只围住首次派发，不重新解释已经受理的原调用。 */
    public RunView executePurposeGuarded(String purpose, String tenant, String key, String input,
            Runnable beforeDispatch) {
        FrozenRun run = agents.acceptPurpose(purpose, tenant, key, input);
        return execute(run, ExecutionLimits.unbounded(new ResultRef("system-agent:" + run.purpose())),
                Objects.requireNonNull(beforeDispatch)).run();
    }

    /** 首次派发在同一领取事务绑定正式来源，初始UNKNOWN用量也可关联原任务。 */
    public RunView executePurposeBound(String purpose, String tenant, String key, String input,
            java.util.function.Consumer<FrozenRun> beforeDispatch) {
        FrozenRun run = agents.acceptPurpose(purpose, tenant, key, input);
        Objects.requireNonNull(beforeDispatch);
        return execute(run, ExecutionLimits.unbounded(new ResultRef("system-agent:" + run.purpose())),
                () -> beforeDispatch.accept(run)).run();
    }

    /** 受控系统用途调用；限制同时影响冻结受理与真实 Provider 请求。 */
    public PurposeResult executePurposeControlled(String purpose, String tenant, String key, String input,
                                                  ExecutionLimits limits) {
        Objects.requireNonNull(limits);
        if (requiresBusinessParent(purpose)) requireParentScope(purpose, tenant);
        FrozenRun run = agents.acceptPurpose(purpose, tenant, key, input);
        validateControlledRun(run);
        return result(run, effectiveLimits(run, limits));
    }

    public RunView debug(String id,String source,long revision,String key,String input,String actor) {
        if ("memory-extraction".equals(agents.detail(id).purpose())) {
            input=new MemoryExtractionCandidates(json).encodeInput(memoryInput(input));
        }
        return execute(agents.acceptDebug(id,source,revision,key,input,actor),
                ExecutionLimits.unbounded(new ResultRef("system-agent:debug:" + id))).run();
    }

    /** 重启后读取通用 Runtime 已提交结果；固定模型调用结果未知时不重复外调。 */
    public void recoverDebugRuns() {
        for(var run:agents.pendingDebugRuns()) {
            if("READY".equals(run.view().status())) { execute(run); continue; }
            if("MODEL_TASK".equals(run.executionMode())) { agents.run(run.id(),run.tenantId()); continue; }
            try {
                var completed=runner.getObject().completed(run);
                if(completed.isPresent()) {
                    var result=completed.get();
                    agents.finish(run,result.content(),null,result.usage()==null?null:result.usage().promptTokens(),
                            result.usage()==null?null:result.usage().completionTokens(),
                            java.time.Duration.between(run.view().createdAt(),java.time.Instant.now()).toMillis());
                }
            } catch(SystemAgentRunner.TerminalFailure failure) {
                agents.finish(run,null,"system_agent_execution_failed",null,null,0);
            } catch(Exception failure) {
                // 查询失败不能证明 Runtime 已失败；保留状态供后续恢复，日志不包含供应商正文。
                log.warn("System Agent recovery deferred: run={}, failure={}",run.id(),failure.getClass().getSimpleName());
            }
        }
    }

    private RunView execute(FrozenRun run) {
        return execute(run, ExecutionLimits.unbounded(new ResultRef("system-agent:" + run.purpose()))).run();
    }

    private PurposeResult result(FrozenRun run, ExecutionLimits limits) {
        return execute(run, limits);
    }

    private static void validateControlledRun(FrozenRun run) {
        if (!"context-summary".equals(run.purpose())) return;
        if (!"MODEL_TASK".equals(run.executionMode())
                || run.config() == null || !run.config().mounts().isEmpty()
                || run.mounts() == null || !run.mounts().isEmpty()) {
            throw new IllegalArgumentException(controlledReason(run, "execution_invalid"));
        }
    }

    private ExecutionLimits effectiveLimits(FrozenRun run, ExecutionLimits requested) {
        if (!"context-summary".equals(run.purpose())) return requested;
        try {
            var input = json.readTree(run.input());
            int frozenMax = input.path(MAX_OUTPUT_TOKENS_FIELD).asInt(0);
            long frozenMillis = input.path(REMAINING_MILLIS_FIELD).asLong(0);
            if (frozenMax <= 0 || frozenMillis <= 0) throw new IllegalArgumentException(controlledReason(run, "limits_invalid"));
            Duration frozen = Duration.ofMillis(frozenMillis);
            Duration remaining = requested.remaining().compareTo(frozen) < 0 ? requested.remaining() : frozen;
            return new ExecutionLimits(frozenMax, remaining, requested.cancelled(), requested.root());
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(controlledReason(run, "limits_invalid"), failure);
        }
    }

    private PurposeResult execute(FrozenRun run, ExecutionLimits limits) {
        return execute(run, limits, null);
    }

    private PurposeResult execute(FrozenRun run, ExecutionLimits limits, Runnable beforeDispatch) {
        if (limits.cancelled().getAsBoolean() || limits.remaining().isZero() || limits.remaining().isNegative()) {
            return new PurposeResult(agents.run(run.id(), run.tenantId()), configurationRef(run), invocationRef(run), 0);
        }
        boolean claimed = beforeDispatch == null ? agents.claim(run) : agents.claimGuarded(run, beforeDispatch);
        if (!claimed) return new PurposeResult(agents.run(run.id(),run.tenantId()),
                configurationRef(run), invocationRef(run), coveredMessages(run));
        long started=System.nanoTime();
        String event=limits.root().value()+":"+run.id();
        LlmResult result = null;
        try (var scope=MeterScope.open(metering(run, event))) {
            var memoryInput = "memory-extraction".equals(run.purpose()) ? memoryInput(run.input()) : null;
            int covered = coveredMessages(run);
            if ("MODEL_TASK".equals(run.executionMode())) {
                ResponseFormat format=java.util.Set.of("memory-extraction", "task-context").contains(run.purpose())
                        ?new ResponseFormat.JsonObject():new ResponseFormat.Text();
                Duration timeout = limits.remaining().compareTo(Duration.ofSeconds(run.config().timeoutSeconds())) < 0
                        ? limits.remaining() : Duration.ofSeconds(run.config().timeoutSeconds());
                if ("context-summary".equals(run.purpose())) {
                    var summary = llm.generatePinnedSummary(run.tenantId(),run.config().modelConfigId(),run.modelRevision(),
                            run.config().systemPrompt(),run.input(),format,timeout,limits.maxOutputTokens(),limits.cancelled());
                    result = summary.result(); covered = summary.coveredMessages();
                } else {
                    result=llm.generatePinned(run.tenantId(),run.config().modelConfigId(),run.modelRevision(),
                            run.config().systemPrompt(),run.input(),format,timeout,limits.maxOutputTokens(),limits.cancelled());
                }
            } else {
                result=runner.getObject().execute(run);
            }
            if(result==null || result.content()==null || result.content().isBlank()) throw new IllegalArgumentException("system_agent_output_empty");
            if("memory-extraction".equals(run.purpose())) {
                new MemoryExtractionCandidates(json).parse(result.content(),memoryInput);
            }
            String output = "context-summary".equals(run.purpose())
                    ? json.createObjectNode().put("content", result.content()).put("coveredMessages", covered).toString()
                    : result.content();
            var finished = agents.finish(run,output,null,
                    result.usage()==null?null:result.usage().promptTokens(),
                    result.usage()==null?null:result.usage().completionTokens(),elapsed(started));
            return new PurposeResult(finished, configurationRef(run), invocationRef(run), covered);
        } catch(SystemAgentRunner.Pending pending) {
            return new PurposeResult(agents.run(run.id(),run.tenantId()), configurationRef(run), invocationRef(run), 0);
        } catch(Exception failure) {
            // 校验失败仍保留已实际收到的原输出及用量；异常只保存稳定码，日志不输出原文。
            log.warn("系统用途执行失败 invocation={} diagnostic={}", run.id(),
                    com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(failure));
            String message=failure.getMessage();
            String code=failure instanceof LlmService.ModelOutcomeUnknownException ? "system_agent_outcome_unknown"
                    : failure instanceof LlmService.TruncatedOutputException ? "system_agent_output_truncated"
                    : failure instanceof LlmService.ContentFilteredException ? "model_content_filtered"
                    : failure instanceof IllegalArgumentException && message!=null
                    && message.matches("memory_[a-z_]+|system_agent_output_empty")?message:"system_agent_execution_failed";
            return new PurposeResult(agents.finish(run,result==null?null:result.content(),code,
                    result==null||result.usage()==null?null:result.usage().promptTokens(),
                    result==null||result.usage()==null?null:result.usage().completionTokens(),elapsed(started)),
                    configurationRef(run), invocationRef(run), 0);
        }
    }

    private static String controlledReason(FrozenRun run, String suffix) {
        return "context_summary_" + suffix;
    }

    /** 业务准备的上下文摘要沿真实父Run身份和预算；技术事件不能替代业务根。 */
    private static void requireParentScope(String purpose, String tenant) {
        String prefix = "context_summary";
        var scope = com.skillforge.runtime.api.port.ExecutionBudgetScope.current()
                .orElseThrow(() -> new IllegalArgumentException(prefix + "_parent_budget_missing"));
        var cost = MeterScope.current();
        if (!scope.run().tenantId().value().equals(tenant) || cost == null
                || !scope.run().runId().equals(cost.runId()) || cost.rootInputId() == null || cost.traceContext() == null
                || cost.requestId() == null || cost.sessionId() == null || cost.agentId() == null
                || !cost.requestId().equals(cost.traceContext().inputId()) || !cost.rootInputId().equals(cost.traceContext().rootInputId()))
            throw new IllegalArgumentException(prefix + "_parent_identity_mismatch");
    }

    private static boolean requiresBusinessParent(String purpose) {
        return SystemAgentService.CONTEXT_SUMMARY_PURPOSE.equals(purpose);
    }

    private static CostContext metering(FrozenRun run, String event) {
        if (requiresBusinessParent(run.purpose()) && !run.debug()) {
            requireParentScope(run.purpose(), run.tenantId());
            return CostContext.builder().eventId(event).scopeType(com.skillforge.core.billing.export.PlatformUsageRecorder.SYSTEM_AGENT_SCOPE_TYPE).scopeId(run.id())
                    .callPoint("system-agent.task").build();
        }
        return CostContext.builder().eventId(event).requestId(event)
                .scopeType(com.skillforge.core.billing.export.PlatformUsageRecorder.SYSTEM_AGENT_SCOPE_TYPE).scopeId(run.id())
                .callPoint(run.debug()?"system-agent.debug":"system-agent.task")
                .traceContext(new TraceContext(event,event,event,event,event,null)).build();
    }

    /** 调试与生产都显式提供冻结来源时间，避免隐式当前时间破坏重放与相对日期解释。 */
    private MemoryExtractionCandidates.ExtractionInput memoryInput(String input) {
        return new MemoryExtractionCandidates(json).decodeInput(input);
    }

    private int coveredMessages(FrozenRun run) {
        try { return json.readTree(run.input()).path("coveredMessages").asInt(0); }
        catch (Exception ignored) { return 0; }
    }

    private static ResultRef configurationRef(FrozenRun run) {
        return new ResultRef("system-agent-config:" + run.agentId() + ":" +
                (run.version() == null ? "draft" : run.version()));
    }

    private static ResultRef invocationRef(FrozenRun run) {
        return new ResultRef("system-agent-invocation:" + run.id());
    }

    public record PurposeResult(RunView run, ResultRef configurationRef, ResultRef invocationRef,
                                int coveredMessages) {
        public PurposeResult {
            Objects.requireNonNull(run); Objects.requireNonNull(configurationRef); Objects.requireNonNull(invocationRef);
            if (coveredMessages < 0) throw new IllegalArgumentException("coveredMessages must not be negative");
        }
    }

    public record ExecutionLimits(int maxOutputTokens, Duration remaining, BooleanSupplier cancelled, ResultRef root) {
        public ExecutionLimits {
            if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens must be positive");
            Objects.requireNonNull(remaining); Objects.requireNonNull(cancelled); Objects.requireNonNull(root);
            if (remaining.isNegative() || remaining.isZero()) throw new IllegalArgumentException("remaining must be positive");
        }

        static ExecutionLimits unbounded(ResultRef root) {
            return new ExecutionLimits(Integer.MAX_VALUE, Duration.ofDays(3650), () -> false, root);
        }
    }
    private static long elapsed(long started) {return (System.nanoTime()-started)/1_000_000;}

}
