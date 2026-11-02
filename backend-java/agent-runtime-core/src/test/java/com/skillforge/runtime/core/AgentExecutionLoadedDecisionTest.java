package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 直接已加载事实的纯路由不需要旧FrozenContextSnapshot、journal或许可。 */
class AgentExecutionLoadedDecisionTest {
    private static final LoopBudget BUDGET = new LoopBudget.Enforced(10, 2, 2, 100, 100, 200, Duration.ofMinutes(1));
    private final AgentExecutionLoop loop = new AgentExecutionLoop(new CodecLimits(10_000, 1_000, 32));

    /** 实际Provider输出使用明确模型JSON契约；快照和工具参数schema均不能冒充模型输出。 */
    @Test
    void decodesProductionDocumentWithoutFallingBackAcrossSchemas() {
        var document = new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA, "{\"type\":\"tool_call\",\"tool_handle\":\"tool:one\",\"arguments\":{}}");
        assertEquals(new AgentDecisionDecoder.Decoded(call("{}")), loop.decodeModel(document));
        var response = new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA, "{\"type\":\"respond\",\"output\":\"原生模型实际回答\"}");
        assertEquals(new AgentDecisionDecoder.Decoded(new AgentAction.Respond("原生模型实际回答", List.of())), loop.decodeModel(response));
        var snapshot = new AgentDecisionDecoder(new CodecLimits(10_000, 1_000, 32)).encode(new AgentAction.Respond("原答案", List.of()));
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.UNSUPPORTED_SCHEMA), loop.decodeModel(snapshot));
        assertInstanceOf(AgentDecisionDecoder.Decoded.class, loop.decode(snapshot));
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.UNSUPPORTED_SCHEMA), loop.decodeModel(new TypedPayload("unsupported", document.canonicalJson())));
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.UNSUPPORTED_SCHEMA), loop.decodeModel(new TypedPayload("application/json", document.canonicalJson())));
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.INVALID_DECISION), loop.decodeModel(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA, "{\"type\":\"tool_call\"}")));
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.INVALID_DECISION),
                loop.decode(new TypedPayload(snapshot.schemaId(), response.canonicalJson())));
    }

    /** 原安全观察具备真实引用时只复用，达到工具次数上限也不创造第二次效果。 */
    @Test
    void reusesResolvedExactObservationAtToolCountLimit() {
        var tool = tool(ToolEffect.READ_ONLY, ToolCallIdentityPolicy.ARGUMENTS);
        var call = call("{}");
        var original = new AgentExecutionLoop.ToolObservation(call, tool, AgentExecutionLoop.ToolResolution.SUCCEEDED,
                Optional.of(new ResultRef("saved-feedback")), List.of(new ModelContent.Text("原真实安全结果")), source());
        var reuse = assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                loop.next(input(call, tool, List.of(original), usage(2))));
        assertSame(original, reuse.original());
        assertEquals(List.of(new ModelContent.Text("原真实安全结果")), reuse.original().contents());
        var missing = new AgentExecutionLoop.ToolObservation(call, tool, AgentExecutionLoop.ToolResolution.SUCCEEDED,
                Optional.empty(), List.of(), source());
        assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.SAFE_FEEDBACK_REQUIRED),
                loop.next(input(call, tool, List.of(missing), usage(0))));
    }

    @Test
    void freshModelReadAfterKnownFailureUsesBudgetAndStillRespectsLaterObservations() {
        var read = tool(ToolEffect.READ_ONLY, ToolCallIdentityPolicy.ARGUMENTS);
        var call = call("{}");
        var failed = new AgentExecutionLoop.ToolObservation(call, read, AgentExecutionLoop.ToolResolution.FAILED,
                Optional.of(new ResultRef("saved-failure")), List.of(new ModelContent.Text("原读取已失败")), source());
        assertInstanceOf(AgentExecutionLoop.LoadedSelected.class, loop.next(input(call, read, List.of(failed), usage(1))));
        assertEquals(new AgentExecutionLoop.LoadedBudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED),
                loop.next(input(call, read, List.of(failed), usage(2))));
        for (var resolution : List.of(AgentExecutionLoop.ToolResolution.UNKNOWN, AgentExecutionLoop.ToolResolution.INPUT_REQUIRED)) {
            var unresolved = new AgentExecutionLoop.ToolObservation(call, read, resolution, Optional.empty(), List.of(), source());
            assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.TOOL_RESULT_UNRESOLVED),
                    loop.next(input(call, read, List.of(failed, unresolved), usage(1))));
        }
        var succeeded = new AgentExecutionLoop.ToolObservation(call, read, AgentExecutionLoop.ToolResolution.SUCCEEDED,
                Optional.of(new ResultRef("saved-success")), List.of(new ModelContent.Text("真实读取结果")), source());
        var reused = assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                loop.next(input(call, read, List.of(failed, succeeded), usage(2))));
        assertSame(succeeded, reused.original());
        for (var policy : ToolCallIdentityPolicy.values()) {
            var write = tool(ToolEffect.NON_IDEMPOTENT_WRITE, policy);
            var failedWrite = new AgentExecutionLoop.ToolObservation(call, write, AgentExecutionLoop.ToolResolution.FAILED,
                    Optional.of(new ResultRef("saved-write-failure")), List.of(new ModelContent.Text("原写入失败")), source());
            assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class, loop.next(input(call, write, List.of(failedWrite), usage(2))));
        }
        var handleRead = tool(ToolEffect.READ_ONLY, ToolCallIdentityPolicy.HANDLE);
        var failedRead = new AgentExecutionLoop.ToolObservation(call, handleRead, AgentExecutionLoop.ToolResolution.FAILED,
                Optional.of(new ResultRef("saved-handle-failure")), List.of(new ModelContent.Text("原读取失败")), source());
        assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class, loop.next(input(call, handleRead, List.of(failedRead), usage(2))));
    }

    /** 未决结果在完全相同或不同参数下都阻止重放，不能改参绕开UNKNOWN。 */
    @Test
    void neverReplaysUnknownOrWaitingTarget() {
        var tool = tool(ToolEffect.READ_ONLY, ToolCallIdentityPolicy.ARGUMENTS);
        for (var resolution : List.of(AgentExecutionLoop.ToolResolution.UNKNOWN, AgentExecutionLoop.ToolResolution.INPUT_REQUIRED)) {
            var original = new AgentExecutionLoop.ToolObservation(call("{}"), tool, resolution, Optional.empty(), List.of(), source());
            for (var call : List.of(call("{}"), call("{\"query\":\"changed\"}")))
                assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.TOOL_RESULT_UNRESOLVED),
                        loop.next(input(call, tool, List.of(original), usage(0))));
        }
    }

    /** 普通写工具保持目标级保护，只读新参数仍走新调用预算，算法集中在Core。 */
    @Test
    void protectsWriteIdentityAndChecksBudgetOnlyForFreshCalls() {
        var write = tool(ToolEffect.NON_IDEMPOTENT_WRITE, ToolCallIdentityPolicy.HANDLE);
        var original = new AgentExecutionLoop.ToolObservation(call("{}"), write, AgentExecutionLoop.ToolResolution.SUCCEEDED,
                Optional.of(new ResultRef("saved-write")), List.of(new ModelContent.Text("写入原结果")), source());
        assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.REPEATED_WRITE_ARGUMENTS),
                loop.next(input(call("{\"value\":\"changed\"}"), write, List.of(original), usage(0))));
        var read = tool(ToolEffect.READ_ONLY, ToolCallIdentityPolicy.ARGUMENTS);
        assertInstanceOf(AgentExecutionLoop.LoadedSelected.class, loop.next(input(call("{}"), read, List.of(), usage(0))));
        assertEquals(new AgentExecutionLoop.LoadedBudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED),
                loop.next(input(call("{}"), read, List.of(), usage(2))));
    }

    /** 实际加载链按冻结策略区分调用，不能从只读或非幂等效果重新推导身份。 */
    @Test
    void loadedExecutionUsesExplicitIdentityPolicyIndependentlyOfEffect() {
        var originalCall = call("{}");
        var changed = call("{\"value\":\"changed\"}");
        for (var effect : List.of(ToolEffect.READ_ONLY, ToolEffect.NON_IDEMPOTENT_WRITE)) {
            for (var policy : ToolCallIdentityPolicy.values()) {
                var tool = tool(effect, policy);
                var original = new AgentExecutionLoop.ToolObservation(originalCall, tool,
                        AgentExecutionLoop.ToolResolution.SUCCEEDED, Optional.of(new ResultRef("saved-result")),
                        List.of(new ModelContent.Text("原真实结果")), source());
                var result = loop.next(input(changed, tool, List.of(original), usage(1)));
                if (policy == ToolCallIdentityPolicy.ARGUMENTS) {
                    assertInstanceOf(AgentExecutionLoop.LoadedSelected.class, result);
                    assertEquals(new AgentExecutionLoop.LoadedBudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED),
                            loop.next(input(changed, tool, List.of(original), usage(2))));
                } else {
                    assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.REPEATED_WRITE_ARGUMENTS), result);
                }
                var reused = assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                        loop.next(input(originalCall, tool, List.of(original), usage(2))));
                assertSame(original, reused.original());
            }
        }
    }

    /** 同句柄的新发布策略不能冒充已执行观察的冻结目标。 */
    @Test
    void loadedExecutionRejectsReplacingHistoricalIdentityPolicy() {
        var oldTool = tool(ToolEffect.NON_IDEMPOTENT_WRITE, ToolCallIdentityPolicy.HANDLE);
        var currentTool = tool(ToolEffect.NON_IDEMPOTENT_WRITE, ToolCallIdentityPolicy.ARGUMENTS);
        var original = new AgentExecutionLoop.ToolObservation(call("{}"), oldTool,
                AgentExecutionLoop.ToolResolution.SUCCEEDED, Optional.of(new ResultRef("saved-original")),
                List.of(new ModelContent.Text("原真实结果")), source());
        for (var call : List.of(call("{}"), call("{\"value\":\"changed\"}")))
            assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.INVALID_HISTORY),
                    loop.next(input(call, currentTool, List.of(original), usage(0))));
    }

    @Test
    void currentObservationIsFreshAcrossModelOperationsButRecoveryReusesItsOwnResult() {
        var browser = tool(ToolEffect.NON_IDEMPOTENT_WRITE, ToolCallIdentityPolicy.HANDLE);
        var action = call("{\"command\":\"snapshot\"}");
        for (var resolution : List.of(AgentExecutionLoop.ToolResolution.SUCCEEDED, AgentExecutionLoop.ToolResolution.FAILED)) {
            var original = new AgentExecutionLoop.ToolObservation(action, browser, resolution,
                    Optional.of(new ResultRef("original-feedback")), List.of(new ModelContent.Text("原观察")), source(), true, 3, 1);
            var same = assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                    loop.next(currentInput(action, browser, List.of(original), usage(2), true, 3)));
            assertSame(original, same.original());
            assertInstanceOf(AgentExecutionLoop.LoadedSelected.class,
                    loop.next(currentInput(action, browser, List.of(original), usage(1), true, 4)));
            assertEquals(new AgentExecutionLoop.LoadedBudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED),
                    loop.next(currentInput(action, browser, List.of(original), usage(2), true, 4)));
            assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                    loop.next(currentInput(action, browser, List.of(original), usage(2), false, 4)));
            var unqualified = new AgentExecutionLoop.ToolObservation(action, browser, resolution,
                    Optional.of(new ResultRef("old-feedback")), List.of(), source(), false, 3, 1);
            assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                    loop.next(currentInput(action, browser, List.of(unqualified), usage(2), true, 4)));
            var current = new AgentExecutionLoop.ToolObservation(action, browser, resolution,
                    Optional.of(new ResultRef("current-feedback")), List.of(), source(), true, 4, 1);
            var restored = assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                    loop.next(currentInput(action, browser, List.of(original, current), usage(2), true, 4)));
            assertSame(current, restored.original());
        }
    }

    @Test
    void unresolvedTargetTakesPrecedenceOverEarlierReusableObservation() {
        var browser = tool(ToolEffect.NON_IDEMPOTENT_WRITE, ToolCallIdentityPolicy.HANDLE);
        var action = call("{\"command\":\"snapshot\"}");
        var done = new AgentExecutionLoop.ToolObservation(action, browser, AgentExecutionLoop.ToolResolution.SUCCEEDED,
                Optional.of(new ResultRef("original-feedback")), List.of(), source(), true, 3, 1);
        for (var resolution : List.of(AgentExecutionLoop.ToolResolution.UNKNOWN, AgentExecutionLoop.ToolResolution.INPUT_REQUIRED)) {
            var unresolved = new AgentExecutionLoop.ToolObservation(action, browser, resolution,
                    Optional.empty(), List.of(), source(), true, 4, 1);
            for (var currentRead : List.of(false, true))
                assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.TOOL_RESULT_UNRESOLVED),
                        loop.next(currentInput(action, browser, List.of(done, unresolved), usage(1), currentRead, 5)));
        }
    }

    @Test
    void currentSlotRecoveryPrecedesOldUnqualifiedResultAndPreservesWriteIdentity() {
        var browser = tool(ToolEffect.NON_IDEMPOTENT_WRITE, ToolCallIdentityPolicy.HANDLE);
        var action = call("{\"command\":\"snapshot\"}");
        var old = new AgentExecutionLoop.ToolObservation(action, browser, AgentExecutionLoop.ToolResolution.SUCCEEDED,
                Optional.of(new ResultRef("old")), List.of(), source());
        var current = new AgentExecutionLoop.ToolObservation(action, browser, AgentExecutionLoop.ToolResolution.SUCCEEDED,
                Optional.of(new ResultRef("current")), List.of(), source(), true, 4, 1);
        var restored = assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                loop.next(currentInput(action, browser, List.of(old, current), usage(2), true, 4)));
        assertSame(current, restored.original());
        var changed = call("{\"command\":\"other\"}");
        assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.INVALID_HISTORY),
                loop.next(currentInput(changed, browser, List.of(current), usage(1), true, 4)));
        assertEquals(new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.REPEATED_WRITE_ARGUMENTS),
                loop.next(currentInput(changed, browser, List.of(current), usage(1), true, 5)));
    }

    @Test
    void sameModelDifferentSlotKeepsParameterizedReadSemantics() {
        var read = tool(ToolEffect.READ_ONLY, ToolCallIdentityPolicy.ARGUMENTS);
        var original = new AgentExecutionLoop.ToolObservation(call("{\"page\":1}"), read,
                AgentExecutionLoop.ToolResolution.SUCCEEDED, Optional.of(new ResultRef("page-one")), List.of(), source(), true, 4, 1);
        var input = new AgentExecutionLoop.LoadedInput(new AgentDecisionDecoder.Decoded(call("{\"page\":2}")),
                BUDGET, usage(1), Duration.ZERO, List.of(read), List.of(original), true, 4, 2);
        assertInstanceOf(AgentExecutionLoop.LoadedSelected.class, loop.next(input));
    }

    /** 已经得到的本地回答不因其实际模型用量刚好越线而丢弃。 */
    @Test
    void preservesLocalCompletionAfterPaidModelUsageCrossesLimit() {
        var usage = new LoopUsage(1, 0, 0, new LoopUsage.TokenTally(100, true), new LoopUsage.TokenTally(0, true));
        var response = new AgentAction.Respond("完整回答", List.of());
        var selected = assertInstanceOf(AgentExecutionLoop.LoadedSelected.class, loop.next(new AgentExecutionLoop.LoadedInput(
                new AgentDecisionDecoder.Decoded(response), BUDGET, usage, Duration.ofSeconds(2), List.of(), List.of())));
        assertEquals(response, selected.action());
    }

    /** 输入只带真实类型化路由值，不建立来源事件或模型上下文假材料。 */
    private static AgentExecutionLoop.LoadedInput input(AgentAction.ToolCall call, AgentExecutionLoop.LoadedTool tool,
            List<AgentExecutionLoop.ToolObservation> history, LoopUsage usage) {
        return new AgentExecutionLoop.LoadedInput(new AgentDecisionDecoder.Decoded(call), BUDGET, usage, Duration.ofSeconds(1), List.of(tool), history);
    }

    private static AgentExecutionLoop.LoadedInput currentInput(AgentAction.ToolCall call, AgentExecutionLoop.LoadedTool tool,
            List<AgentExecutionLoop.ToolObservation> history, LoopUsage usage, boolean currentRead, long modelOrdinal) {
        return new AgentExecutionLoop.LoadedInput(new AgentDecisionDecoder.Decoded(call), BUDGET, usage,
                Duration.ofSeconds(1), List.of(tool), history, currentRead, modelOrdinal, currentRead ? 1 : 0);
    }

    /** 计数是用例明确事实，不代表真实Provider或预算账本验收。 */
    private static LoopUsage usage(int calls) { return new LoopUsage(1, calls, 0, new LoopUsage.TokenTally(0, true), new LoopUsage.TokenTally(0, true)); }

    private static AgentExecutionLoop.ToolSource source() { return new AgentExecutionLoop.ToolSource(
            new com.skillforge.runtime.api.port.ActionRef(new com.skillforge.runtime.api.TenantId("tenant"), "tool-action"), "tool-attempt", new ResultRef("tool-request"), new ResultRef("tool-result")); }

    /** 原规范工具参数用于身份比较。 */
    private static AgentAction.ToolCall call(String json) { return new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", json)); }

    /** 显式提供冻结效果及调用身份策略，加载入口统一使用Binding.parameterized。 */
    private static AgentExecutionLoop.LoadedTool tool(ToolEffect effect, ToolCallIdentityPolicy callIdentityPolicy) {
        var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:one"), new CapabilityRef(CapabilityType.TOOL, "one", "1"), "deployment", 1,
                new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"), effect, effect == ToolEffect.READ_ONLY,
                Duration.ofSeconds(1), true, ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), false, callIdentityPolicy);
        return new AgentExecutionLoop.LoadedTool(new ToolCallRouting.Binding(target,
                new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.TEST_MOCK, "fixture", "tool"), false), "sf_tool");
    }
}
