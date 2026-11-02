package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import com.skillforge.core.workflow.execution.node.WorkflowExpressions;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.core.workflow.runtime.WorkflowRuntimeBindings;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionTime;
import com.skillforge.runtime.session.WorkflowNodeProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.ibatis.session.SqlSession;

/** 节点请求由真实Engine状态和已安装槽生成；同事务guard重建同一请求，不接受DTO自证。 */
public final class WorkflowNodeRequests {
    public static final String SCHEMA = "skillforge.runtime.execution.workflow-node.v1";
    private final ObjectMapper json;
    private final AgentRuntimePayloadValidation payloads;
    private final AgentRuntimeModelConfiguration profiles;
    private final JdbcModelBindings models;
    private final AgentExecutionCodec codec;
    private final AgentRuntimeWorkflowScripts scripts;
    private final WorkflowScriptNodeCodec scriptCodec;
    private final java.time.Clock clock;
    private final SessionTokenCounter tokens;
    public WorkflowNodeRequests(ObjectMapper json, AgentRuntimePayloadValidation payloads,
            AgentRuntimeModelConfiguration profiles, JdbcModelBindings models, CodecLimits limits,AgentRuntimeWorkflowScripts scripts,WorkflowScriptNodeCodec scriptCodec,
            java.time.Clock clock, SessionTokenCounter tokens) {
        this.json = com.skillforge.institution.runtime.SessionSubjectJson.configure(json.copy()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.payloads = Objects.requireNonNull(payloads); this.profiles = Objects.requireNonNull(profiles); this.models = Objects.requireNonNull(models);
        codec = new AgentExecutionCodec(limits);
        this.scripts=Objects.requireNonNull(scripts);this.scriptCodec=Objects.requireNonNull(scriptCodec);
        this.clock=Objects.requireNonNull(clock);this.tokens=Objects.requireNonNull(tokens);
    }
    /** 仅保存节点原身份、原Profile引用及唯一实际请求，不保存Execution状态或Provider许可。 */
    public record Frozen(WorkflowNodeCallIdentity node, ResultRef profile, ExecutionStore.Kind kind, String target,
            ResultRef capability, ExecutionStore.Material request, ExecutionBudgetPort.Category category,
            ExecutionBudgetPort.Resources resources, Optional<TypedPayload> extraParameters) {
        public Frozen {
            Objects.requireNonNull(node); Objects.requireNonNull(profile); Objects.requireNonNull(kind); Objects.requireNonNull(target);
            Objects.requireNonNull(capability); Objects.requireNonNull(request); Objects.requireNonNull(category); Objects.requireNonNull(resources); Objects.requireNonNull(extraParameters);
        }
        @Override public String toString() { return "FrozenWorkflowNode[kind=" + kind + ",REDACTED]"; }
    }
    public Frozen freeze(WorkflowNodeSource.Source source, ResultRef profile, Optional<TypedPayload> extraParameters) {
        SessionPreparations.Profile modelProfile = null; JdbcModelBindings.Binding model = null;
        if ("llm".equals(source.node().node().type())) {
            modelProfile = profiles.preparationProfile(source.identity().executionRef().tenantId(), profile).orElseThrow(WorkflowNodeRequests::rejected);
            model = models.findForOwner(modelProfile.binding(), source.identity().owner().agentId()).orElseThrow(WorkflowNodeRequests::rejected);
        }
        var script="script".equals(source.node().node().type())?scripts.find(source.inputs().registered().orElseThrow().registered().actor(),source.identity()).orElseThrow(WorkflowNodeRequests::rejected).preparation():null;
        return build(source, profile, extraParameters, modelProfile, model,script,
                modelProfile==null?Optional.empty():Optional.of(AgentExecutionTime.sample(clock)));
    }
    public void verifyBorrowed(SqlSession session, ExecutionStore.Run parent, WorkflowNodeSource.Source source, ExecutionStore.Admission admission) {
        var frozen = read(admission.configurationMaterials().stream().filter(value -> value.reference().equals(admission.configuration())).findFirst().orElseThrow(WorkflowNodeRequests::rejected));
        if (!frozen.node().equals(source.identity()) || !frozen.profile().equals(parent.configuration())) throw rejected();
        var rebuilt = rebuildBorrowed(session, source, frozen);
        if (!rebuilt.equals(frozen) || !admission.input().equals(frozen.request())
                || !admission.context().equals(WorkflowNodeProgram.initial(admission.run().runRef()))) throw rejected();
    }
    public Frozen rebuildBorrowed(SqlSession session, WorkflowNodeSource.Source source, Frozen frozen) {
        SessionPreparations.Profile profile = null; JdbcModelBindings.Binding model = null;
        if (frozen.kind() == ExecutionStore.Kind.MODEL) {
            profile = JdbcSessionPreparations.profileBorrowed(session, source.identity().executionRef().tenantId(), frozen.profile()).orElseThrow(WorkflowNodeRequests::rejected);
            model = JdbcModelBindings.findBorrowed(session, profile.binding(), source.identity().owner().agentId()).orElseThrow(WorkflowNodeRequests::rejected);
        }
        var script="script".equals(source.node().node().type())?com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls.findBorrowed(session,source.identity()).orElseThrow(WorkflowNodeRequests::rejected).preparation():null;
        var time=frozen.kind()==ExecutionStore.Kind.MODEL
                ?Optional.of(AgentExecutionTime.frozen(codec.decodeModelRequest(frozen.request().content()).request().messages(),1))
                :Optional.<AgentExecutionTime.Sample>empty();
        return build(source, frozen.profile(), frozen.extraParameters(), profile, model,script,time);
    }
    private Frozen build(WorkflowNodeSource.Source source, ResultRef profileRef, Optional<TypedPayload> extra,
            SessionPreparations.Profile profile, JdbcModelBindings.Binding model,com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls.Preparation script,
            Optional<AgentExecutionTime.Sample> time) {
        var node = source.node(); var identity = WorkflowNodeSource.runIdentity(source.identity());
        var bindings = bindings(source); ExecutionStore.Kind kind; String target; ResultRef capability;
        TypedPayload request; ExecutionBudgetPort.Category category; ExecutionBudgetPort.Resources resources;
        switch (node.node().type()) {
            case "script" -> {
                Objects.requireNonNull(script);scripts.requireFrozenSource(source.execution(),source.inputs(),script,node);
                kind=ExecutionStore.Kind.TOOL;target="workflow-script:"+script.taskId();capability=new ResultRef("workflow-node-capability:"+WorkflowNodeSource.identityDigest(source.identity()));
                request=scriptCodec.reference(script,node);category=ExecutionBudgetPort.Category.TOOL;resources=ExecutionBudgetPort.Resources.call(category,0,0);
            }
            case "tool" -> {
                var definition = object(source.inputs().binding().definitionJson());
                String slot = WorkflowDefinitionContract.tools(definition).get(node.node().tool());
                var selected = bindings.tools().stream().filter(value -> value.slot().equals(slot)).findFirst().orElseThrow(WorkflowNodeRequests::rejected);
                var arguments = payload(WorkflowExpressions.interpolateValue(node.node().args() == null ? Map.of() : node.node().args(), node));
                if (!payloads.accepts(selected.target().inputSchema(), arguments)) throw rejected();
                kind = ExecutionStore.Kind.TOOL; target = selected.target().handle().value();
                capability = new ResultRef("workflow-node-capability:" + WorkflowNodeSource.identityDigest(source.identity()));
                request = codec.encodeTool(new AgentExecutionCodec.ToolRequest(selected.target(), selected.binding(), arguments,
                        "workflow-node-call:" + WorkflowNodeSource.identityDigest(source.identity()), selected.binding().toolName()));
                category = ExecutionBudgetPort.Category.TOOL; resources = ExecutionBudgetPort.Resources.call(category, 0, 0);
            }
            case "knowledge_query" -> {
                var spec = node.node().external();
                var selected = bindings.knowledge().stream().filter(value -> value.slot().equals(spec.requirement())).findFirst().orElseThrow(WorkflowNodeRequests::rejected).target();
                var budget = spec.budget(); var sources = selected.sourceVersions().stream().map(KnowledgeSourceVersion::sourceId).collect(Collectors.toSet());
                if (sources.size() > positive(budget, "max_sources")) throw rejected();
                var query = new KnowledgeRequest("workflow-node:" + WorkflowNodeSource.identityDigest(source.identity()), identity.runRef().tenantId(), identity.requester(),
                        identity.agentId(), identity.owner().sessionRef(), identity.trace(), KnowledgeMode.GROUNDING, "workflow_node_query",
                        new com.skillforge.runtime.api.KnowledgeOperation.Search(WorkflowExpressions.interpolate(Objects.requireNonNull(spec.query()), node.config(), node.state(), node.extracted(), node.extraVars())), sources,
                        selected.sourceVersions(), selected.allowedOperators(), new KnowledgeBudget(1, positive(budget,"max_items"), positive(budget,"max_tokens"),
                        positive(budget,"max_characters"), positive(budget,"timeout_ms")), source.execution().createdAt(), Optional.empty(), true);
                kind = ExecutionStore.Kind.KNOWLEDGE; target = selected.handle().value();
                capability = new ResultRef("workflow-node-capability:" + WorkflowNodeSource.identityDigest(source.identity()));
                request = codec.encodeKnowledgeRequest(new AgentExecutionCodec.KnowledgeCallRequest(selected, query, source.inputs().snapshot(),
                        new EventId("workflow-node-source:" + WorkflowNodeSource.identityDigest(source.identity())), "workflow-node-call:" + WorkflowNodeSource.identityDigest(source.identity()),
                        "knowledge_query", new TypedPayload("application/json", "{}")));
                category = ExecutionBudgetPort.Category.KNOWLEDGE; resources = ExecutionBudgetPort.Resources.call(category,0,0);
            }
            case "llm" -> {
                Objects.requireNonNull(profile); Objects.requireNonNull(model);
                if (!model.strategy().equals(profile.strategy())) throw rejected();
                var messages = AgentExecutionTime.append(AgentRuntimeWorkflowPrompts.messages(source.inputs().customPrompt(), node, json),time.orElseThrow(WorkflowNodeRequests::rejected),1);
                if (messages.stream().mapToLong(value -> value.accountingText().codePointCount(0,value.accountingText().length())).sum() > profile.maxCharacters()) throw rejected();
                var measured=tokens.measure(profile.tokenizerProfileRef(),model.target(),messages,List.of());
                if(!measured.messages().equals(messages)||!measured.tools().isEmpty()
                        ||!measured.profile().reference().equals(profile.tokenizerProfileRef())
                        ||!measured.profile().target().equals(model.target())||measured.totalTokens()>profile.maxTokens())throw rejected();
                var response = new ResponseContract(node.node().extract() != null && !node.node().extract().isEmpty() ? new ResponseFormat.JsonObject() : new ResponseFormat.Text(),
                        EnforcementLevel.STRICT, "workflow_node_output");
                var frozen = new FrozenModelRequest(profile.strategy(), model.target(), messages, response, profile.generation(), profile.reasoning());
                kind = ExecutionStore.Kind.MODEL; target = model.target().effectiveModelId(); capability = model.target().capabilities().capabilityFactRef();
                request = codec.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(frozen, List.of(),0, Optional.empty()));
                category = ExecutionBudgetPort.Category.BUSINESS_MODEL;
                resources = ExecutionBudgetPort.Resources.call(category, measured.totalTokens(), profile.generation().maxOutputTokens().orElseThrow());
            }
            default -> throw rejected();
        }
        var material = new ExecutionStore.Material(new ResultRef("workflow-node-request:" + WorkflowNodeSource.identityDigest(source.identity())), request);
        return new Frozen(source.identity(), profileRef, kind, target, capability, material, category, resources, extra);
    }
    public ExecutionStore.Material encode(Frozen frozen) {
        try { var content = new TypedPayload(SCHEMA, json.writeValueAsString(frozen)); if (!payloads.accepts(new TypedPayload("application/json", content.canonicalJson()))) throw rejected();
            return new ExecutionStore.Material(reference(WorkflowNodeSource.runIdentity(frozen.node()).runRef()), content);
        } catch (java.io.IOException invalid) { throw rejected(); }
    }
    public Frozen read(ExecutionStore.Material material) {
        if (!SCHEMA.equals(material.content().schemaId()) || !payloads.accepts(new TypedPayload("application/json",material.content().canonicalJson()))) throw rejected();
        try { var frozen = json.readValue(material.content().canonicalJson(), Frozen.class); if (!encode(frozen).equals(material)) throw rejected(); return frozen; }
        catch (java.io.IOException invalid) { throw rejected(); }
    }
    public static ResultRef reference(RunRef run) { return new ResultRef("workflow-node-configuration:" + run.runId()); }
    public Optional<TypedPayload> scriptFeedback(TypedPayload request,com.skillforge.runtime.api.port.ToolPortal.ToolOutcome actual){return scriptCodec.feedback(request,actual);}
    private WorkflowRuntimeBindings bindings(WorkflowNodeSource.Source source) {
        try { return json.readValue(source.inputs().binding().resolvedBindingsJson(), WorkflowRuntimeBindings.class); }
        catch (java.io.IOException invalid) { throw rejected(); }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> object(String value) {
        try { return json.readValue(value, Map.class); } catch (java.io.IOException invalid) { throw rejected(); }
    }
    private TypedPayload payload(Object value) {
        try { return new TypedPayload("application/json",json.writeValueAsString(value)); } catch(java.io.IOException invalid) { throw rejected(); }
    }
    private static int positive(Map<String,Object> values,String key) {
        var value=values.get(key); if (!(value instanceof Number number) || number.doubleValue()!=number.intValue() || number.intValue()<=0) throw rejected(); return number.intValue();
    }
    private static ExecutionStore.Rejection rejected() { return new ExecutionStore.Rejection("workflow_node_request_rejected"); }
}
