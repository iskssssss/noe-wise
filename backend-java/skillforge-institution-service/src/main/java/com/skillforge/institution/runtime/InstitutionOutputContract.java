package com.skillforge.institution.runtime;

import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;

import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.artifact.DeliverableContract.Planned;
import com.skillforge.institution.browser.BrowserClosingBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.prompts.OutputPrompts;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowQuery;
import com.skillforge.runtime.session.spi.SessionOutputContract;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import com.skillforge.runtime.session.AgentExecutionOutputs;
import com.skillforge.runtime.session.SessionGoal;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 机构宿主成果生命周期到通用 Runtime 输出门禁的唯一适配器。 */
@Component
public final class InstitutionOutputContract implements SessionOutputContract {
    private static final int SOURCE_PAGE_SIZE = 256;
    private static final String TOOL_RESULT_EVENT = "agent.tool-result";
    private static final String PLAN_REVIEW_SCHEMA = "skillforge.runtime.plan-review-request.v1";
    private static final String JSON_SCHEMA = "application/json";
    private static final String STRUCTURED_CONTENT = "structuredContent";
    private static final Set<String> PUBLIC_CARD_TYPES = Set.of("tool_input", "confirmation", "form", "approval");
    private final DeliverableService deliverables;
    private final AgentRuntimeGoalDriver goals;
    private final PublicOutputGuard publicOutputs;
    private final SessionLogPort log;
    private final ObjectMapper json;
    private final WorkflowInteractionView workflows;
    private final com.skillforge.institution.artifact.ArtifactClosingBudget closingBudget;
    private final BrowserClosingBudget browserClosingBudget;
    // 只缓存不可变工具结果已检查的水位；淘汰后按当前 Run 重读，不缓存未来结果。
    private final java.util.Map<RunIdentity, CheckedResults> checkedResults = new java.util.LinkedHashMap<>(32, .75f, true);
    private record CheckedResults(long sequence, Optional<String> rejection) { }
    private static final String FINALIZE_TOOL = "finalize_task_outputs";

    public InstitutionOutputContract(DeliverableService deliverables, AgentRuntimeGoalDriver goals,
            PublicOutputGuard publicOutputs, SessionLogPort log, ObjectMapper json,
            WorkflowInteractionView workflows, com.skillforge.institution.artifact.ArtifactClosingBudget closingBudget,
            BrowserClosingBudget browserClosingBudget) {
        this.deliverables = deliverables;
        this.goals = goals;
        this.publicOutputs = publicOutputs;
        this.log = log;
        this.json = json;
        this.workflows=workflows;
        this.closingBudget=closingBudget;
        this.browserClosingBudget=browserClosingBudget;
    }

    @Override public Optional<String> publicContentRejection(RunIdentity run, List<String> texts) {
        var scope = publicOutputs.capture(run);
        return texts.stream().anyMatch(scope::rejects) ? Optional.of(PublicOutputGuard.REJECTION_CODE) : Optional.empty();
    }

    @Override public Optional<String> capabilityContentRejection(RunIdentity run,List<TypedPayload> payloads) {
        var scope=publicOutputs.capture(run);
        for(var payload:payloads) {
            try {
                var result=json.readTree(payload.canonicalJson());
                if(PublicOutputGuard.REJECTION_CODE.equals(result.path("error").asText())
                        ||PublicOutputGuard.REJECTION_CODE.equals(result.path("delivery_error").asText()))return Optional.of(PublicOutputGuard.REJECTION_CODE);
                var texts=new ArrayList<String>();
                var structured=result.path(STRUCTURED_CONTENT);
                if (structured.isObject()) {
                    if (PublicOutputGuard.REJECTION_CODE.equals(structured.path("error").asText())
                            || PublicOutputGuard.REJECTION_CODE.equals(structured.path("delivery_error").asText()))
                        return Optional.of(PublicOutputGuard.REJECTION_CODE);
                    if (structured.has("public_presentation"))
                        texts.addAll(PublicPresentationTexts.read(structured.path("public_presentation")));
                }
                // 能力拥有者明确标识的公开内容；参数与结果 JSON 本身仍是内部事实。
                if(result.has("public_presentation"))texts.addAll(PublicPresentationTexts.read(result.path("public_presentation")));
                if(payload.schemaId().equals(PLAN_REVIEW_SCHEMA)) {
                    texts.add(result.path("title").asText()); texts.add(result.path("plan").asText());
                } else if(result.has("message")&&result.has("requestedSchema")) {
                    texts.add(result.path("message").asText());texts.addAll(PublicPresentationTexts.read(result.path("requestedSchema")));
                } else if(result.has("type")&&PUBLIC_CARD_TYPES.contains(result.path("type").asText())) {
                    texts.addAll(PublicPresentationTexts.read(result));
                }
                if(texts.stream().anyMatch(scope::rejects))return Optional.of(PublicOutputGuard.REJECTION_CODE);
            }catch(IOException invalid){throw new IllegalStateException("public_output_capability_invalid",invalid);}
        }
        return Optional.empty();
    }

    @Override public Optional<String> workflowContentRejection(RunIdentity run,VerifiedIdentity actor,ExecutionRef execution) {
        if (!actor.owner().equals(run.owner())) throw new IllegalStateException("public_workflow_actor_scope_invalid");
        var pending=workflows.find(new WorkflowQuery(actor,execution));
        if(pending.isEmpty())return Optional.empty();
        var card=pending.orElseThrow();
        if(!card.owner().equals(run.owner())||!card.runRef().equals(run.runRef())||!card.executionRef().equals(execution))
            throw new IllegalStateException("public_workflow_source_scope_invalid");
        try {
            return publicContentRejection(run,PublicPresentationTexts.read(json.readTree(card.card().canonicalJson())));
        }catch(IOException invalid){throw new IllegalStateException("public_workflow_card_invalid",invalid);}
    }

    @Override public Optional<String> terminalRejection(RunIdentity run) {
        CheckedResults checked;
        synchronized (checkedResults) { checked = checkedResults.get(run); }
        if (checked != null && checked.rejection().isPresent()) return checked.rejection();
        long cursor = checked == null ? 0 : checked.sequence();
        while (true) {
            var page = log.readAfter(new SessionLogPort.ReadSessionLog(run.owner(), cursor, SOURCE_PAGE_SIZE),
                    run.runRef(), Set.of(TOOL_RESULT_EVENT));
            for (var event : page) {
                if (!event.owner().equals(run.owner()) || event.sessionSequence() <= cursor
                        || !event.runRef().equals(Optional.of(run.runRef())) || !TOOL_RESULT_EVENT.equals(event.eventType()))
                    throw new IllegalStateException("public_output_result_scope_invalid");
                cursor = event.sessionSequence();
                try {
                    var payload = json.readTree(event.payload().canonicalJson());
                    var result = payload.path("output");
                    if (result.path("canonicalJson").isTextual()) result = json.readTree(result.path("canonicalJson").textValue());
                    var rejected=capabilityContentRejection(run,List.of(new TypedPayload(JSON_SCHEMA,result.toString())));
                    if(rejected.isPresent()) { remember(run, new CheckedResults(cursor, rejected)); return rejected; }
                } catch (IOException invalid) { throw new IllegalStateException("public_output_result_invalid", invalid); }
            }
            if (page.size() < SOURCE_PAGE_SIZE) {
                if (cursor > 0) remember(run, new CheckedResults(cursor, Optional.empty()));
                return Optional.empty();
            }
        }
    }

    @Override public StreamingContent publicStreamingContent(RunIdentity run, String accumulated) {
        return publicOutputs.capture(run).streamingContent(accumulated);
    }

    private void remember(RunIdentity run, CheckedResults result) {
        synchronized (checkedResults) {
            var previous = checkedResults.get(run);
            if (previous == null || previous.rejection().isEmpty()
                    && (result.rejection().isPresent() || result.sequence() > previous.sequence()))
                checkedResults.put(run, result);
            while (checkedResults.size() > 256) checkedResults.remove(checkedResults.keySet().iterator().next());
        }
    }

    @Override public Optional<ActionRejection> actionRejection(RunIdentity run, String action) {
        var code = deliverables.actionRejection(run, action);
        if (code == null) return Optional.empty();
        return Optional.of(new ActionRejection(code, OutputPrompts.rejection(code)));
    }

    /** 只有真实交付状态或已有正式成果时提供输出上下文，普通对话不携带空协议。 */
    @Override public Optional<PreparationHint> preparationHint(RunIdentity run) {
        return preparationHint(run,deliverables.currentOutputPlanState(run),deliverables.currentOutputPlans(run));
    }

    /** 本次准备已读的成果状态与正式版本只编码一次，不在Hint转换中重新加载。 */
    private Optional<PreparationHint> preparationHint(RunIdentity run,Optional<String> state,List<Planned> plans) {
        if (state.isEmpty() && plans.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new PreparationHint(OutputPrompts.preparationHint(),
                    OutputPrompts.finalAnswerHint(),
                    OutputPrompts.runContext(run.causationInputId().value(),
                            state.orElse("absent"),
                            json.writeValueAsString(plans.stream().map(item -> {
                                var value=new java.util.LinkedHashMap<String,Object>();
                                value.put("key",item.key());value.put("title",item.title());value.put("kind",item.kind());
                                value.put("base_revision_id",item.baseRevisionId());
                                value.put("formats",item.formats().stream().map(format->format.format()).toList());
                                return value;
                            }).toList()))));
        } catch (IOException invalid) {
            throw new IllegalStateException("output_context_serialization_failed", invalid);
        }
    }

    /**
     * 同次新MODEL准备只消费当前Run及真实成果状态，不解释输入或预建交付义务。
     * 返回值只冻结进该请求，不复用到下一派发，也不替代finalize或取消的当前状态校验。
     */
    public AgentExecutionOutputs.Policy prepareOutput(ExecutionStore.Run actual,AgentRunBindings.Binding binding,
            List<SessionToolDirectory.Entry> frozen) {
        var run=actual.identity();
        if(!binding.owner().equals(run.owner())||!binding.actor().equals(run.agentId()))
            throw new IllegalStateException("execution_output_binding_mismatch");
        var goal=goals.read(run.owner()).map(AgentRuntimeGoalDriver.Status::goal);
        var output=deliverables.prepareOutput(run);
        var closing=closingPlan(run,goal,output.preparedResult().isPresent(),output.hasOutputs(),plan->browserClosingBudget.extendFrozen(frozen,plan));
        var handles=frozen.stream().filter(entry->closingToolAllowed(run,entry.binding())).map(entry->entry.target().handle().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var spec=new ExecutionBudgetPort.ClosingSpec(closing.modelCalls(),closing.finalizationCalls(),closing.localProcessing().toSeconds(),
                closing.receiptInputUpperBound(),closing.facts(),handles);
        var respond=output.preparedResult().isEmpty()&&output.hasOutputs()?rejection("TASK_OUTPUT_RESOLUTION_REQUIRED"):Optional.<ActionRejection>empty();
        var execute=output.preparedResult().isPresent()?Optional.of(new ActionRejection("TASK_OUTPUTS_FROZEN",
                OutputPrompts.preparedOutputFeedback(output.state().orElseThrow()))):Optional.<ActionRejection>empty();
        var hint=preparationHint(run,output.state(),output.plans());
        return new AgentExecutionOutputs.Policy(hint,respond,execute,output.preparedResult(),spec,false);
    }

    /** 拒绝反馈仍取正式成果提示词，读取合批不改变模型可见错误协议。 */
    private static Optional<ActionRejection> rejection(String code) { return Optional.of(new ActionRejection(code,OutputPrompts.rejection(code))); }

    @Override public Optional<String> preparedResult(RunIdentity run) {
        return deliverables.preparedResult(run);
    }

    @Override public boolean closingToolAllowed(RunIdentity run, com.skillforge.runtime.api.port.ToolPortal.ToolBinding binding) {
        return binding.providerType()==com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM
                && FINALIZE_TOOL.equals(binding.providerRef()) && FINALIZE_TOOL.equals(binding.toolName())
                || BrowserClosingBudget.stopBinding(binding);
    }

    @Override public ClosingPlan closingPlan(RunIdentity run) {
        return closingPlan(run,plan->browserClosingBudget.extend(run,plan));
    }

    /** 新实际MODEL准备只按原冻结工具目录保护停止，不读取当前挂载或新建binding。 */
    public ClosingPlan closingPlan(RunIdentity run,List<com.skillforge.runtime.session.spi.SessionToolDirectory.Entry> frozen) {
        return closingPlan(run,plan->browserClosingBudget.extendFrozen(frozen,plan));
    }

    /** Goal与成果当前事实由宿主拥有，浏览器容量从调用者明确的准确目录计算。 */
    private ClosingPlan closingPlan(RunIdentity run,java.util.function.UnaryOperator<ClosingPlan> browser) {
        var goal=goals.read(run.owner()).map(AgentRuntimeGoalDriver.Status::goal);
        boolean prepared=deliverables.preparedResult(run).isPresent();
        return closingPlan(run,goal,prepared,!prepared&&deliverables.hasOutputs(run),browser);
    }

    /** 单次已读的Goal和成果义务计算有限收尾规模，不重查来源或浏览器目录。 */
    private ClosingPlan closingPlan(RunIdentity run,Optional<SessionGoal.Snapshot> goal,boolean prepared,boolean hasOutputs,
            java.util.function.UnaryOperator<ClosingPlan> browser) {
        var facts=new ArrayList<String>();
        goal.ifPresent(value -> facts.add("持续 Goal 当前状态="+value.state()
                +"；revision="+value.revision()+"；目标="+value.objective()));
        if (prepared)
            return new ClosingPlan(1,0,java.time.Duration.ofSeconds(1),0,facts);
        if (!hasOutputs)
            return browser.apply(new ClosingPlan(1,0,java.time.Duration.ofSeconds(1),0,facts));
        facts.add("本轮已有真实成果提交状态；预留清单核验与最终回复，未选择的候选文件不形成交付义务。");
        return browser.apply(closingBudget.plan(run, facts));
    }

    @Override public void reconcile(RunIdentity run) {
        deliverables.reconcile(run);
        deliverables.pendingCompletion(run).ifPresent(completion ->
                deliverables.markCompletion(run, completion.intent(), goals.settleCompletion(completion)));
    }
}
