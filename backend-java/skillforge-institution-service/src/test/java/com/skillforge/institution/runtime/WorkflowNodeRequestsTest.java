package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.NodeCallIdentity;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.core.workflow.runtime.WorkflowRuntimeBindings;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolHandle;
import com.skillforge.runtime.api.port.WorkflowNodeCallIdentity;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.WorkflowNodeProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

class WorkflowNodeRequestsTest {
    @Test
    void equivalentNumbersFromLiveOutputAndCheckpointKeepTheSameFrozenRequest() throws Exception {
        var fixture = new Fixture();
        var frozen = fixture.requests.freeze(fixture.source(1.0d), fixture.profile, Optional.empty());
        var admission = fixture.admission(frozen);
        var stored = fixture.requests.read(admission.configurationMaterials().getFirst());
        var rebuilt = fixture.requests.rebuildBorrowed(fixture.sql, fixture.source(BigInteger.ONE), stored);

        assertEquals(fixture.arguments(stored), fixture.arguments(rebuilt),
                "工具原始结果 1.0 与 checkpoint 中的 1 必须冻结为同一参数");
        assertDoesNotThrow(() -> fixture.requests.verifyBorrowed(
                fixture.sql, fixture.parent, fixture.source(BigInteger.ONE), admission));
    }

    @Test
    void fractionalNumbersAndNumericStringsKeepTheirValuesAcrossRequestRebuild() throws Exception {
        var fixture = new Fixture();
        for (Object value : List.of(1.25d, "1", new BigDecimal("0.12345678901234567890123456789"))) {
            Object checkpointValue = value instanceof Double ? new BigDecimal("1.25") : value;
            var frozen = fixture.requests.freeze(fixture.source(value), fixture.profile, Optional.empty());
            var admission = fixture.admission(frozen);
            var rebuilt = fixture.requests.rebuildBorrowed(fixture.sql, fixture.source(checkpointValue), frozen);

            assertEquals(fixture.arguments(frozen), fixture.arguments(rebuilt));
            var actual = fixture.json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(fixture.arguments(rebuilt).canonicalJson()).path("value").path("quantity");
            if (value instanceof String text) assertEquals(text, actual.textValue());
            else assertEquals(0, new BigDecimal(value.toString()).compareTo(actual.decimalValue()));
            assertDoesNotThrow(() -> fixture.requests.verifyBorrowed(
                    fixture.sql, fixture.parent, fixture.source(checkpointValue), admission));
        }
    }

    @Test
    void changedNumberOrNumberTypeStillRejectsTheOriginalFrozenRequest() throws Exception {
        var fixture = new Fixture();
        var frozen = fixture.requests.freeze(fixture.source(BigInteger.ONE), fixture.profile, Optional.empty());
        var admission = fixture.admission(frozen);

        for (Object changed : List.of(BigInteger.TWO, "1")) {
            var failure = assertThrows(ExecutionStore.Rejection.class, () -> fixture.requests.verifyBorrowed(
                    fixture.sql, fixture.parent, fixture.source(changed), admission));
            assertEquals("workflow_node_request_rejected", failure.getMessage());
        }
    }

    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final CodecLimits limits = new CodecLimits(100_000, 10_000, 100);
        final AgentExecutionCodec codec = new AgentExecutionCodec(limits);
        final SqlSession sql = mock(SqlSession.class);
        final ExecutionStore.Run parent = mock(ExecutionStore.Run.class);
        final ExecutionRecord execution = mock(ExecutionRecord.class);
        final ResultRef profile = new ResultRef("parent-profile");
        final TenantId tenant = new TenantId("1");
        final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(1));
        final InputId input = new InputId("input");
        final RunIdentity identity = new RunIdentity(owner, new AgentId("agent"), input, new RunRef(tenant, "parent"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verification"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, "input:input"));
        final WorkflowNodeCallIdentity node = new WorkflowNodeCallIdentity(identity, new ExecutionRef(tenant, "execution"), "validate", 1, 1);
        final Map<String, Object> definition = Map.of("entry", "validate", "meta", Map.of(
                "tools", List.of(Map.of("name", "validate", "requirement", "validator")),
                "capability_requirements", List.of(Map.of("slot", "validator", "type", "tool", "required", true,
                        "capability_id", "tool:json_schema_validate", "version_constraint", "*"))),
                "nodes", List.of(Map.of("id", "validate", "type", "tool", "tool", "validate",
                        "args", Map.of("value", "${state.query}"))), "edges", List.of());
        final WorkflowNodeRequests requests;
        final SdkWorkflowPortal.NodeInputs inputs;

        Fixture() throws Exception {
            var payloads = new AgentRuntimePayloadValidation() {
                @Override public boolean accepts(TypedPayload payload) {
                    new SessionRuntimePayloads().validate(payload, limits);
                    return true;
                }
                @Override public boolean accepts(TypedPayload schema, TypedPayload payload) {
                    SessionRuntimePayloads.validateAgainstSchema(schema, payload, limits);
                    return true;
                }
            };
            requests = new WorkflowNodeRequests(json, payloads, mock(AgentRuntimeModelConfiguration.class),
                    mock(JdbcModelBindings.class), limits, mock(AgentRuntimeWorkflowScripts.class),
                    new WorkflowScriptNodeCodec(json, payloads), Clock.systemUTC(), mock(SessionTokenCounter.class));
            var tool = new FrozenToolTarget(new ToolHandle("tool:validator"), new CapabilityRef(CapabilityType.TOOL, "validator", "v1"),
                    "deployment", 1, new TypedPayload("json-schema", "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{}},\"additionalProperties\":false}"),
                    new TypedPayload("json-schema", "{}"), ToolEffect.READ_ONLY, true, Duration.ofSeconds(5), true,
                    ToolConfirmationPolicy.NEVER, Set.of(), Set.of("platform_handler:json_schema_validate"), true);
            var bindings = new WorkflowRuntimeBindings(List.of(new WorkflowRuntimeBindings.ToolSlot("validator", tool,
                    new ToolBinding(ToolBinding.ProviderType.PLATFORM, "json_schema_validate", "json_schema_validate"))), List.of(), List.of(), List.of());
            var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:root"), "instance", 1,
                    new CapabilityRef(CapabilityType.WORKFLOW, "workflow", "v1"), Duration.ofSeconds(30), 1);
            var binding = new JdbcWorkflowBindings.Binding(target, "workflow", json.writeValueAsString(definition), "{}",
                    Optional.empty(), "{}", "{}", json.writeValueAsString(bindings));
            inputs = new SdkWorkflowPortal.NodeInputs(target, new CapabilitySnapshotRef(owner.sessionRef(), input, "snapshot", 1),
                    new TypedPayload("application/json", "{}"), List.of(), Optional.empty(), binding);
            when(parent.configuration()).thenReturn(profile);
        }

        WorkflowNodeSource.Source source(Object quantity) {
            var workflow = new WorkflowParser().parse("workflow", definition);
            var context = new NodeExecutionContext(workflow, workflow.nodes().getFirst(), Map.of(),
                    Map.of("query", Map.of("id", "N-1", "quantity", quantity)), Map.of(), "", List.of(), "instance", tenant.value(),
                    List.of(), node.executionRef().executionId(), null, owner.sessionRef().sessionId(), Map.of(), null,
                    new NodeCallIdentity(node.executionRef().executionId(), node.nodeId(), node.visit(), node.call()));
            return new WorkflowNodeSource.Source(execution, node, inputs, context);
        }

        ExecutionStore.Admission admission(WorkflowNodeRequests.Frozen frozen) {
            var material = requests.encode(frozen);
            var child = WorkflowNodeSource.runIdentity(node);
            var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                    Set.of("tool.invoke"), 1, identity.causationIdentityRef());
            var budget = new ExecutionBudgetPort.Registration(child.runRef(), identity.runRef(), ExecutionBudgetPort.Role.MEMBER,
                    new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, "test-policy"));
            return new ExecutionStore.Admission(actor, child, AgentExecutionCodec.digest(material.content().canonicalJson()),
                    material.reference(), frozen.request(), WorkflowNodeProgram.initial(child.runRef()), budget, List.of(), List.of(material));
        }

        TypedPayload arguments(WorkflowNodeRequests.Frozen frozen) {
            return codec.decodeTool(frozen.request().content()).arguments();
        }
    }
}
