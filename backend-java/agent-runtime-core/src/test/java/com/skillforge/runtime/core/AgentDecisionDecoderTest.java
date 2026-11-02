package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.PlanCommand;
import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.api.port.DelegationCommand.Operation;
import com.skillforge.runtime.core.AgentDecisionDecoder.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 独立 golden wire/快照与负向矩阵；纯解码不证明 Session 已允许继续，也不实现任何真实外部端口。 */
class AgentDecisionDecoderTest {
    @Test void nativeBusinessArgumentFailuresStayClosedAndNeverBecomeActions() {
        var decoder = new AgentDecisionDecoder(new CodecLimits(4096, 4096, 32));
        for (var reason : List.of(Reason.TOOL_ARGUMENTS_INVALID_JSON, Reason.TOOL_ARGUMENTS_NOT_OBJECT, Reason.CONTROL_ARGUMENTS_FIELDS_INVALID, Reason.CONTROL_COMMENTARY_INVALID, Reason.CONTROL_ARGUMENTS_INVALID, Reason.PLAN_TASK_SELF_REPLACEMENT)) {
            String wire = "{\"reason\":\"" + reason.name() + "\",\"type\":\"decision_rejected\"}";
            assertEquals(new Failure(reason), decoder.decodeWire(wire));
            assertEquals(new Failure(reason), decoder.decodeSnapshot(new TypedPayload(AgentDecisionDecoder.SCHEMA, wire)));
            assertEquals(new Failure(Reason.INVALID_DECISION), decoder.decodeWire(wire.replace("}", ",\"arguments\":{}}")));
        }
    }

    @Test void toolAvailabilityRefusalStaysClosedThroughWireAndSnapshot() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        String wire = "{\"reason\":\"TOOL_NOT_AVAILABLE\",\"type\":\"decision_rejected\"}";
        var expected = new Failure(Reason.TOOL_NOT_AVAILABLE);
        assertEquals(expected, decoder.decodeWire(wire));
        assertEquals(expected, decoder.decodeSnapshot(new TypedPayload("skillforge.runtime.agent-decision.v1", wire)));
        assertThrows(IllegalArgumentException.class, () -> decoder.encodeResult(expected));
        for (String unsafe : List.of(
                "{\"reason\":\"TOOL_NOT_AVAILABLE\",\"type\":\"decision_rejected\",\"tool_name\":\"private\"}",
                "{\"reason\":\"TOOL_NOT_AVAILABLE\",\"type\":\"decision_rejected\",\"arguments\":{}}")) {
            assertEquals(new Failure(Reason.INVALID_DECISION), decoder.decodeWire(unsafe));
        }
    }

    /** 原生控制动作拒绝仍是失败，不形成可执行动作；原因经快照恢复后保持可纠正。 */
    @Test void preservesSingleControlRequirementThroughWireAndSnapshot() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        String wire = "{\"reason\":\"CONTROL_ACTION_REQUIRES_SINGLE_CALL\",\"type\":\"decision_rejected\"}";
        var expected = new Failure(Reason.CONTROL_ACTION_REQUIRES_SINGLE_CALL);
        assertEquals(expected, decoder.decodeWire(wire));
        assertEquals(expected, decoder.decodeSnapshot(new TypedPayload("skillforge.runtime.agent-decision.v1", wire)));
        assertEquals(new Failure(Reason.INVALID_DECISION), decoder.decodeWire(
                "{\"reason\":\"invented\",\"type\":\"decision_rejected\"}"));
        assertEquals(new Failure(Reason.INVALID_DECISION), decoder.decodeWire(
                "{\"extra\":true,\"reason\":\"CONTROL_ACTION_REQUIRES_SINGLE_CALL\",\"type\":\"decision_rejected\"}"));
    }

    @Test void normalizesStandardToolArgumentDocumentWithoutChangingValuesOrArrayOrder() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        String document = """
                {
                  "method": "POST", "url": "https://api.tavily.com/search",
                  "headers": {"X-Trace": "\\u4e2d\\/\\uD83D\\uDE00", "Content-Type": "application/json"},
                  "body": {"query": "资料\\n第二行", "max_results": 3, "include_raw_content": false,
                    "filters": {"z": 2, "a": 1}, "order": [3, 1, 2], "large": 9223372036854775807}
                }
                """;
        String expected = "{\"body\":{\"filters\":{\"a\":1,\"z\":2},\"include_raw_content\":false,"
                + "\"large\":9223372036854775807,\"max_results\":3,\"order\":[3,1,2],\"query\":\"资料\\n第二行\"},"
                + "\"headers\":{\"Content-Type\":\"application/json\",\"X-Trace\":\"中/😀\"},"
                + "\"method\":\"POST\",\"url\":\"https://api.tavily.com/search\"}";
        var normalized = decoder.decodeToolArgumentsDocument(new TypedPayload("application/json", document));
        assertEquals(new TypedPayload("application/json", expected), normalized);
        var action = new AgentAction.ToolCall("tool:http", normalized);
        assertEquals(action, decoded(decoder.decodeSnapshot(decoder.encode(action))));
        assertEquals(new TypedPayload("application/json", "{}"),
                decoder.decodeToolArgumentsDocument(new TypedPayload("application/json", " { } ")));
        // 标准文档入境不成为损坏冻结参数或快照的失败回退。
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.ToolCall("tool:http",
                new TypedPayload("application/json", document)));
        assertInvalid(decoder.decodeSnapshot(new TypedPayload(AgentDecisionDecoder.SCHEMA,
                "{\"arguments\":" + document + ",\"tool_handle\":\"tool:http\",\"type\":\"tool_call\"}")));
    }

    @Test void rejectsInvalidOrNonObjectToolArgumentDocumentsWithoutRepair() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        for (String document : List.of("{\"key\":1,\"key\":2}", "{\"body\":{\"key\":1,\"\\u006bey\":2}}",
                "{\"key\":1,}", "{\"key\":}", "{}{}", "/* comment */{}", "", "[]", "null", "true", "\"object\"")) {
            assertThrows(IllegalArgumentException.class, () -> decoder.decodeToolArgumentsDocument(
                    new TypedPayload("application/json", document)));
        }
        assertThrows(IllegalArgumentException.class, () -> decoder.decodeToolArgumentsDocument(null));
        assertThrows(IllegalArgumentException.class, () -> decoder.decodeToolArgumentsDocument(new TypedPayload("future.arguments", "{}")));
    }

    @Test void boundsToolArgumentDocumentSizeNodesDepthAndExpandedCanonicalOutput() {
        String document = "{\"body\":{\"order\":[3,1,2]}}";
        var limits = new CodecLimits(document.length(), 8, 4);
        assertEquals(new TypedPayload("application/json", document), new AgentDecisionDecoder(limits)
                .decodeToolArgumentsDocument(new TypedPayload("application/json", document)));
        for (CodecLimits rejected : List.of(new CodecLimits(document.length() - 1, 8, 4),
                new CodecLimits(document.length(), 7, 4), new CodecLimits(document.length(), 8, 3))) {
            assertThrows(IllegalArgumentException.class, () -> new AgentDecisionDecoder(rejected)
                    .decodeToolArgumentsDocument(new TypedPayload("application/json", document)));
        }
        assertThrows(IllegalArgumentException.class, () -> new AgentDecisionDecoder(new CodecLimits(20, 10, 3))
                .decodeToolArgumentsDocument(new TypedPayload("application/json", "{\"value\":1e30}")));
    }

    @Test void commentaryIsFrozenOnceWithoutChangingTheSelectedAction() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        var result = decoder.decodeWire("""
                {"type":"tool_call","tool_handle":"tool:read","arguments":{},"commentary":"我先读取资料。"}
                """);
        assertEquals(Optional.of("我先读取资料。"), result.commentary());
        assertEquals(new AgentAction.ToolCall("tool:read", new TypedPayload("application/json", "{}")),
                ((Decoded) result).action());
        assertEquals(result, decoder.decodeSnapshot(decoder.encodeResult(result)));
        var batch = decoder.decodeWire("""
                {"type":"tool_batch","commentary":"并行核对两个来源。","calls":[
                {"type":"tool_call","tool_handle":"tool:a","arguments":{}},
                {"type":"tool_call","tool_handle":"tool:b","arguments":{}}]}
                """);
        assertEquals(Optional.of("并行核对两个来源。"), batch.commentary());
        assertEquals(batch, decoder.decodeSnapshot(decoder.encodeResult(batch)));
        for (String wire : List.of(
                "{\"type\":\"respond\",\"output\":\"完成\",\"commentary\":\"重复\"}",
                "{\"type\":\"ask_user\",\"question\":\"确认？\",\"commentary\":\"重复\"}",
                "{\"type\":\"dispatch\",\"handle\":\"w\",\"commentary\":null}",
                "{\"type\":\"dispatch\",\"handle\":\"w\",\"commentary\":\" \"}"))
            assertInstanceOf(Failure.class, decoder.decodeWire(wire));
    }
    private static final CodecLimits LIMITS = new CodecLimits(20000, 1000, 20);
    private static final String TASK = "{\"constraints\":[\"only-read\",\"cite\"],\"context\":\"context\",\"expected_output\":\"report\",\"objective\":\"inspect\"}";
    private static final String DELEGATE = "{\"background\":true,\"operation\":\"start\",\"target_handle\":\"agent:review\",\"task\":" + TASK + ",\"type\":\"delegate\"}";

    @Test
    void toolBatchPreservesOrderAndRejectsMixedOrUnboundedActions() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        var one = new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{}"));
        var two = new AgentAction.ToolCall("tool:two", new TypedPayload("application/json", "{}"));
        var batch = new AgentDecisionDecoder.DecodedBatch(List.of(two, one));
        assertEquals(batch, decoder.decodeSnapshot(decoder.encodeResult(batch)));
        assertEquals(batch, decoder.decodeWire(decoder.encodeResult(batch).canonicalJson()));
        assertThrows(IllegalArgumentException.class, () -> new AgentDecisionDecoder.DecodedBatch(List.of(one)));
        var maximum = java.util.stream.IntStream.range(0, com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH)
                .mapToObj(index -> new AgentAction.ToolCall("tool:" + index, new TypedPayload("application/json", "{}"))).toList();
        var full = new AgentDecisionDecoder.DecodedBatch(maximum);
        assertEquals(full, decoder.decodeSnapshot(decoder.encodeResult(full)));
        var excess = new ArrayList<>(maximum); excess.add(one);
        assertThrows(IllegalArgumentException.class, () -> new AgentDecisionDecoder.DecodedBatch(excess));
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.INVALID_DECISION),
                decoder.decodeWire("{\"type\":\"tool_batch\",\"calls\":[{\"type\":\"ask_user\",\"question\":\"x\"},{\"type\":\"tool_call\",\"tool_handle\":\"t\",\"arguments\":{}}]}"));
    }

    @Test
    void knowledgeQueryRequiresBoundedExplicitText() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.INVALID_DECISION),
                decoder.decodeWire("{\"type\":\"knowledge_query\",\"knowledge_handle\":\"knowledge:one\"}"));
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.KnowledgeQuery("knowledge:one", " "));
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.KnowledgeQuery("knowledge:one", "字".repeat(513)));
        var query = new AgentAction.KnowledgeQuery("knowledge:one", "😀".repeat(512));
        assertEquals(query, decoded(decoder.decodeSnapshot(decoder.encode(query))));
    }

    /** 九类动作 golden 都是手写预期，不由同一 encoder 生成输入；逐项比对真实类型和持久字节。 */
    @Test
    void decodesAndEncodesAllNineGoldenVariants() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        var actions = List.<AgentAction>of(new AgentAction.Respond("answer😀", List.of("b", "a")), new AgentAction.Dispatch("workflow:one"),
                new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{\"limit\":2,\"query\":\"weather\"}")),
                new AgentAction.ActivateSkill("skill:one"),
                new AgentAction.ReadSkillResource("skill:one", "references/guide.md"), new AgentAction.KnowledgeQuery("knowledge:one", "question"),
                new AgentAction.AskUser("confirm?"), delegation(),
                new AgentAction.Plan(new com.skillforge.runtime.api.port.PlanCommand(com.skillforge.runtime.api.port.PlanCommand.Operation.GET,"work-one",0,Optional.empty(),"","","",List.of(),List.of())));
        var json = List.of("{\"evidence_item_ids\":[\"b\",\"a\"],\"output\":\"answer😀\",\"type\":\"respond\"}",
                "{\"handle\":\"workflow:one\",\"type\":\"dispatch\"}",
                "{\"arguments\":{\"limit\":2,\"query\":\"weather\"},\"tool_handle\":\"tool:one\",\"type\":\"tool_call\"}",
                "{\"skill_handle\":\"skill:one\",\"type\":\"activate_skill\"}",
                "{\"path\":\"references/guide.md\",\"skill_handle\":\"skill:one\",\"type\":\"read_skill_resource\"}",
                "{\"knowledge_handle\":\"knowledge:one\",\"query\":\"question\",\"type\":\"knowledge_query\"}",
                "{\"question\":\"confirm?\",\"type\":\"ask_user\"}", DELEGATE,
                "{\"operation\":\"get\",\"type\":\"plan\",\"work_id\":\"work-one\"}");
        assertEquals(9, AgentAction.class.getPermittedSubclasses().length);
        for (int i = 0; i < actions.size(); i++) {
            var expected = new TypedPayload(AgentDecisionDecoder.SCHEMA, json.get(i));
            assertEquals(actions.get(i), decoded(decoder.decodeWire(json.get(i))));
            assertEquals(actions.get(i), decoded(decoder.decodeSnapshot(expected)));
            assertEquals(expected, decoder.encode(actions.get(i)));
        }
    }



    /** 八种委派操作都使用同一封闭命令协议，控制操作不能携带其他操作私有字段。 */
    @Test
    void decodesAndEncodesAllDelegationOperations() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        var commands = List.of(
                new DelegationCommand(Operation.TARGETS, "", Optional.empty(), List.of(), "", true),
                DelegationCommand.start("agent:review", new DelegationCommand.Task("inspect", "context", List.of("only-read", "cite"), "report")),
                new DelegationCommand(Operation.LIST, "", Optional.empty(), List.of(), "", true),
                new DelegationCommand(Operation.GET, "", Optional.empty(), List.of("delegation-1"), "", true),
                new DelegationCommand(Operation.SEND, "", Optional.empty(), List.of("delegation-1"), "continue", true),
                new DelegationCommand(Operation.WAIT, "", Optional.empty(), List.of("delegation-1", "delegation-2"), "", true),
                new DelegationCommand(Operation.CANCEL, "", Optional.empty(), List.of("delegation-1"), "", true));
        var json = List.of(
                "{\"operation\":\"targets\",\"type\":\"delegate\"}", DELEGATE,
                "{\"operation\":\"list\",\"type\":\"delegate\"}",
                "{\"delegation_ids\":[\"delegation-1\"],\"operation\":\"get\",\"type\":\"delegate\"}",
                "{\"delegation_ids\":[\"delegation-1\"],\"message\":\"continue\",\"operation\":\"send\",\"type\":\"delegate\"}",
                "{\"delegation_ids\":[\"delegation-1\",\"delegation-2\"],\"operation\":\"wait\",\"type\":\"delegate\"}",
                "{\"delegation_ids\":[\"delegation-1\"],\"operation\":\"cancel\",\"type\":\"delegate\"}");
        for (int i = 0; i < commands.size(); i++) {
            var action = new AgentAction.Delegate(commands.get(i));
            assertEquals(action, decoded(decoder.decodeWire(json.get(i))));
            assertEquals(new TypedPayload(AgentDecisionDecoder.SCHEMA, json.get(i)), decoder.encode(action));
        }
    }

    /** 原始模型布局归一化不改写文本；严格快照不因 wire 可读而放宽。 */
    @Test
    void normalizesRawLayoutEscapesAndMissingOptionalListsWithoutSnapshotFallback() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        var wire = " \r\n{ \"type\" : \"respond\", \"output\" : \"  \\u4e2d\\/\\uD83D\\uDE00  \" }\t";
        var action = new AgentAction.Respond("  中/😀  ", List.of());
        assertEquals(action, decoded(decoder.decodeWire(wire)));
        assertEquals("{\"evidence_item_ids\":[],\"output\":\"  中/😀  \",\"type\":\"respond\"}", decoder.encode(action).canonicalJson());
        assertInvalid(decoder.decodeSnapshot(new TypedPayload(AgentDecisionDecoder.SCHEMA, wire)));
        assertInvalid(decoder.decodeSnapshot(new TypedPayload(AgentDecisionDecoder.SCHEMA, "{\"output\":\"answer\",\"type\":\"respond\"}")));
        var targets = new AgentAction.Delegate(new DelegationCommand(Operation.TARGETS, "", Optional.empty(), List.of(), "", true));
        assertEquals(targets, decoded(decoder.decodeWire("{\"type\":\"delegate\",\"operation\":\"targets\"}")));
        assertEquals("{\"operation\":\"targets\",\"type\":\"delegate\"}", decoder.encode(targets).canonicalJson());
    }

    /** 不接受旧 delegate_to/预算/引用、名称调用、前缀语法、params 注入或混合两个变体字段。 */
    @Test
    void rejectsLegacyUnknownAndInjectedFields() {
        for (String wire : List.of("FINAL: answer", "TOOL: tool:one", "REFERENCES: item", "{\"type\":\"delegate\",\"delegate_to\":\"agent\"}",
                "{\"type\":\"delegate\",\"operation\":\"start\",\"target_handle\":\"agent\",\"task\":{" +
                        "\"objective\":\"x\",\"context\":\"\",\"constraints\":[],\"expected_output\":\"y\"},\"budget\":{}}",
                "{\"type\":\"tool_call\",\"tool_handle\":\"tool\",\"arguments\":{},\"params\":{}}",
                "{\"type\":\"tool_call\",\"name\":\"search\",\"arguments\":{}}",
                "{\"type\":\"tool_call\",\"qualified_name\":\"mcp:server/search\",\"arguments\":{}}",
                "{\"type\":\"dispatch\",\"handle\":\"flow\",\"arguments\":[]}",
                "{\"type\":\"read_skill_resource\",\"skill_handle\":\"skill:one\"}",
                "{\"type\":\"read_skill_resource\",\"skill_handle\":\"skill:one\",\"path\":\"../secret\",\"execute\":true}",
                "{\"type\":\"respond\",\"output\":\"a\",\"question\":\"b\"}", "{\"type\":\"future\"}", "{}", "[]", "null", "\"text\"", "false")) {
            assertInvalid(new AgentDecisionDecoder(LIMITS).decodeWire(wire));
        }
    }

    /** 所有顶层业务文本拒绝空白/null/错误类型；列表元素也不因后置 Runtime 可能拒绝就放行。 */
    @Test
    void rejectsBlankMissingAndWrongTypedBusinessText() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        var types = List.of("respond", "dispatch", "tool_call", "activate_skill", "knowledge_query", "ask_user");
        var fields = List.of("output", "handle", "tool_handle", "skill_handle", "knowledge_handle", "question");
        for (int i = 0; i < types.size(); i++) {
            assertInvalid(decoder.decodeWire("{\"type\":\"" + types.get(i) + "\"}"));
            for (String value : List.of("\"\"", "\" \\t\"", "null", "true", "[]", "{}")) {
                String arguments = types.get(i).equals("tool_call") ? ",\"arguments\":{}" : "";
                assertInvalid(decoder.decodeWire("{\"type\":\"" + types.get(i) + "\",\"" + fields.get(i) + "\":" + value + arguments + "}"));
            }
        }
        for (String arguments : List.of("null", "[]", "\"x\"", "true", "1")) {
            assertInvalid(decoder.decodeWire("{\"type\":\"tool_call\",\"tool_handle\":\"tool:one\",\"arguments\":" + arguments + "}"));
        }
        for (String list : List.of("null", "{}", "\"x\"", "[\"\"]", "[\" \"]", "[null]", "[true]", "[\"same\",\"same\"]")) {
            assertInvalid(decoder.decodeWire("{\"type\":\"respond\",\"output\":\"a\",\"evidence_item_ids\":" + list + "}"));
        }
        assertInvalid(decoder.decodeWire(DELEGATE.replace("\"agent:review\"", "\" \"")));
    }

    /** 原始 JSON 仍严格遵守词法，重复键按解码后的名字判定，拒绝尾随文档与非法 Unicode。 */
    @Test
    void rejectsMalformedDocumentsAndDuplicateEscapedNames() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        var valid = "{\"type\":\"ask_user\",\"question\":\"x\"}";
        for (String wire : List.of("", " \t\r\n", valid + "{}", valid + " x", "\ufeff" + valid, "\u00a0" + valid,
                "/*comment*/" + valid, "{\"type\":\"ask_user\",\"question\":\"x\",}", "{\"type\":\"respond\",\"output\":\"x\",\"ty\\u0070e\":\"ask_user\"}",
                "{\"type\":\"respond\",\"output\":tr ue}", "{\"type\":\"respond\",\"output\":\"\\uD800\"}",
                "{\"type\":\"respond\",\"output\":\"line\nbreak\"}", "{\"type\":\"respond\",\"output\":\"\\x01\"}")) {
            assertInvalid(decoder.decodeWire(wire));
        }
        assertInvalid(decoder.decodeWire(null));
    }

    @Test void delegationRejectsRetiredPlanFieldEvenForValidOperations() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        for (var value : List.of("null", "{}", "{\"expected_revision\":0,\"tasks\":[]}")) {
            assertInvalid(decoder.decodeWire(DELEGATE.substring(0, DELEGATE.length() - 1) + ",\"plan\":" + value + "}"));
            assertInvalid(decoder.decodeWire("{\"type\":\"delegate\",\"operation\":\"targets\",\"plan\":" + value + "}"));
        }
    }

    /** 委派嵌套字段闭包、操作字段 presence、列表去重与文本规则均在解码边界验证。 */
    @Test
    void rejectsMalformedDelegationCommands() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        for (String wire : List.of(
                DELEGATE.replace("\"objective\":\"inspect\"", "\"objective\":\" \""),
                DELEGATE.replace("\"expected_output\":\"report\"", "\"expected_output\":null"),
                DELEGATE.replace("[\"only-read\",\"cite\"]", "[\"same\",\"same\"]"),
                DELEGATE.replace("\"objective\":\"inspect\"", "\"objective\":\"inspect\",\"identity\":\"other\""),
                DELEGATE.replace("\"context\":\"context\"", "\"context\":false"),
                DELEGATE.replace("\"operation\":\"start\"", "\"operation\":\"future\""),
                "{\"type\":\"delegate\",\"operation\":\"start\",\"target_handle\":\"agent:review\"}",
                "{\"type\":\"delegate\",\"operation\":\"submit_plan\"}",
                "{\"type\":\"delegate\",\"operation\":\"submit_plan\",\"plan\":{\"expected_revision\":\"0\",\"tasks\":[]}}",
                "{\"type\":\"delegate\",\"operation\":\"submit_plan\",\"plan\":{\"expected_revision\":0,\"tasks\":[]}}",
                "{\"type\":\"delegate\",\"operation\":\"submit_plan\",\"plan\":{\"expected_revision\":0,\"tasks\":[{\"id\":\"one\",\"title\":\"One\",\"target_handle\":\"agent:review\",\"task\":" + TASK + ",\"inputs\":[],\"outputs\":[\"same\",\"same\"],\"exclusive_resources\":[]}]}}",
                "{\"type\":\"delegate\",\"operation\":\"targets\",\"delegation_ids\":[]}",
                "{\"type\":\"delegate\",\"operation\":\"list\",\"message\":\"x\"}",
                "{\"type\":\"delegate\",\"operation\":\"get\",\"delegation_ids\":[]}",
                "{\"type\":\"delegate\",\"operation\":\"get\",\"delegation_ids\":[\"one\",\"two\"]}",
                "{\"type\":\"delegate\",\"operation\":\"send\",\"delegation_ids\":[\"one\"],\"message\":\" \"}",
                "{\"type\":\"delegate\",\"operation\":\"wait\",\"delegation_ids\":[]}",
                "{\"type\":\"delegate\",\"operation\":\"wait\",\"delegation_ids\":[\"same\",\"same\"]}",
                "{\"type\":\"delegate\",\"operation\":\"cancel\",\"delegation_ids\":[\" \" ]}")) {
            assertInvalid(decoder.decodeWire(wire));
        }
    }

    /** 长度在创建树前拒绝；深度与节点限制分别触发，编码端也不超出本实例契约。 */
    @Test
    void enforcesIndependentParseAndEncodeResourceLimits() {
        String wire = "{\"type\":\"ask_user\",\"question\":\"x\"}";
        assertInstanceOf(Decoded.class, new AgentDecisionDecoder(new CodecLimits(wire.length(), 5, 2)).decodeWire(wire));
        assertInvalid(new AgentDecisionDecoder(new CodecLimits(wire.length() - 1, 5, 2)).decodeWire(wire));
        assertInvalid(new AgentDecisionDecoder(new CodecLimits(wire.length(), 4, 2)).decodeWire(wire));
        assertInvalid(new AgentDecisionDecoder(new CodecLimits(wire.length(), 5, 1)).decodeWire(wire));
        assertThrows(IllegalArgumentException.class, () -> new AgentDecisionDecoder(new CodecLimits(2, 1000, 20)).encode(delegation()));
    }

    /** schema 错配不会回退 wire，业务失败不持有可泄露的异常或模型正文。 */
    @Test
    void keepsErrorsAndActionsRedacted() {
        var decoder = new AgentDecisionDecoder(LIMITS);
        assertEquals(new Failure(Reason.UNSUPPORTED_SCHEMA), decoder.decodeSnapshot(new TypedPayload("future.v2", "{}")));
        assertEquals(new Failure(Reason.UNSUPPORTED_SCHEMA), decoder.decodeSnapshot(null));
        var result = decoder.decodeWire("{\"type\":\"secret-sentinel\",\"output\":\"private-body\"}");
        assertInvalid(result);
        assertFalse(result.toString().contains("sentinel"));
        assertFalse(result.toString().contains("body"));
        assertEquals("DecodedAgentAction[REDACTED]", decoder.decodeWire(DELEGATE).toString());
        assertEquals("DelegateAction[REDACTED]", delegation().toString());
    }

    /** 散装值也必须防御复制并拒绝空白、重复；不可借助手动构造绕过解码器最小结构规则。 */
    @Test
    void enforcesActionValueConstraintsWithoutSideEffects() {
        var ids = new ArrayList<>(List.of("item"));
        var response = new AgentAction.Respond("answer", ids);
        ids.clear();
        assertEquals(List.of("item"), response.evidenceItemIds());
        assertThrows(UnsupportedOperationException.class, () -> response.evidenceItemIds().clear());
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.Respond(" ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.Respond("a", List.of("x", "x")));
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.ToolCall("\ud800", new TypedPayload("application/json", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.ToolCall("tool:one", new TypedPayload("future.arguments", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "[]")));
        assertThrows(IllegalArgumentException.class, () -> new DelegationCommand.Task("inspect", "context", List.of("same", "same"), "report"));
        assertThrows(IllegalArgumentException.class, () -> new DelegationCommand(Operation.WAIT, "", Optional.empty(), List.of("same", "same"), "", true));
    }

    /** 独立 API 值预期，不从 JSON 或生产 codec 生成。 */
    private static AgentAction.Delegate delegation() {
        return new AgentAction.Delegate(DelegationCommand.start("agent:review",
                new DelegationCommand.Task("inspect", "context", List.of("only-read", "cite"), "report")));
    }

    /** 不以 payload 非空断言成功，必须取得封闭 Decoded 类型和真实动作。 */
    private static AgentAction decoded(Result result) { return assertInstanceOf(Decoded.class, result).action(); }
    /** 所有当前 schema 错误统一稳定失败，不打印输入到生产输出。 */
    private static void assertInvalid(Result result) { assertEquals(new Failure(Reason.INVALID_DECISION), result); }
}
