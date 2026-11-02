package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.AssistantContent;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import com.skillforge.runtime.api.port.NativeAgentTools;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.FrozenModelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 原生消息解释与历史投影；只消费原结果和冻结声明，不派发工具或决定Run终态。 */
final class SessionNativeResponses {
    private final CodecLimits limits;
    private final AgentDecisionDecoder decoder;

    SessionNativeResponses(CodecLimits limits) { this.limits=limits;this.decoder=new AgentDecisionDecoder(limits); }

    /** 截断先拒绝整回合；正文始终按正文处理，即使其内容恰好是一个内部动作JSON。 */
    Interpretation interpret(RunRef run, ModelPort.ModelResult.Completed result, FrozenModelRequest request,
            List<AgentExecutionProgram.KnowledgeObservation> knowledge,List<ModelMessage> history) {
        if ("length".equals(result.finishReasonCode())) return rejected("model_output_length");
        if ("content_filter".equals(result.finishReasonCode())) return rejected("model_content_filtered");
        var turn=result.assistantTurn();
        if(turn.isEmpty())return rejected("model_native_turn_missing");
        var calls=turn.orElseThrow().toolCalls();
        if(!List.of("stop","tool_calls").contains(result.finishReasonCode()))return rejected("model_finish_reason_invalid");
        if(calls.isEmpty()) {
            if(!"stop".equals(result.finishReasonCode()))return rejected("model_tool_calls_missing");
            var text=turn.orElseThrow().content();
            if(text.isBlank())return rejected(result.reasoningContent().isPresent()?"model_reasoning_only":"model_response_empty");
            var cited=NativeCitations.resolve(run,text,knowledge,limits);
            if(cited instanceof NativeCitations.Rejected denied)return rejected(denied.code());
            var resolved=(NativeCitations.Resolved)cited;
            return new Interpretation(Optional.of(new AgentDecisionDecoder.Decoded(new AgentAction.Respond(resolved.output(),resolved.evidenceItemIds()),Optional.empty())),Optional.empty());
        }
        if(!"tool_calls".equals(result.finishReasonCode()))return rejected("model_response_inconsistent");
        if(request.toolChoice()==ModelPort.ToolChoice.NONE)return rejected("model_tool_choice_none_violated");
        if(calls.size()>com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH||calls.stream().map(AssistantContent.ToolCall::callId).distinct().count()!=calls.size())return rejected("model_tool_calls_invalid");
        var priorCalls=history.stream().flatMap(message->message.toolCalls().stream()).map(ModelToolCall::callId).collect(java.util.stream.Collectors.toSet());
        if(calls.stream().anyMatch(call->priorCalls.contains(call.callId())))return rejected("model_tool_call_id_reused");
        if(calls.stream().anyMatch(call->request.tools().stream().filter(tool->tool.wireName().equals(call.wireName())).count()!=1))return failed(AgentDecisionDecoder.Reason.TOOL_NOT_AVAILABLE);
        if(calls.size()>1&&calls.stream().anyMatch(call->request.tools().stream().anyMatch(tool->tool.wireName().equals(call.wireName())&&tool.kind()==ModelPort.ModelToolKind.AGENT_ACTION&&!tool.wireName().equals("read_skill_resource"))))return failed(AgentDecisionDecoder.Reason.CONTROL_ACTION_REQUIRES_SINGLE_CALL);
        var actions=new ArrayList<AgentDecisionDecoder.Decoded>();
        for(var call:calls) {
            var matches=request.tools().stream().filter(tool->tool.wireName().equals(call.wireName())).toList();
            if(matches.size()!=1)return failed(AgentDecisionDecoder.Reason.TOOL_NOT_AVAILABLE);
            var declaration=matches.getFirst();
            final TypedPayload arguments;
            try {arguments=decoder.decodeToolArgumentsDocument(new TypedPayload("application/json",call.argumentsJson()));}
            catch(IllegalArgumentException invalid) {
                try {if(!SessionRuntimePayloadJson.parse(call.argumentsJson(),limits).isObject())return failed(AgentDecisionDecoder.Reason.TOOL_ARGUMENTS_NOT_OBJECT);}
                catch(IllegalArgumentException malformed){return failed(AgentDecisionDecoder.Reason.TOOL_ARGUMENTS_INVALID_JSON);}
                return failed(AgentDecisionDecoder.Reason.TOOL_ARGUMENTS_INVALID_JSON);
            }
            final AgentDecisionDecoder.Result decoded;
            if(declaration.kind()==ModelPort.ModelToolKind.TOOL_CALL)decoded=new AgentDecisionDecoder.Decoded(new AgentAction.ToolCall(declaration.toolHandle(),arguments),Optional.empty());
            else decoded=control(declaration,arguments);
            if(decoded instanceof AgentDecisionDecoder.Failure failure)return new Interpretation(Optional.of(failure),Optional.empty());
            if(!(decoded instanceof AgentDecisionDecoder.Decoded single))throw invalid();
            actions.add(single);
        }
        if(actions.size()==1)return new Interpretation(Optional.of(actions.getFirst()),Optional.empty());
        // 固定包资源读取由Session随原MODEL结果采用；剩余业务动作仍走原单工具或持久批次。
        var business=actions.stream().filter(action->action.action() instanceof AgentAction.ToolCall).toList();
        if(business.isEmpty())return new Interpretation(Optional.of(actions.getFirst()),Optional.empty());
        if(business.size()==1)return new Interpretation(Optional.of(business.getFirst()),Optional.empty());
        return new Interpretation(Optional.of(new AgentDecisionDecoder.DecodedBatch(business.stream().map(action->(AgentAction.ToolCall)action.action()).toList(),Optional.empty())),Optional.empty());
    }

    /** 控制类型只来自冻结名称；字段、operation和简写只在此原生入境边界形成内部规范动作。 */
    private AgentDecisionDecoder.Result control(ModelTool declaration,TypedPayload arguments) {
        var value=SessionRuntimePayloadJson.parse(arguments.canonicalJson(),limits);
        var schema=SessionRuntimePayloadJson.parse(declaration.inputSchema().canonicalJson(),limits);
        var properties=schema.path("properties");
        if(!properties.isObject())throw invalid();
        var fields=value.fieldNames();
        while(fields.hasNext())if(!properties.has(fields.next()))return new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_FIELDS_INVALID);
        for(var required:schema.path("required"))if(!required.isTextual()||!value.has(required.textValue()))return new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_FIELDS_INVALID);
        if(value.has("type")||value.has("operation"))return new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_FIELDS_INVALID);
        var mapping=NativeAgentTools.find(declaration.wireName());
        if(mapping.isEmpty())return new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.TOOL_NOT_AVAILABLE);
        var control=mapping.orElseThrow();var decision=AgentExecutionCodec.object();decision.put("type",control.actionType());
        var entries=value.fields();
        while(entries.hasNext()) {
            var entry=entries.next();
            if("commentary".equals(entry.getKey())) {
                if(entry.getValue().isNull())continue;
                if(!entry.getValue().isTextual()||entry.getValue().textValue().isBlank()||entry.getValue().textValue().codePointCount(0,entry.getValue().textValue().length())>600)
                    return new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.CONTROL_COMMENTARY_INVALID);
            }
            decision.set(control.argumentNames().getOrDefault(entry.getKey(),entry.getKey()),entry.getValue());
        }
        try {
            SessionRuntimeJsonSchema.validate(SessionRuntimeJsonSchema.project(schema),value);
            if(control.delegationOperation().isPresent()) {
                String operation=control.delegationOperation().orElseThrow();
                if(control.wireName().equals("delegate_status")&&!decision.has("id"))operation="list";
                decision.put("operation",operation);
                if(control.actionType().equals("delegate")&&decision.has("ids"))decision.set("delegation_ids",decision.remove("ids"));
                if(control.actionType().equals("delegate")&&decision.has("id"))decision.putArray("delegation_ids").add(text(decision.remove("id")));
                if(operation.equals("start")) {
                    if(!decision.has("background"))decision.put("background",false);
                    String instruction=text(decision.remove("instruction"));var task=decision.putObject("task");
                    task.put("objective",instruction);task.put("context","");task.putArray("constraints");task.put("expected_output","");
                }
            }
            var decoded=decoder.decodeWire(SessionRuntimePayloadJson.write(decision));
            return decoded instanceof AgentDecisionDecoder.Failure failure&&failure.reason()==AgentDecisionDecoder.Reason.INVALID_DECISION
                    ?new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_INVALID):decoded;
        } catch(IllegalArgumentException invalid){return new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_INVALID);}
    }

    /** 原生历史沿用真实调用身份；结构化任务继续使用其内部动作身份。 */
    static String callId(AgentExecutionProgram.Materials before,int index) {
        if(before.configuration().profile().response().format() instanceof ModelPort.ResponseFormat.NativeConversation) {
            var calls=before.model().orElseThrow(SessionNativeResponses::invalid).assistantTurn().orElseThrow(SessionNativeResponses::invalid).toolCalls();
            var business=calls.stream().filter(call->!isResourceRead(call.wireName(),before.configuration().modelTools())).toList();
            return (business.isEmpty()?calls:business).get(index).callId();
        }
        return "execution-call:"+before.modelOrdinal()+(before.decision().orElse(null) instanceof AgentDecisionDecoder.DecodedBatch?":"+(index+1):"");
    }

    /** 原生正文与调用参数来自已保存结果，拒绝或恢复不会从内部Respond伪造函数。 */
    static ModelMessage assistant(AgentExecutionProgram.Materials before,List<ModelToolCall> structuredCalls) {
        return assistant(before.model().orElseThrow(SessionNativeResponses::invalid),before.modelOrdinal(),
                before.configuration().profile().response().format() instanceof ModelPort.ResponseFormat.NativeConversation,structuredCalls,before.decision().flatMap(AgentDecisionDecoder.Result::commentary).orElse(""));
    }
    static ModelMessage assistant(ModelPort.ModelResult.Completed result,long ordinal,boolean nativeConversation,List<ModelToolCall> structuredCalls,String structuredCommentary) {
        if(nativeConversation) {
            var turn=result.assistantTurn().orElseThrow(SessionNativeResponses::invalid);
            return new ModelMessage("execution-assistant-"+ordinal,ModelRole.ASSISTANT,turn.content(),
                    turn.toolCalls().stream().map(call->new ModelToolCall(call.callId(),call.wireName(),new TypedPayload("application/json",call.argumentsJson()))).toList(),Optional.empty(),result.reasoningContent(),result.protocolState());
        }
        return new ModelMessage("execution-assistant-"+ordinal,ModelRole.ASSISTANT,result.assistantTurn().map(ModelPort.AssistantTurn::content).orElse(structuredCommentary),structuredCalls,Optional.empty(),result.reasoningContent(),result.protocolState());
    }
    private static boolean isResourceRead(String wireName,List<ModelTool> tools) {
        return tools.stream().anyMatch(tool->tool.wireName().equals(wireName)&&tool.kind()==ModelPort.ModelToolKind.AGENT_ACTION
                &&tool.wireName().equals("read_skill_resource"));
    }

    /** 只消费已经整轮解码通过的原生结果，不从逻辑名称猜其它工具，也不读取当前目录。 */
    List<ResourceRead> resourceReads(ModelPort.ModelResult.Completed result,List<ModelTool> tools) {
        var reads=new ArrayList<ResourceRead>();
        for(var call:result.assistantTurn().orElseThrow(SessionNativeResponses::invalid).toolCalls()) {
            if(!isResourceRead(call.wireName(),tools))continue;
            var declaration=tools.stream().filter(tool->tool.wireName().equals(call.wireName())&&tool.kind()==ModelPort.ModelToolKind.AGENT_ACTION)
                    .findFirst().orElseThrow(SessionNativeResponses::invalid);
            var decoded=control(declaration,decoder.decodeToolArgumentsDocument(new TypedPayload("application/json",call.argumentsJson())));
            if(!(decoded instanceof AgentDecisionDecoder.Decoded single)||!(single.action() instanceof AgentAction.ReadSkillResource read))throw invalid();
            reads.add(new ResourceRead(call.callId(),read));
        }
        return List.copyOf(reads);
    }
    record ResourceRead(String callId,AgentAction.ReadSkillResource action) { }

    /** 资源回执和真实业务回执按原assistant调用次序一次性闭合，不发布半组。 */
    static List<ModelMessage> toolResults(AgentExecutionProgram.Materials before,List<ModelMessage> business,CodecLimits limits) {
        if(!(before.configuration().profile().response().format() instanceof ModelPort.ResponseFormat.NativeConversation))return List.copyOf(business);
        var completed=before.model().orElseThrow(SessionNativeResponses::invalid);
        var reads=new SessionNativeResponses(limits).resourceReads(completed,before.configuration().modelTools());
        if(reads.isEmpty())return List.copyOf(business);
        var byId=new java.util.HashMap<String,ModelMessage>();
        for(var message:business)if(byId.put(message.toolCallId().orElseThrow(SessionNativeResponses::invalid),message)!=null)throw invalid();
        var skills=new AgentExecutionSkills(before.configuration().skills(),before.configuration().availableCapabilities(),limits);
        for(var read:reads) {
            var transition=skills.apply(before.skills(),read.action());
            var message=new ModelMessage("execution-skill-"+before.modelOrdinal()+":"+read.callId(),ModelRole.TOOL,transition.feedback(),List.of(),Optional.of(read.callId()),Optional.empty());
            if(byId.put(read.callId(),message)!=null)throw invalid();
        }
        var ordered=new ArrayList<ModelMessage>();
        for(var call:completed.assistantTurn().orElseThrow(SessionNativeResponses::invalid).toolCalls()) {
            var message=byId.remove(call.callId());if(message==null)throw invalid();ordered.add(message);
        }
        if(!byId.isEmpty())throw invalid();
        return List.copyOf(ordered);
    }
    private static String text(JsonNode value){if(value==null||!value.isTextual()||value.textValue().isBlank())throw invalid();return value.textValue();}
    private static Interpretation rejected(String reason){return new Interpretation(Optional.empty(),Optional.of(reason));}
    private static Interpretation failed(AgentDecisionDecoder.Reason reason){return new Interpretation(Optional.of(new AgentDecisionDecoder.Failure(reason)),Optional.empty());}
    record Interpretation(Optional<AgentDecisionDecoder.Result> decision,Optional<String> problem) { }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("native_response_invalid");}
}
