package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.session.spi.SessionModelProjections.Policy;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelDecisionProjectionPolicyTest {
    @Test void actualSelfReplacingPlanBecomesSafeReasonBeforeItsRawPlanIsDiscarded() {
        String raw = """
                {"type":"plan","operation":"submit","work_id":"work","expected_revision":2,
                 "plan":{"title":"private-title","change_reason":"修订","approval_required":false,"tasks":[
                 {"id":"step2","title":"private-task","executor":{"type":"primary","target_handle":""},
                  "task":{"objective":"分析","context":"private-context","constraints":[],"expected_output":"结论"},
                  "inputs":[],"outputs":["answer"],"exclusive_resources":[],"cancelled":false,"replaces_task_id":"step2"}]}}
                """;
        var output = POLICY.project(json(raw));
        assertEquals(ModelDecisionProjectionPolicy.PLAN_TASK_SELF_REPLACEMENT, output);
        assertFalse(output.canonicalJson().contains("private"));
        assertFalse(output.canonicalJson().contains("step2"));
        assertDoesNotThrow(() -> new SessionRuntimePayloads().validate(output, PARSER));
    }

    @Test void argumentFailureCodesSurviveProjectionAndRegistryWithoutRawParameters() {
        for (var reason : List.of(AgentDecisionDecoder.Reason.TOOL_ARGUMENTS_INVALID_JSON,
                AgentDecisionDecoder.Reason.TOOL_ARGUMENTS_NOT_OBJECT, AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_FIELDS_INVALID, AgentDecisionDecoder.Reason.CONTROL_COMMENTARY_INVALID, AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_INVALID, AgentDecisionDecoder.Reason.PLAN_TASK_SELF_REPLACEMENT)) {
            String wire = "{\"reason\":\"" + reason.name() + "\",\"type\":\"decision_rejected\"}";
            for (var input : List.of(json(wire))) {
                var projected = POLICY.project(input);
                assertEquals(wire, projected.canonicalJson());
                assertDoesNotThrow(() -> POLICY.validateProjection(projected));
                assertDoesNotThrow(() -> new SessionRuntimePayloads().validate(projected, PARSER));
                assertEquals(new AgentDecisionDecoder.Failure(reason), DECODER.decodeSnapshot(projected));
                assertEquals(INVALID, policy(projected.canonicalJson().length() - 1, PARSER).project(input));
            }
            String unsafe = wire.replace("}", ",\"arguments\":{\"private\":\"secret\"}}");
            assertEquals(INVALID, POLICY.project(json(unsafe)));
            assertThrows(IllegalArgumentException.class, () -> POLICY.validateProjection(
                    new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, unsafe)));
        }
    }

    @Test void unavailableToolRefusalSurvivesBoundedProjectionRegistryAndSnapshotDecode() {
        String wire = " { \"type\" : \"decision_rejected\", \"reason\" : \"TOOL_NOT_AVAILABLE\" } ";
        for (var input : List.of(json(wire))) {
            var projection = POLICY.project(input);
            assertEquals(ModelDecisionProjectionPolicy.TOOL_NOT_AVAILABLE, projection);
            assertDoesNotThrow(() -> POLICY.validateProjection(projection));
            assertDoesNotThrow(() -> new SessionRuntimePayloads().validate(projection, PARSER));
            assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.TOOL_NOT_AVAILABLE),
                    DECODER.decodeSnapshot(projection));
            assertEquals(INVALID, policy(projection.canonicalJson().length() - 1, PARSER).project(input));
        }
        for (String unsafe : List.of(
                "{\"arguments\":{},\"reason\":\"TOOL_NOT_AVAILABLE\",\"type\":\"decision_rejected\"}",
                "{\"tool_name\":\"private\",\"reason\":\"TOOL_NOT_AVAILABLE\",\"type\":\"decision_rejected\"}")) {
            assertEquals(INVALID, POLICY.project(json(unsafe)));
            var projection = new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, unsafe);
            assertThrows(IllegalArgumentException.class, () -> POLICY.validateProjection(projection));
            assertThrows(IllegalArgumentException.class, () -> new SessionRuntimePayloads().validate(projection, PARSER));
        }
    }

    /** 真实安全投影和Registry不能把明确的执行前拒绝再次抹成泛非法决定。 */
    @Test void knownControlRefusalSurvivesProjectionRegistryAndSnapshotDecode() {
        String wire = " { \"type\" : \"decision_rejected\", \"reason\" : \"CONTROL_ACTION_REQUIRES_SINGLE_CALL\" } ";
        for (var input : List.of(json(wire))) {
            var projection = POLICY.project(input);
            assertEquals(ModelDecisionProjectionPolicy.SINGLE_CONTROL_REQUIRED, projection);
            assertDoesNotThrow(() -> POLICY.validateProjection(projection));
            assertDoesNotThrow(() -> new SessionRuntimePayloads().validate(projection, PARSER));
            assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.CONTROL_ACTION_REQUIRES_SINGLE_CALL),
                    DECODER.decodeSnapshot(projection));
            assertEquals(INVALID, policy(projection.canonicalJson().length() - 1, PARSER).project(input));
        }
        for (String bad : List.of(
                "{\"reason\":\"unknown\",\"type\":\"decision_rejected\"}",
                "{\"private\":\"secret\",\"reason\":\"CONTROL_ACTION_REQUIRES_SINGLE_CALL\",\"type\":\"decision_rejected\"}")) {
            assertEquals(INVALID, POLICY.project(json(bad)));
            var unsafe = new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, bad);
            assertThrows(IllegalArgumentException.class, () -> POLICY.validateProjection(unsafe));
            assertThrows(IllegalArgumentException.class, () -> new SessionRuntimePayloads().validate(unsafe, PARSER));
        }
    }

    @Test void commentarySurvivesThePublicDecisionBudgetAndCannotBecomeAStandaloneReply() {
        var wire = "{\"type\":\"tool_call\",\"tool_handle\":\"read\",\"arguments\":{},\"commentary\":\"我先核对资料。\"}";
        var result = POLICY.project(json(wire));
        assertEquals(java.util.Optional.of("我先核对资料。"), DECODER.decodeSnapshot(result).commentary());
        assertDoesNotThrow(() -> POLICY.validateProjection(result));
        assertEquals(INVALID, POLICY.project(json("{\"type\":\"respond\",\"output\":\"完成\",\"commentary\":\"说明\"}")));
        assertEquals(INVALID, policy(result.canonicalJson().codePointCount(0, result.canonicalJson().length()) - 1, PARSER).project(json(wire)));
    }
    private static final CodecLimits PARSER = new CodecLimits(4096, 128, 16);
    private static final TypedPayload INVALID = new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, "{}");
    private static final AgentDecisionDecoder DECODER = new AgentDecisionDecoder(PARSER);
    private static final ModelDecisionProjectionPolicy POLICY = policy(1024, PARSER);

    @Test void nativeTextIsNotDecodedAsActionWhileStructuredJsonUsesDecisionCodec() {
        var wire = " { \"type\" : \"respond\", \"output\" : \"最终答案😀\" } ";
        var expected = DECODER.encode(new AgentAction.Respond("最终答案😀", List.of()));
        assertEquals(text(wire), POLICY.project(text(wire)));
        assertEquals(expected, POLICY.project(json(wire)));
        for (var action : List.of(new AgentAction.ToolCall("tool:lookup", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")),
                new AgentAction.ToolCall("tool:a", new com.skillforge.runtime.api.TypedPayload("application/json",
                        "{\"kind\":\"all\",\"page\":1}")),
                new AgentAction.AskUser("请选择"),
                new AgentAction.Dispatch("workflow:one"), new AgentAction.ActivateSkill("skill:one"), new AgentAction.KnowledgeQuery("knowledge:one", "query"))) {
            var encoded = action instanceof AgentAction.AskUser ? DECODER.encode(action)
                    : DECODER.encodeResult(new AgentDecisionDecoder.Decoded(action, java.util.Optional.of("核对当前资料。")));
            assertEquals(encoded, POLICY.project(json(encoded.canonicalJson())));
        }
        var delegate = "{\"commentary\":\"开始协作核对。\",\"type\":\"delegate\",\"operation\":\"start\",\"background\":false,\"target_handle\":\"agent:one\","
                + "\"task\":{\"objective\":\"help\",\"context\":\"context\",\"constraints\":[],\"expected_output\":\"answer\"}}";
        var projected = POLICY.project(json(delegate));
        var decodedDelegate = assertInstanceOf(AgentDecisionDecoder.Decoded.class, DECODER.decodeSnapshot(projected));
        assertInstanceOf(AgentAction.Delegate.class, decodedDelegate.action());
        assertDoesNotThrow(() -> POLICY.validateProjection(expected));
    }

    @Test void parallelToolBatchSurvivesProjectionRegistryAndSnapshotDecode() {
        var modelOutput = json("""
                {"type":"tool_batch","commentary":"并行查询两份资料。","calls":[
                  {"type":"tool_call","tool_handle":"tool:query","arguments":{"case_key":"batch-a"}},
                  {"type":"tool_call","tool_handle":"tool:query","arguments":{"case_key":"batch-b"}}
                ]}
                """);

        var projection = POLICY.project(modelOutput);
        assertDoesNotThrow(() -> POLICY.validateProjection(projection));
        assertDoesNotThrow(() -> new SessionRuntimePayloads().validate(projection, PARSER));

        var batch = assertInstanceOf(AgentDecisionDecoder.DecodedBatch.class,
                DECODER.decodeSnapshot(projection));
        assertEquals(2, batch.calls().size());
        assertEquals(List.of("tool:query", "tool:query"),
                batch.calls().stream().map(AgentAction.ToolCall::toolHandle).toList());
        assertEquals(List.of("{\"case_key\":\"batch-a\"}", "{\"case_key\":\"batch-b\"}"),
                batch.calls().stream().map(call -> call.arguments().canonicalJson()).toList());
    }

    @Test void reasoningAndUnknownArgumentsAreNotRemovedToRescueInvalidOutput() {
        for (var wire : List.of("{\"type\":\"respond\",\"output\":\"ok\",\"reasoning\":\"private chain\"}",
                "{\"type\":\"tool_call\",\"tool_handle\":\"tool:a\",\"arguments\":{},\"unexpected\":true}",
                "private reasoning\n{\"type\":\"ask_user\",\"question\":\"ok\"}")) {
            assertEquals(text(wire), POLICY.project(text(wire)));
            assertEquals(INVALID, POLICY.project(json(wire)));
        }
        var failure = assertInstanceOf(AgentDecisionDecoder.Failure.class, DECODER.decodeSnapshot(INVALID));
        assertEquals(AgentDecisionDecoder.Reason.INVALID_DECISION, failure.reason());
        assertDoesNotThrow(() -> POLICY.validateProjection(INVALID));
    }

    @Test void malformedWrappersDuplicatesAndUnsupportedSchemasYieldOnlyFixedFailure() {
        for (var input : List.of(json("{\"type\":\"ask_user\",\"question\":\"one\",\"question\":\"two\"}"),
                json("{bad"), json("[]"), new TypedPayload(ModelDecisionProjectionPolicy.TEXT_SCHEMA, "{}"),
                new TypedPayload(ModelDecisionProjectionPolicy.TEXT_SCHEMA, "\"unterminated"),
                new TypedPayload("unknown.v1", "{\"secret\":\"private\"}"), json("{} {}"), text("\ud800"))) {
            assertEquals(INVALID, POLICY.project(input));
        }
        assertEquals(INVALID, POLICY.project(null));
    }

    @Test void outputAndParserLimitsFailWithoutTruncatingOrRebuildingAction() {
        var wire = "{\"type\":\"ask_user\",\"question\":\"0123456789\"}";
        var expected = DECODER.encode(new AgentAction.AskUser("0123456789"));
        var exact = policy(expected.canonicalJson().length(), PARSER);
        var shortBudget = policy(expected.canonicalJson().length() - 1, PARSER);
        assertEquals(expected, exact.project(json(wire)));
        assertEquals(INVALID, shortBudget.project(json(wire)));
        var emoji = DECODER.encode(new AgentAction.AskUser("😀"));
        var emojiJson = emoji.canonicalJson();
        int characters = emojiJson.codePointCount(0, emojiJson.length());
        assertTrue(characters < emojiJson.length());
        var emojiBudget = policy(characters, PARSER);
        assertEquals(emoji, emojiBudget.project(json(emojiJson)));
        assertDoesNotThrow(() -> emojiBudget.validateProjection(emoji));
        assertEquals(INVALID, policy(characters - 1, PARSER).project(json(emojiJson)));
        var smallInput = policy(1024, new CodecLimits(10, 128, 16));
        var smallNodes = policy(1024, new CodecLimits(4096, 1, 16));
        var smallDepth = policy(1024, new CodecLimits(4096, 128, 1));
        for (var policy : List.of(smallInput, smallNodes, smallDepth)) assertEquals(INVALID, policy.project(json(wire)));
        assertThrows(IllegalArgumentException.class, () -> policy(1, PARSER));
        var registered = POLICY.policy();
        assertThrows(IllegalArgumentException.class, () -> new ModelDecisionProjectionPolicy(new Policy(registered.reference(), "unsupported.v2",
                registered.maxOutputCharacters(), registered.maxCodeUnits(), registered.maxNodes(), registered.maxDepth())));
    }

    @Test void projectionRegistryRejectsOtherInvalidDocumentsAndUnnormalizedSnapshots() {
        for (var invalid : List.of(new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, "{\"type\":\"invalid\"}"),
                new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, "{\"type\":\"respond\",\"output\":\"ok\"}"),
                json("{}"), new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, "{\"secret\":\"private\"}"))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> POLICY.validateProjection(invalid));
            assertEquals("model_decision_projection_invalid", failure.getMessage());
        }
    }

    @Test void executionAllowsMissingProgressButRejectsInvalidPresentProgress() {
        for (String summary : List.of(",\"commentary\":\"   \"", ",\"commentary\":\"" + "字".repeat(601) + "\"")) {
            assertEquals(INVALID, POLICY.project(json("{\"type\":\"tool_call\",\"tool_handle\":\"read\",\"arguments\":{}" + summary + "}")));
        }
        var missing = DECODER.encode(new AgentAction.ToolCall("read", new TypedPayload("application/json", "{}")));
        assertDoesNotThrow(() -> POLICY.validateProjection(missing));
        assertEquals(missing, POLICY.project(json(missing.canonicalJson())));
        assertNotEquals(INVALID, POLICY.project(json("{\"type\":\"respond\",\"output\":\"你好\"}")));
    }

    private static TypedPayload json(String value) { return new TypedPayload(ModelDecisionProjectionPolicy.JSON_SCHEMA, value); }
    private static ModelDecisionProjectionPolicy policy(int outputCharacters, CodecLimits parser) {
        return new ModelDecisionProjectionPolicy(new Policy(new ResultRef("decision-policy"), ModelDecisionProjectionPolicy.VERSION,
                outputCharacters, parser.maxCodeUnits(), parser.maxNodes(), parser.maxDepth()));
    }
    private static TypedPayload text(String value) {
        // 合法输入复用规范字符串编码；非法UTF-16作为显式wire攻击单独构造。
        return new TypedPayload(ModelDecisionProjectionPolicy.TEXT_SCHEMA,
                value.equals("\ud800") ? "\"\\ud800\"" : RuntimeJson.writeCanonical(value));
    }
}
