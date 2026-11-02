package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.ResultRef;
import java.util.function.Function;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** typed实际请求与结果的纯私有诊断读取器；只输出显式模型可见字段，不输出target/binding/credential。 */
public final class ExecutionModelAttemptInspector {
    private final CodecLimits limits;
    private final AgentExecutionCodec codec;
    /** 配额由宿主配置提供，诊断与正式恢复复用唯一实际请求/结果codec。 */
    public ExecutionModelAttemptInspector(CodecLimits limits) {this.limits=Objects.requireNonNull(limits);codec=new AgentExecutionCodec(limits);}
    /** 宿主只传解析预算，不依赖 Runtime Core 的内部限制类型。 */
    public ExecutionModelAttemptInspector(int maxCodeUnits, int maxNodes, int maxDepth) {
        this(new CodecLimits(maxCodeUnits, maxNodes, maxDepth));
    }

    /** 所属Host先核完整Run/Action/Attempt/material引用闭包；此处只解析精确当前协议。 */
    public Inspection inspect(TypedPayload request,Optional<TypedPayload> result) {
        return inspect(request, result, references -> references.stream().map(ignored -> Optional.<ExecutionStore.Material>empty()).toList());
    }

    /** 叶材料只按原manifest引用批读；读取器仍由已核验租户/Run范围的宿主提供。 */
    public Inspection inspect(TypedPayload request, Optional<TypedPayload> result,
            Function<List<ResultRef>, List<Optional<ExecutionStore.Material>>> materials) {
        var frozen=codec.decodeModelRequest(request, materials).request();var root=JsonNodeFactory.instance.objectNode();
        var messages=root.putArray("messages");
        for(var message:frozen.messages()) {
            var value=messages.addObject();value.put("message_id",message.messageId());value.put("role",message.role().name().toLowerCase(Locale.ROOT));value.put("content",message.textContent());
            var contents=value.putArray("contents");for(var part:message.contents()) {
                var item=contents.addObject();
                if(part instanceof ModelContent.Text text) {item.put("type","text");item.put("text",text.text());}
                else {var image=(ModelContent.Image)part;item.put("type","image");item.put("media_id",image.media().id());item.put("mime_type",image.media().mimeType());
                    item.put("byte_length",Long.toString(image.media().byteLength()));item.put("width",image.media().width());item.put("height",image.media().height());item.put("detail",image.detail().name());}
            }
            var calls=value.putArray("tool_calls");for(var call:message.toolCalls()) {var item=calls.addObject();item.put("call_id",call.callId());item.put("wire_name",call.wireName());item.put("arguments_schema",call.arguments().schemaId());item.put("arguments",call.arguments().canonicalJson());}
            if(message.toolCallId().isPresent())value.put("tool_call_id",message.toolCallId().orElseThrow());else value.putNull("tool_call_id");
            if(message.reasoningContent().isPresent())value.put("reasoning_content",message.reasoningContent().orElseThrow());else value.putNull("reasoning_content");
        }
        var tools=root.putArray("tools");for(var tool:frozen.tools()) {var item=tools.addObject();item.put("wire_name",tool.wireName());item.put("description",tool.description());item.put("input_schema",tool.inputSchema().canonicalJson());item.put("strict",tool.strict());item.put("kind",tool.kind().name());}
        var generation=root.putObject("generation");if(frozen.generation().maxOutputTokens().isPresent())generation.put("max_output_tokens",frozen.generation().maxOutputTokens().getAsInt());else generation.putNull("max_output_tokens");
        if(frozen.generation().temperature().isPresent())generation.put("temperature",frozen.generation().temperature().getAsDouble());else generation.putNull("temperature");
        var response=root.putObject("response");response.put("format",switch(frozen.response().format()) {
            case ModelPort.ResponseFormat.Text ignored->"TEXT";case ModelPort.ResponseFormat.JsonObject ignored->"JSON_OBJECT";
            case ModelPort.ResponseFormat.JsonSchema ignored->"JSON_SCHEMA";case ModelPort.ResponseFormat.NativeConversation ignored->"NATIVE_CONVERSATION";});
        if(frozen.response().format() instanceof ModelPort.ResponseFormat.JsonSchema schema)response.put("schema",schema.schema().canonicalJson());else response.putNull("schema");
        response.put("enforcement",frozen.response().enforcement().name());response.put("negotiation_reason_code",frozen.response().negotiationReasonCode());
        root.put("reasoning",frozen.reasoning().name());root.put("tool_choice",frozen.toolChoice().name());
        var actual=result.map(codec::decodeModelOutcome);
        if(actual.orElse(null) instanceof ModelPort.ModelResult.Completed completed) {
            if(!completed.effectiveModelId().equals(frozen.target().effectiveModelId())||frozen.reasoning()==ModelPort.ReasoningPolicy.DISABLED&&completed.reasoningContent().isPresent())throw invalid();
        }
        return new Inspection(root,actual,frozen.target().effectiveModelId());
    }

    /** 各实际能力只暴露其正式业务参数与安全结果，不把内部snapshot或目标配置交给平台DTO。 */
    public Capability inspectCapability(ExecutionStore.Kind kind,TypedPayload request,Optional<TypedPayload> result) {
        if(kind==ExecutionStore.Kind.TOOL) {
            var call=codec.decodeTool(request);var parameters=codec.parse(call.arguments(),call.arguments().schemaId());if(!parameters.isObject())throw invalid();
            var redacted=new ArrayList<String>();for(var field:call.target().sensitiveFields().stream().sorted().toList())
                if(((com.fasterxml.jackson.databind.node.ObjectNode)parameters).remove(field)!=null)redacted.add(field);
            var input=new TypedPayload(call.arguments().schemaId(),parameters.toString());
            Optional<TypedPayload> output=result.flatMap(actual->{var decoded=codec.decodeToolOutcome(actual);
                return decoded instanceof ToolPortal.ToolOutcome.Succeeded success?Optional.of(SessionRuntimePayloads.safeToolOutput(call.target().outputSchema(),success.observation(),call.target().sensitiveFields(),limits)):Optional.empty();});
            return new Capability(call.callId(),call.wireName(),input,output,List.copyOf(redacted));
        }
        if(kind==ExecutionStore.Kind.KNOWLEDGE) {
            var call=codec.decodeKnowledgeRequest(request);result.ifPresent(codec::decodeKnowledgeOutcome);
            return new Capability(call.callId(),call.wireName(),call.arguments(),result.map(actual->codec.decodeOutcome(actual).outcome()),List.of());
        }
        if(kind==ExecutionStore.Kind.DELEGATION) {
            var boundary=new SessionExecutionDelegations(limits);var call=boundary.decodeRequest(request);
            return new Capability(call.callId(),call.wireName(),new AgentDecisionDecoder(limits).encode(new AgentAction.Delegate(call.command())),result.map(actual->boundary.decodeResult(actual).value()),List.of());
        }
        if(kind==ExecutionStore.Kind.WORKFLOW) {
            var boundary=new SessionExecutionWorkflows(limits);var call=boundary.decode(request);String id=null,name="workflow";TypedPayload args=new TypedPayload("application/json","{}");
            if(call instanceof SessionExecutionWorkflows.Dispatch dispatch){id=dispatch.callId();name=dispatch.wireName();args=dispatch.arguments();}
            else if(call instanceof SessionExecutionWorkflows.Resume resume){id=resume.callId();name=resume.wireName();args=resume.arguments();}
            return new Capability(id,name,args,result.map(actual->codec.decodeOutcome(boundary.decodeResult(actual).outcome()).outcome()),List.of());
        }
        throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("execution_diagnostic_source_invalid");}
    /** 只读安全模型投影和该Attempt实际结果；不表示该结果已被选定或采纳。 */
    public record Inspection(JsonNode input,Optional<ModelPort.ModelResult> outcome,String modelId) {
        @Override public String toString(){return "ExecutionModelInspection[REDACTED]";}
    }
    /** 实际能力的正式参数与脱敏结果；redactedFields非空说明输入只可标记PARTIAL。 */
    public record Capability(String callId,String title,TypedPayload input,Optional<TypedPayload> output,List<String> redactedFields) {
        @Override public String toString(){return "ExecutionCapabilityInspection[REDACTED]";}
    }
}
