package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.session.spi.SessionOutputContract.ActionRejection;
import com.skillforge.runtime.session.spi.SessionOutputContract.PreparationHint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 原实际MODEL请求拥有的宿主输出义务；采纳与恢复只消费此值，不重查当前成果状态。 */
public final class AgentExecutionOutputs {
    public static final String SCHEMA="skillforge.runtime.execution.output-directive.v1";
    private static final String MESSAGE="execution-output-policy:";
    private static final String CORRECTION="execution-output-correction:";
    public static final int MAX_CORRECTIONS=2;
    private AgentExecutionOutputs() { }

    /** 已有可信成果且全部执行动作已关闭时，本轮只能回答；普通预算收尾仍保留允许的能力。 */
    static boolean finalAnswerOnly(Optional<Directive> directive) {
        return directive.map(Directive::policy).filter(policy -> policy.closing() && policy.closingSpec().toolHandles().isEmpty()
                || policy.preparedResult().isPresent() && policy.respondRejection().isEmpty() && policy.executionRejection().isPresent()).isPresent();
    }

    /** @param hint 当前可信规则与Run事实 @param respondRejection 提前结束的拒绝 @param executionRejection 其他新动作的拒绝 @param preparedResult 原真实冻结成果引用 @param closingSpec 宿主测量所需的有限收尾资源与目标 @param closing 本请求是否已进入账本收尾阶段 */
    public record Policy(Optional<PreparationHint> hint,Optional<ActionRejection> respondRejection,
            Optional<ActionRejection> executionRejection,Optional<String> preparedResult,
            ExecutionBudgetPort.ClosingSpec closingSpec,boolean closing) {
        public Policy { Objects.requireNonNull(hint);Objects.requireNonNull(respondRejection);Objects.requireNonNull(executionRejection);Objects.requireNonNull(preparedResult);Objects.requireNonNull(closingSpec); }
        public Policy withClosing(boolean value) { return new Policy(hint,respondRejection,executionRejection,preparedResult,closingSpec,value); }
        @Override public String toString() { return "ExecutionOutputPolicy[REDACTED]"; }
    }
    /** @param policy 同次准备的真实宿主义务 @param correctionsUsed 已提交历史中实际纠正次数，不含本次候选 */
    public record Directive(Policy policy,int correctionsUsed) {
        public Directive { Objects.requireNonNull(policy);if(correctionsUsed<0||correctionsUsed>MAX_CORRECTIONS)throw invalid(); }
        @Override public String toString() { return "ExecutionOutputDirective[REDACTED]"; }
    }

    /** 固定字段编码只进入原实际请求和模型SYSTEM规则，不形成第二套持久化日志。 */
    static ObjectNode encode(Directive directive) {
        var value=AgentExecutionCodec.object();var policy=directive.policy();
        value.put("activeInstructions",policy.hint().map(hint->policy.preparedResult().isPresent()&&policy.respondRejection().isEmpty()?hint.finalAnswerInstructions():hint.stableInstructions()).orElse("宿主没有额外输出规则；按已确认事实完成任务。"));
        if(policy.hint().isPresent()) {var hint=value.putObject("hint");var source=policy.hint().orElseThrow();hint.put("stableInstructions",source.stableInstructions());hint.put("finalAnswerInstructions",source.finalAnswerInstructions());hint.put("runContext",source.runContext());} else value.putNull("hint");
        rejection(value,"respondRejection",policy.respondRejection());rejection(value,"executionRejection",policy.executionRejection());
        policy.preparedResult().ifPresentOrElse(result->value.put("preparedResult",result),()->value.putNull("preparedResult"));
        var spec=value.putObject("closingSpec");spec.put("modelCalls",Integer.toString(policy.closingSpec().modelCalls()));spec.put("finalizationCalls",Integer.toString(policy.closingSpec().finalizationCalls()));
        spec.put("localSeconds",Long.toString(policy.closingSpec().localSeconds()));spec.put("receiptInputUpperBound",Long.toString(policy.closingSpec().receiptInputUpperBound()));
        var facts=spec.putArray("facts");policy.closingSpec().facts().forEach(facts::add);var handles=spec.putArray("toolHandles");policy.closingSpec().toolHandles().stream().sorted().forEach(handles::add);
        value.put("closing",policy.closing());value.put("correctionsUsed",Integer.toString(directive.correctionsUsed()));return value;
    }
    /** 精确解码原模型请求的义务，未知字段或不规范数字不能被当前宿主规则覆盖。 */
    static Directive decode(JsonNode value) {
        AgentExecutionCodec.fields(value,"activeInstructions","hint","respondRejection","executionRejection","preparedResult","closingSpec","closing","correctionsUsed");
        Optional<PreparationHint> hint=Optional.empty();if(!value.get("hint").isNull()){var source=value.get("hint");AgentExecutionCodec.fields(source,"stableInstructions","finalAnswerInstructions","runContext");hint=Optional.of(new PreparationHint(text(source,"stableInstructions"),text(source,"finalAnswerInstructions"),text(source,"runContext")));}
        var spec=value.get("closingSpec");AgentExecutionCodec.fields(spec,"modelCalls","finalizationCalls","localSeconds","receiptInputUpperBound","facts","toolHandles");
        if(!value.get("closing").isBoolean())throw invalid();
        var closing=new ExecutionBudgetPort.ClosingSpec(Math.toIntExact(AgentExecutionCodec.number(spec,"modelCalls")),Math.toIntExact(AgentExecutionCodec.number(spec,"finalizationCalls")),AgentExecutionCodec.number(spec,"localSeconds"),AgentExecutionCodec.number(spec,"receiptInputUpperBound"),strings(spec.get("facts")),Set.copyOf(strings(spec.get("toolHandles"))));
        var result=value.get("preparedResult").isNull()?Optional.<String>empty():Optional.of(text(value,"preparedResult"));
        var directive=new Directive(new Policy(hint,readRejection(value.get("respondRejection")),readRejection(value.get("executionRejection")),result,closing,value.get("closing").booleanValue()),Math.toIntExact(AgentExecutionCodec.number(value,"correctionsUsed")));
        if(!encode(directive).equals(value))throw invalid();return directive;
    }

    /** 当前动态政策追加于完整历史之后，由调用方随后追加可信时间；完整义务仍由原请求output字段持有。 */
    static List<ModelMessage> messages(List<ModelMessage> original,Optional<Directive> directive,long ordinal,AgentExecutionCodec codec) {
        var retained=original.stream().filter(message->message.role()!=ModelRole.SYSTEM||!message.messageId().startsWith(MESSAGE)).toList();
        if(directive.isEmpty())return retained;
        var policy=directive.orElseThrow().policy();
        if(policy.hint().isEmpty()&&policy.respondRejection().isEmpty()&&policy.executionRejection().isEmpty()
                &&policy.preparedResult().isEmpty()&&!policy.closing()&&policy.closingSpec().facts().isEmpty())return retained;
        var result=new ArrayList<ModelMessage>(retained);
        var visible=new StringBuilder("当前执行要求：\n");
        policy.hint().ifPresent(hint -> visible.append(policy.preparedResult().isPresent()&&policy.respondRejection().isEmpty()
                ?hint.finalAnswerInstructions():hint.stableInstructions()).append("\n").append(hint.runContext()).append("\n"));
        if(policy.closing()) {
            visible.append("当前已进入收尾阶段。停止新增业务查询、检索、委派和工作流，不要继续扩展任务。\n进入收尾不代表工具次数或所有额度已经耗尽。未提供具体触发原因时，只说明本轮已进入收尾、相关查询未继续；不要猜测为次数耗尽、权限拒绝或服务故障。\n");
            if(policy.closingSpec().toolHandles().isEmpty()) visible.append("本轮工具调用已关闭。请立即依据已有事实给出最终答复，说明已完成、未完成和证据限制；不得把预算结束当作目标达成。\n");
            else visible.append("本轮仅提供获准的收尾工具，用于停止已有资源或冻结已有成果；完成这些动作后给出真实最终答复。\n");
        }
        policy.respondRejection().ifPresent(rejection -> visible.append("最终答复前还需处理：").append(rejection.feedback())
                .append("（原因码：").append(rejection.code()).append("）\n"));
        policy.executionRejection().ifPresent(rejection -> visible.append("执行限制：").append(rejection.feedback())
                .append("（原因码：").append(rejection.code()).append("）\n"));
        policy.closingSpec().facts().forEach(fact -> visible.append("已确认的收尾事实：").append(fact).append("\n"));
        result.add(new ModelMessage(MESSAGE+ordinal,ModelRole.SYSTEM,visible.toString()));
        return List.copyOf(result);
    }

    /** 被拒动作未发生外部效果；将原归一化意图与真实拒绝反馈写入同一次MODEL采纳候选。 */
    static List<ModelMessage> correct(List<ModelMessage> messages,com.skillforge.runtime.api.port.ModelPort.ModelResult.Completed result,ActionRejection rejection,long ordinal,Directive directive,AgentExecutionCodec codec,boolean nativeConversation) {
        var corrected=new ArrayList<>(messages);
        if(nativeConversation) {
            var assistant=SessionNativeResponses.assistant(result,ordinal,true,List.of(),"");
            String refusal="本次动作在执行前被拒绝，尚未执行。原因码："+rejection.code()+"。\n"+rejection.feedback();
            var receipts=assistant.toolCalls().stream().map(call->new ModelMessage("execution-output-rejected:"+ordinal+":"+call.callId(),ModelRole.TOOL,
                    refusal,List.of(),Optional.of(call.callId()),Optional.<String>empty())).toList();
            corrected=new ArrayList<>(com.skillforge.runtime.core.ModelConversation.append(messages,assistant,receipts));
        } else corrected.add(new ModelMessage("execution-output-intent:"+ordinal,ModelRole.ASSISTANT,result.output().canonicalJson()));
        String feedback="请修正刚才的输出。原因码："+rejection.code()+"。\n"+rejection.feedback()
                +"\n已使用 "+Math.incrementExact(directive.correctionsUsed())+" / "+MAX_CORRECTIONS
                +" 次纠正机会。按真实结果继续，不得宣称被拒动作已完成；收尾阶段不得新增业务调用。";
        corrected.add(new ModelMessage(CORRECTION+ordinal,ModelRole.SYSTEM,feedback));
        return List.copyOf(corrected);
    }
    /** 当前请求的固定义务先于工具、等待或响应采用，收尾阶段只允许明确目标和最终响应。 */
    static Optional<ActionRejection> rejection(Directive directive,AgentDecisionDecoder.Result decoded) {
        var policy=directive.policy();boolean respond=decoded instanceof AgentDecisionDecoder.Decoded single&&single.action() instanceof AgentAction.Respond;
        if(respond)return policy.respondRejection();
        if(policy.executionRejection().isPresent())return policy.executionRejection();
        if(!policy.closing())return Optional.empty();
        if(decoded instanceof AgentDecisionDecoder.Decoded single&&single.action() instanceof AgentAction.ToolCall tool&&policy.closingSpec().toolHandles().contains(tool.toolHandle()))return Optional.empty();
        return Optional.of(new ActionRejection("output_closing_action_rejected","收尾阶段只能停止原有资源、冻结已有成果或给出真实最终响应，不能启动新业务。"));
    }
    private static void rejection(ObjectNode value,String field,Optional<ActionRejection> rejection) {if(rejection.isPresent()){var item=value.putObject(field);item.put("code",rejection.orElseThrow().code());item.put("feedback",rejection.orElseThrow().feedback());}else value.putNull(field);}
    private static Optional<ActionRejection> readRejection(JsonNode value){if(value.isNull())return Optional.empty();AgentExecutionCodec.fields(value,"code","feedback");return Optional.of(new ActionRejection(text(value,"code"),text(value,"feedback")));}
    private static List<String> strings(JsonNode value){if(!value.isArray())throw invalid();var result=new ArrayList<String>();for(var item:value){if(!item.isTextual())throw invalid();result.add(item.textValue());}return List.copyOf(result);}
    private static String text(JsonNode value,String field){return AgentExecutionCodec.string(value,field);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("execution_output_directive_invalid");}
}
