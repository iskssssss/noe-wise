package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.institution.execution.WorkflowScriptSandboxClient;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.time.Instant;
import java.util.Optional;
import java.util.TreeMap;

/** 完整沙箱请求只存在于原任务ledger；节点请求保存其精确引用及本地输出契约。 */
public final class WorkflowScriptNodeCodec {
    public static final String REQUEST_SCHEMA="skillforge.runtime.execution.workflow-script-request.v1";
    private final ObjectMapper json;
    private final AgentRuntimePayloadValidation payloads;
    public WorkflowScriptNodeCodec(ObjectMapper json,AgentRuntimePayloadValidation payloads) {
        this.json=com.skillforge.institution.runtime.SessionSubjectJson.configure(json.copy()).setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.payloads=payloads;
    }
    public record Reference(String taskId,String requestFingerprint,TypedPayload outputSchema,Instant deadlineAt) { }
    public TypedPayload reference(JdbcWorkflowScriptCalls.Preparation preparation,NodeExecutionContext node) {
        try {
            return encode(new Reference(preparation.taskId(),fingerprint(preparation),new TypedPayload("json-schema",
                    json.writeValueAsString(node.node().args().getOrDefault("output_schema",java.util.Map.of("type","object")))),preparation.deadlineAt()));
        } catch(java.io.IOException invalid){throw rejected();}
    }
    public Reference read(TypedPayload payload) {
        if(!REQUEST_SCHEMA.equals(payload.schemaId()))throw rejected();
        try {var reference=json.readValue(payload.canonicalJson(),Reference.class);if(!encode(reference).equals(payload))throw rejected();return reference;}
        catch(java.io.IOException invalid){throw rejected();}
    }
    private TypedPayload encode(Reference reference) {
        try{return new TypedPayload(REQUEST_SCHEMA,json.writeValueAsString(reference));}catch(java.io.IOException invalid){throw rejected();}
    }
    public void require(TypedPayload reference,JdbcWorkflowScriptCalls.Preparation preparation) {
        var value=read(reference);if(!value.taskId().equals(preparation.taskId())||!value.requestFingerprint().equals(fingerprint(preparation))
                ||!value.deadlineAt().equals(preparation.deadlineAt()))throw rejected();
    }
    public TypedPayload status(WorkflowScriptSandboxClient.Status actual) {
        try{return new TypedPayload("application/json",json.writeValueAsString(new TreeMap<>(java.util.Map.of("kind","workflow-script-status","status",actual))));}catch(java.io.IOException invalid){throw rejected();}
    }
    public TypedPayload rejection(TypedPayload actual) {
        try{return new TypedPayload("application/json",json.writeValueAsString(new TreeMap<>(java.util.Map.of("kind","workflow-script-rejection","rejection",actual))));}catch(java.io.IOException invalid){throw rejected();}
    }
    public WorkflowScriptSandboxClient.Status status(TypedPayload actual) {
        if(!"application/json".equals(actual.schemaId()))throw rejected();
        try{var root=json.readTree(actual.canonicalJson());if(root.size()!=2||!"workflow-script-status".equals(root.path("kind").asText()))throw rejected();
            var status=json.treeToValue(root.get("status"),WorkflowScriptSandboxClient.Status.class);if(!status(status).equals(actual))throw rejected();return status;}
        catch(java.io.IOException invalid){throw rejected();}
    }
    public Optional<TypedPayload> feedback(TypedPayload request,ToolOutcome actual) {
        var reference=read(request);
        if(!(actual instanceof ToolOutcome.Succeeded success))return Optional.empty();
        try{if("workflow-script-rejection".equals(json.readTree(success.observation().canonicalJson()).path("kind").asText()))throw outputRejected();}
        catch(java.io.IOException invalid){throw rejected();}
        var status=status(success.observation());
        if(!reference.taskId().equals(status.taskId()))throw rejected();
        if(status.state()!=WorkflowScriptSandboxClient.State.COMPLETED)throw outputRejected();
        var output=status.jsonResult().orElseThrow(WorkflowScriptNodeCodec::outputRejected);
        if(!payloads.accepts(reference.outputSchema(),output))throw outputRejected();
        return Optional.of(output);
    }
    public static String fingerprint(JdbcWorkflowScriptCalls.Preparation preparation) {
        return AgentExecutionCodec.digest(preparation.request().schemaId()+"\0"+preparation.request().canonicalJson());
    }
    public static final class OutputRejected extends IllegalArgumentException {private OutputRejected(){super("workflow_script_output_schema_rejected");}}
    private static OutputRejected outputRejected(){return new OutputRejected();}
    private static IllegalArgumentException rejected(){return new IllegalArgumentException("workflow_script_node_material_invalid");}
}
