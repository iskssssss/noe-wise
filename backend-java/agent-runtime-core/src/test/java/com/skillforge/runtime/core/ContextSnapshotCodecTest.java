package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.core.FrozenContextSnapshot.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 使用手写规范字节与篡改矩阵验证结构闭包，不把 DTO 构造当成来源授权或真实 token 测量。 */
class ContextSnapshotCodecTest {
    private static final CodecLimits LIMITS = new CodecLimits(20000, 2000, 20);
    private static final TenantId TENANT = new TenantId("tenant");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session");

    @Test
    void nativeDialogueRoundTripsWithFrozenCallMappingAndReasoning() {
        var source = fixture();
        var arguments = new TypedPayload("application/json", "{\"timezone\":\"Asia/Shanghai\"}");
        var messages = List.of(new ModelMessage("call", ModelRole.ASSISTANT, "",
                        List.of(new ModelToolCall("call-1", "sf_time", arguments)), Optional.empty(), Optional.of("reasoning")),
                new ModelMessage("result", ModelRole.TOOL, "2026-09-23", List.of(), Optional.of("call-1"), Optional.empty()));
        var tools = List.of(new ModelTool("sf_agent_action", "runtime:agent-action", "受控动作",
                new TypedPayload("application/json", "{\"type\":\"object\"}"), false, ModelToolKind.AGENT_ACTION, false));
        var request = new FrozenModelRequest(source.modelRequest().requestedStrategy(), source.modelRequest().target(),
                messages, tools, ToolChoice.AUTO,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native_agent_decision"),
                source.modelRequest().generation(), ReasoningPolicy.DISABLED);
        long chars = messages.stream().mapToLong(message -> message.accountingText().codePointCount(0, message.accountingText().length())).sum()
                + tools.stream().mapToLong(tool -> tool.accountingText().codePointCount(0, tool.accountingText().length())).sum();
        var block = new ContextBlock("native", "observation", "input-log.v5", List.of(new EventId("source")),
                "run", "untrusted-content", new TypedPayload("content.v1", "{}"), 0, 0, 3, chars,
                messages.stream().map(ModelMessage::messageId).toList());
        var snapshot = withBlocks(source, List.of(block), request, new ContextBudget(1000, 10000, 3, chars));
        var encoded = ContextSnapshotCodec.encode(snapshot);
        assertEquals(snapshot, decode(encoded.canonicalJson()));
        assertEquals(List.of(new CallGroup(List.of("call", "result"), List.of("call-1"), List.of(new EventId("source")))), snapshot.callGroups());
        assertRejected(encoded.canonicalJson().replace("\"callIds\":[\"call-1\"]", "\"callIds\":[\"other\"]"));
        assertRejected(encoded.canonicalJson().replace("\"toolCallId\":\"call-1\"", "\"toolCallId\":\"forged\""));
        assertRejected(encoded.canonicalJson().replace("\"kind\":\"AGENT_ACTION\"", "\"kind\":\"UNKNOWN\""));
    }

    @Test
    void rejectsMissingViewVersionAndOldProtocolInsteadOfDefaultingOnRecovery() {
        var snapshot = fixture();
        assertEquals("fixture-tokenizer", snapshot.viewVersion().tokenizerProfileRef());
        assertThrows(IllegalArgumentException.class, () -> ContextSnapshotCodec.decode(
                new TypedPayload("skillforge.runtime.agent.context-snapshot.v3", GOLDEN), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> ContextSnapshotCodec.decode(
                new TypedPayload("skillforge.runtime.agent.context-snapshot.v6", GOLDEN), LIMITS));
        assertRejected(GOLDEN.replace("\"measurementVersion\":\"1\"", "\"measurementVersion\":\"\""));
        assertRejected(GOLDEN.replace("\"sourceEventIds\":[\"source\"]}],\"capabilityHandles\"",
                "\"sourceEventIds\":[\"forged\"]}],\"capabilityHandles\""));
        var changed = decode(GOLDEN.replace("fixture-policy", "changed-policy"));
        assertNotEquals(ContextSnapshotCodec.reference(snapshot), ContextSnapshotCodec.reference(changed));
    }
    // 手写 golden 与生产 encoder 独立，字段名、List 顺序、空值及整数字符串均由契约决定。
    static final String GOLDEN = """
            {"blocks":[{"blockId":"block","charCost":"2","content":{"canonicalJson":"{}","schemaId":"content.v1"},"messageIds":["m"],"priority":"-1","providerId":"provider","scope":"input","sequence":"0","sourceEventIds":["source"],"tokenCost":"3","trustLevel":"untrusted","type":"input"}],"budget":{"maxCharacters":"20","maxTokens":"10","usedCharacters":"2","usedTokens":"3"},"callGroups":[{"callIds":[],"messageIds":["m"],"sourceEventIds":["source"]}],"capabilityHandles":["mount:a"],"capabilitySnapshot":{"generation":"1","inputId":"input","sessionRef":{"sessionId":"session","tenantId":"tenant"},"snapshotId":"cap"},"contextScope":"AGENT_INPUT","createdAt":"2026-09-05T00:00:00.000000001Z","evidenceRefs":[{"bundleId":"bundle","itemIds":["item"],"sessionRef":{"sessionId":"session","tenantId":"tenant"},"sourceEventId":"evidence","version":"1"}],"executionRef":null,"modelRequest":{"budgetCategory":"BUSINESS_MODEL","generation":{"maxOutputTokens":null,"temperature":null},"messages":[{"contents":[{"text":"问😀","type":"text"}],"messageId":"m","protocolState":null,"reasoningContent":null,"role":"USER","toolCallId":null,"toolCalls":[]}],"reasoning":"DISABLED","requestedStrategy":{"strategyId":"strategy","strategyVersion":"1"},"response":{"enforcement":"STRICT","format":{"kind":"TEXT","schema":null},"negotiationReasonCode":"supported"},"target":{"binding":{"bindingId":"binding","revision":"1","tenantId":"tenant"},"capabilities":{"capabilityFactRef":"fact","imagePolicy":{"baseTokens":"0","enabled":false,"maxDimension":"0","maxImageBytes":"0","maxImages":"0","maxRequestBytes":"0","tileSize":"0","tileTokens":"0","toolImages":false,"version":"text-only.v1"},"prefixCapabilities":{"retainToolsWithToolChoiceNone":"UNVERIFIED","systemUpdatesInHistory":"UNVERIFIED","toolUpdates":"UNVERIFIED"},"reasoning":false,"responseFormats":["TEXT"],"streaming":false},"effectiveModelId":"model","resolutionRef":"resolution","timeout":"PT1S"},"toolChoice":"NONE","tools":[]},"operation":{"iteration":"1","loopId":"loop","runIdentity":{"agentId":"agent","causationIdentityRef":"verified","causationInputId":"input","originRef":{"originId":"input","tenantId":"tenant"},"owner":{"sessionRef":{"sessionId":"session","tenantId":"tenant"},"subject":{"endUserId":"9007199254740993","kind":"USER"},"target":{"id":"agent","kind":"AGENT"}},"requester":{"endUserId":"9007199254740993","kind":"END_USER"},"runRef":{"runId":"run","tenantId":"tenant"},"sourceMessageRef":null,"trace":{"causedByEventId":null,"rootInputId":"input","spanId":"span","traceId":"trace"}}},"persona":{"agentId":"agent","tenantId":"tenant","version":"v1"},"relationship":{"agentId":"agent","revision":"9007199254740993","schemaId":"relationship.v1","schemaVersion":"1","tenantId":"tenant","userId":"9007199254740993"},"viewVersion":{"measurementVersion":"1","policyRef":"fixture-policy","policyVersion":"1","rendererVersion":"fixture-renderer","tokenizerProfileRef":"fixture-tokenizer"}}
            """.stripTrailing();

    /** 测试 encode 和 decode 两个方向；补充字符占两个 UTF16 单元但只计一个 code point。 */
    @Test
    void matchesIndependentGoldenBytesAndPreservesLargeIdentities() {
        var snapshot = fixture();
        assertEquals(GOLDEN, ContextSnapshotCodec.encode(snapshot).canonicalJson());
        // 独立 Python SHA256 对手写 golden 的 schema+NUL+UTF8 计算，不调用被测哈希器生成预期。
        assertEquals(ContextSnapshotCodec.SCHEMA + ":fb39cf5990d45f37e294512952bc2e0ef193cb6cfdc6be49d291145b8f74c86b",
                ContextSnapshotCodec.reference(snapshot).value());
        assertEquals(snapshot, decode(GOLDEN));
        assertEquals(GOLDEN, ContextSnapshotCodec.encode(decode(GOLDEN)).canonicalJson());
        assertEquals(2, snapshot.budget().usedCharacters());
        assertEquals(9007199254740993L, decode(GOLDEN).relationship().orElseThrow().userId().value());
        assertNotEquals(ContextSnapshotCodec.reference(snapshot), ContextSnapshotCodec.reference(
                decode(GOLDEN.replace("\"priority\":\"-1\"", "\"priority\":\"0\""))));
    }

    /** Session target 与实际执行 Agent 分别进入冻结字节和摘要，二者不能互相推导。 */
    @Test
    void bindsSessionTargetAndActualAgentIndependently() {
        var snapshot = fixture();
        String changedTargetJson = GOLDEN.replace(
                "\"target\":{\"id\":\"agent\",\"kind\":\"AGENT\"}",
                "\"target\":{\"id\":\"team\",\"kind\":\"TEAM\"}");
        var changedTarget = decode(changedTargetJson);
        String changedAgentJson = changedTargetJson
                .replace("\"runIdentity\":{\"agentId\":\"agent\"",
                        "\"runIdentity\":{\"agentId\":\"other-agent\"")
                .replace("\"persona\":{\"agentId\":\"agent\"",
                        "\"persona\":{\"agentId\":\"other-agent\"")
                .replace("\"relationship\":{\"agentId\":\"agent\"",
                        "\"relationship\":{\"agentId\":\"other-agent\"");
        var changedAgent = decode(changedAgentJson);
        assertEquals(snapshot.operation().runIdentity().agentId(),
                changedTarget.operation().runIdentity().agentId());
        assertNotEquals(snapshot.operation().runIdentity().owner().target(),
                changedTarget.operation().runIdentity().owner().target());
        assertEquals(changedTarget.operation().runIdentity().owner().target(),
                changedAgent.operation().runIdentity().owner().target());
        assertNotEquals(changedTarget.operation().runIdentity().agentId(),
                changedAgent.operation().runIdentity().agentId());
        assertNotEquals(ContextSnapshotCodec.reference(snapshot), ContextSnapshotCodec.reference(changedTarget));
        assertNotEquals(ContextSnapshotCodec.reference(snapshot), ContextSnapshotCodec.reference(changedAgent));
    }

    /** 每一类引用独立篡改；同租户但不同 input/session/agent/user 同样不得通过。 */
    @Test
    void rejectsCrossOwnerAndInputReferences() {
        for (String changed : List.of(
                GOLDEN.replace("\"inputId\":\"input\"", "\"inputId\":\"other\""),
                GOLDEN.replace("\"snapshotId\":\"cap\"", "\"snapshotId\":null"),
                GOLDEN.replace("\"bindingId\":\"binding\",\"revision\":\"1\",\"tenantId\":\"tenant\"",
                        "\"bindingId\":\"binding\",\"revision\":\"1\",\"tenantId\":\"other\""),
                GOLDEN.replace("\"persona\":{\"agentId\":\"agent\"", "\"persona\":{\"agentId\":\"other\""),
                GOLDEN.replace("\"userId\":\"9007199254740993\"", "\"userId\":\"9007199254740994\""),
                GOLDEN.replace("\"itemIds\":[\"item\"],\"sessionRef\":{\"sessionId\":\"session\"",
                        "\"itemIds\":[\"item\"],\"sessionRef\":{\"sessionId\":\"other\""))) {
            assertRejected(changed);
        }
    }

    /** 受限 Workflow 必须显式执行归属且不能夹带 Agent 私有引用。 */
    @Test
    void requiresRestrictedWorkflowScopeClosure() {
        var source = fixture();
        var workflow = new FrozenContextSnapshot(source.operation(), ContextScope.RESTRICTED_WORKFLOW,
                Optional.of(new ExecutionRef(TENANT, "execution")), Optional.empty(), Optional.empty(), Optional.empty(),
                source.blocks(), List.of(), List.of(), source.modelRequest(), source.budget(), source.viewVersion(), source.createdAt());
        String valid = ContextSnapshotCodec.encode(workflow).canonicalJson();
        assertEquals(workflow, decode(valid));
        assertRejected(GOLDEN.replace("AGENT_INPUT", "RESTRICTED_WORKFLOW"));
        assertRejected(valid.replace("\"executionRef\":{\"executionId\":\"execution\",\"tenantId\":\"tenant\"}", "\"executionRef\":null"));
        assertRejected(valid.replace("\"capabilityHandles\":[]", "\"capabilityHandles\":[\"mount:a\"]"));
        assertRejected(valid.replace("\"executionId\":\"execution\",\"tenantId\":\"tenant\"", "\"executionId\":\"execution\",\"tenantId\":\"other\""));
    }

    /** 数字形式、显式预算、精确字符数及来源关联必须全部一致，不能解码后补齐或裁剪。 */
    @Test
    void rejectsInvalidBudgetsAndMessageSources() {
        for (String changed : List.of(
                GOLDEN.replace("\"charCost\":\"2\"", "\"charCost\":\"3\""),
                GOLDEN.replace("\"usedTokens\":\"3\"", "\"usedTokens\":\"4\""),
                GOLDEN.replace("\"maxTokens\":\"10\"", "\"maxTokens\":\"2\""),
                GOLDEN.replace("\"maxTokens\":\"10\"", "\"maxTokens\":\"0\""),
                GOLDEN.replace("\"sequence\":\"0\"", "\"sequence\":\"-1\""),
                GOLDEN.replace("\"messageIds\":[\"m\"]", "\"messageIds\":[\"other\"]"),
                GOLDEN.replace("\"messageIds\":[\"m\"]", "\"messageIds\":[]"),
                GOLDEN.replace("\"sourceEventIds\":[\"source\"]", "\"sourceEventIds\":[\"source\",\"source\"]"),
                GOLDEN.replace("\"sourceEventIds\":[\"source\"]", "\"sourceEventIds\":[]"),
                GOLDEN.replace("\"capabilityHandles\":[\"mount:a\"]", "\"capabilityHandles\":[\"mount:a\",\"mount:a\"]"))) {
            assertRejected(changed);
        }
    }

    /** schema/version、未知字段、非规范时间和资源超限都应失败关闭。 */
    @Test
    void rejectsSchemaDriftAndBoundedInputFailures() {
        for (String changed : List.of(
                GOLDEN.replace("\"createdAt\":", "\"createdAtAlias\":"),
                GOLDEN.replace("\"priority\":\"-1\"", "\"priority\":\"-1\",\"private\":null"),
                GOLDEN.replace("2026-09-05T00:00:00.000000001Z", "2026-09-05T00:00:00.000000001+00:00"),
                GOLDEN.replace("2026-09-05T00:00:00.000000001Z", "secret-invalid"),
                GOLDEN.replace("\"generation\":\"1\"", "\"generation\":\"01\""))) {
            assertRejected(changed);
        }
        assertThrows(IllegalArgumentException.class, () -> ContextSnapshotCodec.decode(new TypedPayload("unknown.v1", GOLDEN), LIMITS));
        for (CodecLimits limits : List.of(new CodecLimits(GOLDEN.length() - 1, 2000, 20),
                new CodecLimits(20000, 1, 20), new CodecLimits(20000, 2000, 2))) {
            assertThrows(IllegalArgumentException.class, () -> ContextSnapshotCodec.decode(new TypedPayload(ContextSnapshotCodec.SCHEMA, GOLDEN), limits));
        }
    }

    /** 跨块重复消息、逆序/重复 block 及 long 成本加和溢出不能产生可用快照。 */
    @Test
    void rejectsCrossBlockConflictsAndArithmeticOverflow() {
        var source = fixture();
        var original = source.blocks().getFirst();
        var other = new ContextBlock("other", "input", "provider", List.of(new EventId("other-source")), "input", "untrusted",
                original.content(), 0, 1, 1, 2, List.of("m"));
        assertThrows(IllegalArgumentException.class, () -> withBlocks(source, List.of(original, other), source.modelRequest(), source.budget()));
        assertThrows(IllegalArgumentException.class, () -> withBlocks(source, List.of(original, original), source.modelRequest(), source.budget()));
        var request = source.modelRequest();
        var twoMessages = new FrozenModelRequest(request.requestedStrategy(), request.target(), List.of(
                new ModelMessage("m", ModelRole.USER, "问😀"), new ModelMessage("n", ModelRole.USER, "x")), request.response(), request.generation(), request.reasoning());
        var first = new ContextBlock("block", "input", "provider", List.of(new EventId("source")), "input", "untrusted", original.content(), 0, 0, Long.MAX_VALUE, 2, List.of("m"));
        var second = new ContextBlock("other", "input", "provider", List.of(new EventId("other-source")), "input", "untrusted", original.content(), 0, 1, 1, 1, List.of("n"));
        assertThrows(ArithmeticException.class, () -> withBlocks(source, List.of(first, second), twoMessages, new ContextBudget(Long.MAX_VALUE, 10, Long.MAX_VALUE, 3)));
        assertThrows(IllegalArgumentException.class, () -> withBlocks(source, List.of(original), twoMessages, source.budget()));
        assertThrows(IllegalArgumentException.class, () -> withBlocks(source, List.of(second, original), twoMessages, source.budget()));
    }

    /** 单块、块列表及外层快照都不能在普通字符串输出中泄露业务载荷。 */
    @Test
    void redactsNestedContentAndDefensivelyCopiesLists() {
        var source = fixture();
        var block = new ContextBlock("block", "input", "provider", List.of(new EventId("source")), "input", "untrusted",
                new TypedPayload("content.v1", "{\"secret-sentinel\":\"private\"}"), -1, 0, 3, 2, List.of("m"));
        var mutable = new ArrayList<>(List.of(block));
        var snapshot = withBlocks(source, mutable, source.modelRequest(), source.budget());
        mutable.clear();
        assertEquals(1, snapshot.blocks().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.blocks().clear());
        for (String output : List.of(block.toString(), snapshot.blocks().toString(), snapshot.toString())) {
            assertFalse(output.contains("secret-sentinel"));
            assertFalse(output.contains("private"));
        }
    }

    /** 构造最小有来源 Agent 上下文，所有身份和预算显式给出。 */
    static FrozenContextSnapshot fixture() {
        var agent = new AgentId("agent");
        var user = new EndUserId(9007199254740993L);
        var input = new InputId("input");
        var owner = SessionOwner.user(SESSION, new SessionTarget(SessionTarget.Kind.AGENT, agent.value()), user);
        var operation = new ModelPreparationIdentity(new RunIdentity(owner, agent, input, new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verified"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value())), "loop", 1);
        var target = new FrozenModelTarget(new ModelBindingRef(TENANT, "binding", 1), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("fact")), Duration.ofSeconds(1), new ResultRef("resolution"));
        var request = new FrozenModelRequest(new RequestedModelStrategy("strategy", "1"), target, List.of(new ModelMessage("m", ModelRole.USER, "问😀")),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "supported"),
                new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
        var block = new ContextBlock("block", "input", "provider", List.of(new EventId("source")), "input", "untrusted", new TypedPayload("content.v1", "{}"), -1, 0, 3, 2, List.of("m"));
        return new FrozenContextSnapshot(operation, ContextScope.AGENT_INPUT, Optional.empty(), Optional.of(new CapabilitySnapshotRef(SESSION, input, "cap", 1)),
                Optional.of(new PersonaRef(TENANT, agent, "v1")), Optional.of(new RelationshipRef(TENANT, user, agent, "relationship.v1", "1", 9007199254740993L)),
                List.of(block), List.of(new EvidenceRef(SESSION, "bundle", List.of("item"), 1, new EventId("evidence"))), List.of("mount:a"),
                request, new ContextBudget(10, 20, 3, 2), new FrozenContextSnapshot.ViewVersion("fixture-policy", "1", "fixture-renderer", "fixture-tokenizer", "1"), Instant.parse("2026-09-05T00:00:00.000000001Z"));
    }

    /** 仅替换本测试关注的消息与块，其余冻结事实不变。 */
    private static FrozenContextSnapshot withBlocks(FrozenContextSnapshot source, List<ContextBlock> blocks, FrozenModelRequest request, ContextBudget budget) {
        return new FrozenContextSnapshot(source.operation(), source.contextScope(), source.executionRef(), source.capabilitySnapshot(), source.persona(),
                source.relationship(), blocks, source.evidenceRefs(), source.capabilityHandles(), request, budget, source.viewVersion(), source.createdAt());
    }

    /** 生产入口必须显式携带注册 schema 与有界解析配置。 */
    private static FrozenContextSnapshot decode(String json) {
        return ContextSnapshotCodec.decode(new TypedPayload(ContextSnapshotCodec.SCHEMA, json), LIMITS);
    }

    /** 错误归一化不能把未知枚举、时间、引用或完整载荷放进异常链。 */
    private static void assertRejected(String json) {
        var error = assertThrows(IllegalArgumentException.class, () -> decode(json));
        assertEquals("invalid context snapshot", error.getMessage());
        assertEquals(null, error.getCause());
    }
}
