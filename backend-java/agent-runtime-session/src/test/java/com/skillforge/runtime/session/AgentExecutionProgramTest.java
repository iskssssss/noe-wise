package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.EvidenceSufficiency;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeOperation;
import com.skillforge.runtime.api.KnowledgeDocumentResult;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgePlan;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.KnowledgeStep;
import com.skillforge.runtime.api.KnowledgeTraceStep;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.PlanCommand;
import com.skillforge.runtime.api.port.WorkflowPortal;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.KnowledgePortal;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.core.SkillActivation;
import com.skillforge.runtime.session.ExecutionCoordinator.Adoption;
import com.skillforge.runtime.session.ExecutionCoordinator.CapabilityResult;
import com.skillforge.runtime.session.ExecutionCoordinator.Step;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchCall;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchOutcome;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchPlan;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchResponse;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;
import com.skillforge.runtime.session.spi.SessionAdmissionFacts;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionPrompt;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.session.spi.SessionOutputContract;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 只验证纯程序、实际结果 codec 与必要引用恢复；不代替真实 HTTP/Provider/PostgreSQL 验收。 */
class AgentExecutionProgramTest {
    @Test void largeToolPreviewIsFrozenButFullSafeHistorySurvivesConsecutiveAdoptionAndRecovery() {
        var f=new Fixture();var c=f.configuration;
        var target=new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:context-read"),new CapabilityRef(CapabilityType.TOOL,"context-read","v1"),
                "deployment",1,new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),ToolEffect.READ_ONLY,true,Duration.ofSeconds(1),true,
                ToolPortal.ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),false);
        var binding=new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.PLATFORM,"context-read","read_context_reference");
        var read=new SessionToolDirectory.Entry(target,binding,new SessionToolDirectory.Discovery(binding.serverIdentity(),binding.serverIdentity()+"/"+binding.toolName(),binding.toolName(),Optional.empty(),"读取原文"),true);
        var entries=new ArrayList<>(c.tools());entries.add(read);var tools=new ArrayList<>(c.modelTools());
        tools.add(new ModelTool("sf_context_read",target.handle().value(),"读取原文",target.inputSchema(),false,ModelToolKind.TOOL_CALL,true));
        f.configuration=new AgentExecutionProgram.Configuration(c.profile(),c.target(),tools,entries,c.budget(),c.capabilitySnapshot(),c.capabilityRef(),c.initialMessages(),c.actor(),
                c.knowledge(),c.skills(),c.availableCapabilities(),c.workflows(),c.initialPlanState(),c.planRequired());
        f.contentMeasurements=true;f.materials=f.program.open(f.run,f.context);
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        f.commit(first,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("large-call","sf_template","{\"index\":1}"))),"tool_calls",first.action().ordinal(),Optional.empty()));
        var tool=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));String full="x".repeat(50_000);
        f.commit(tool,f.tool(tool,"{\"content\":[{\"type\":\"text\",\"text\":\""+full+"\"}]}"));
        var second=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var frozen=f.codec.decodeModelRequest(second.action()).request().messages();
        var preview=frozen.stream().filter(message->message.toolCallId().filter("large-call"::equals).isPresent()).findFirst().orElseThrow();
        assertTrue(preview.textContent().length()<8192);assertTrue(preview.textContent().contains("source_event_id"));
        assertTrue(second.events().stream().anyMatch(event->event.type().equals(AgentExecutionContextViews.EVENT)));
        f.commit(second,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("next-call","sf_template","{\"index\":2}"))),"tool_calls",second.action().ordinal(),Optional.empty()));
        assertTrue(f.materials.messages().stream().anyMatch(message->message.textContent().contains(full)));
        var next=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));f.commit(next,f.tool(next,"{\"content\":[{\"type\":\"text\",\"text\":\"small\"}]}"));
        var third=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        f.commit(third,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.Text("完成"))),"stop",third.action().ordinal(),Optional.empty()));
        int measured=f.measurements;var recovered=f.program.open(f.run,f.context);
        assertEquals(f.materials.messages(),recovered.messages());assertEquals(measured,f.measurements);
        assertTrue(recovered.messages().stream().anyMatch(message->message.textContent().contains(full)));
    }

    @Test void observeOnlyRequiresExplicitPerCallOutputAndContinuesPastFiniteCallAndActivityCaps() {
        assertEquals("observe_only_output_limit_required", assertThrows(IllegalArgumentException.class,
                () -> Fixture.observeOnly(OptionalInt.empty())).getMessage());
        var f = Fixture.observeOnly(OptionalInt.of(100));
        f.elapsed = Duration.ofDays(2);
        for (int i = 0; i < 12; i++) {
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            assertEquals(100, f.codec.decodeModelRequest(model.action()).request().generation().maxOutputTokens().orElseThrow());
            f.commit(model, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("observe-" + i,
                    "sf_template", "{\"index\":" + i + "}"))), "tool_calls", model.action().ordinal(), Optional.empty()));
            var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"step " + i + "\"}]}"));
        }
        assertEquals(12, f.materials.usage().businessModelCalls());
        assertEquals(12, f.materials.usage().toolCalls());
        assertEquals(f.materials, f.program.open(f.run, f.context));
        var finalRequest = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(finalRequest, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.Text("完成"))), "stop", finalRequest.action().ordinal(), Optional.empty()));
        assertEquals(RunStatus.COMPLETED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
    }

    @Test void frozenBusinessCallLimitAllowsFinalResultAndRejectsOnlyNextCall() {
        var zero = Fixture.withBusinessModelCalls(0);
        var stopped = assertInstanceOf(Step.Finish.class, zero.program.next(zero.run, zero.materials));
        assertEquals(RunStatus.TERMINATED, stopped.status());
        assertEquals("BUSINESS_MODEL_CALL_BUDGET_EXCEEDED", AgentExecutionCodec.string(zero.codec.parse(
                stopped.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
        assertEquals(0, zero.measurements);
        assertEquals(0, zero.materials.usage().businessModelCalls());

        var answer = Fixture.withBusinessModelCalls(1);
        var request = assertInstanceOf(Step.Dispatch.class, answer.program.next(answer.run, answer.materials));
        answer.commit(request, answer.nativeModel(new AssistantTurn(List.of(new AssistantContent.Text("完成"))), "stop", 1, Optional.empty()));
        assertEquals(RunStatus.COMPLETED, assertInstanceOf(Step.Finish.class,
                answer.program.next(answer.run, answer.materials)).status());

        var toolLoop = Fixture.withBusinessModelCalls(1);
        var model = assertInstanceOf(Step.Dispatch.class, toolLoop.program.next(toolLoop.run, toolLoop.materials));
        toolLoop.commit(model, toolLoop.nativeModel(new AssistantTurn(List.of(
                new AssistantContent.ToolCall("budget-tool", "sf_template", "{}"))), "tool_calls", 1, Optional.empty()));
        var tool = assertInstanceOf(Step.Dispatch.class, toolLoop.program.next(toolLoop.run, toolLoop.materials));
        toolLoop.commit(tool, toolLoop.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"已读取\"}]}"));
        var exhausted = assertInstanceOf(Step.Finish.class, toolLoop.program.next(toolLoop.run, toolLoop.materials));
        assertEquals(RunStatus.TERMINATED, exhausted.status());
        assertEquals("BUSINESS_MODEL_CALL_BUDGET_EXCEEDED", AgentExecutionCodec.string(toolLoop.codec.parse(
                exhausted.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
        assertEquals(1, toolLoop.materials.usage().businessModelCalls());
        assertEquals(toolLoop.materials, toolLoop.program.open(toolLoop.run, toolLoop.context));
    }

    @Test void nativePlainMarkdownJsonAndCodeAreFinalBodiesRatherThanExecutableActions() {
        for(String body:List.of("你好","## 已完成\n\n正文", "{\"type\":\"tool_call\",\"tool_handle\":\"tool:template\",\"arguments\":{}}", "function answer() { return 42; }")) {
            var f=new Fixture();var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
            f.commit(first,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.Text(body))),"stop",1,Optional.empty()));
            var finish=assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials));assertEquals(RunStatus.COMPLETED,finish.status());
            assertEquals(body,AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(),AgentExecutionProgram.COMPLETED_SCHEMA),"text"));
            assertEquals(0,f.materials.usage().toolCalls());assertEquals(f.materials,f.program.open(f.run,f.context));
            assertTrue(f.configuration.modelTools().stream().noneMatch(tool->tool.wireName().equals("final_answer")));
        }
    }

    @Test void nativeTruncationEmptyAndReasoningOnlyKeepSpecificReasonsWithoutToolDispatch() {
        record NativeCase(AssistantTurn turn,String finish,Optional<String> reasoning,String code) { }
        var cases=List.of(
                new NativeCase(new AssistantTurn(List.of(new AssistantContent.ToolCall("raw-length-id","sf_template","{\"unfinished\":"))),"length",Optional.empty(),"model_output_length"),
                new NativeCase(new AssistantTurn(List.of()),"stop",Optional.empty(),"model_response_empty"),
                new NativeCase(new AssistantTurn(List.of()),"stop",Optional.of("private reasoning"),"model_reasoning_only"),
                new NativeCase(new AssistantTurn(List.of()),"tool_calls",Optional.empty(),"model_tool_calls_missing"));
        for(var item:cases) {
            var f=new Fixture();var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
            f.commit(dispatch,f.nativeModel(item.turn(),item.finish(),1,item.reasoning()));
            var finish=assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials));assertEquals(RunStatus.FAILED,finish.status());
            assertEquals(item.code(),AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(),AgentExecutionProgram.COMPLETED_SCHEMA),"reason"));
            assertEquals(0,f.materials.usage().toolCalls());assertEquals(f.materials,f.program.open(f.run,f.context));
        }
    }

    @Test void nativeMalformedArgumentsAndUnknownToolsNeverExecuteOrBecomeGenericJsonDecisions() {
        for(var call:List.of(new AssistantContent.ToolCall("raw-invalid","sf_template","{oops"),
                new AssistantContent.ToolCall("raw-array","sf_template","[]"),new AssistantContent.ToolCall("raw-unknown","unknown_tool","{}"))) {
            var f=new Fixture();var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
            f.commit(dispatch,f.nativeModel(new AssistantTurn(List.of(call)),"tool_calls",1,Optional.empty()));
            assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials)).action().kind());
            ModelPort.validateConversation(f.materials.messages());
            assertTrue(f.materials.messages().getLast().textContent().contains(call.wireName().equals("unknown_tool") ? "tool_not_available" : "tool_arguments_invalid"));
            assertEquals(f.materials,new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context));
            assertEquals(0,f.materials.usage().toolCalls());
        }
    }

    @Test void allOfRejectsBeforeToolDispatchAndNewFactsRecoverWithFrozenRequests() {
        var output = new TypedPayload("json-schema", "{\"allOf\":[{\"type\":\"object\"}],\"required\":[\"answer\"]}");
        var f = new Fixture(output); var c = f.configuration; var old = f.entry.target();
        var input = new TypedPayload("json-schema", "{\"allOf\":[{\"type\":\"object\"},{\"required\":[\"id\"]}],\"properties\":{\"id\":{\"type\":\"string\",\"minLength\":2}}}");
        var target = new ToolPortal.FrozenToolTarget(old.handle(), old.capability(), old.deploymentId(), old.deploymentRevision(),
                input, old.outputSchema(), old.effect(), old.idempotent(), old.timeout(), old.modelInvocable(),
                old.confirmation(), old.sensitiveFields(), old.providerRequirements(), old.outputSchemaDeclared(), old.callIdentityPolicy());
        var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), true);
        var declarations = c.modelTools().stream().map(tool -> tool.kind() == ModelToolKind.TOOL_CALL
                ? new ModelTool(tool.wireName(), tool.toolHandle(), tool.description(), input, tool.strict(), tool.kind(), true) : tool).toList();
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), declarations, List.of(entry), c.budget(), c.capabilitySnapshot(), c.capabilityRef(),
                c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired());
        f.materials = f.program.open(f.run, f.context);
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.saved.add(model.action().request()); f.saved.addAll(model.action().requestParts());
        var restored = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        assertEquals(f.codec.decodeModelRequest(model.action()), f.codec.decodeModelRequest(restored.restorePrepared(f.run, model.action())));
        f.commit(model, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("invalid", "sf_template", "{}"))), "tool_calls", 1, Optional.empty()));
        assertEquals(0, f.materials.usage().toolCalls());
        assertTrue(f.materials.messages().getLast().textContent().contains("tool_arguments_invalid"));
        assertEquals(f.materials, restored.open(f.run, f.context));
        var correction = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, correction.action().kind());
        f.commit(correction, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("correct", "sf_template", "{\"id\":\"ok\"}"))), "tool_calls", 2, Optional.empty()));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.TOOL, tool.action().kind());
        f.commit(tool, f.tool(tool, "{\"content\":[],\"structuredContent\":{\"answer\":\"ok\"}}"));
        assertEquals(f.materials, restored.open(f.run, f.context));
        assertEquals(1, f.materials.usage().toolCalls());
    }

    @Test void mixedBodyAndToolCallContinueWithOriginalCallIdAndArgumentBytes() {
        var f=new Fixture();var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));String raw="{ \"amount\": 1.0 }";
        f.commit(dispatch,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.Text("先读取资料"),new AssistantContent.ToolCall("provider-exact-id","sf_template",raw),new AssistantContent.Text("，稍后汇报。"))),"tool_calls",1,Optional.empty()));
        var tool=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));assertEquals(ExecutionStore.Kind.TOOL,tool.action().kind());
        assertEquals("provider-exact-id",f.codec.decodeTool(tool.action().request().content()).callId());
        f.commit(tool,f.tool(tool,"{\"content\":[],\"structuredContent\":{\"answer\":\"真实结果\"}}"));
        var next=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));var messages=requestHistory(f,next);
        assertEquals("先读取资料，稍后汇报。",messages.get(messages.size()-2).textContent());
        assertEquals(raw,messages.get(messages.size()-2).toolCalls().getFirst().arguments().canonicalJson());
        assertEquals(Optional.of("provider-exact-id"),messages.getLast().toolCallId());assertEquals(f.materials,f.program.open(f.run,f.context));
    }

    @Test void structuredDecisionRetainsItsOwnCommentaryAndCanonicalActionHistory() {
        var f=new Fixture(new ResponseFormat.JsonObject(),ReasoningPolicy.DEFAULT,true);
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var output=new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA,"{\"arguments\":{},\"commentary\":\"结构化任务进度\",\"tool_handle\":\"tool:template\",\"type\":\"tool_call\"}");
        var actual=new ModelResult.Completed(output,"model",new ModelUsage(OptionalLong.of(3),OptionalLong.of(2)),"stop",new ResultRef("structured-result"),Optional.of("结构化私有推理"));
        f.commit(first,new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,new Material(new ResultRef("result-1"),f.codec.encodeOutcome(f.core.encodeOutcome(PortalCallKind.MODEL_GENERATE,actual))),Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.reported(actual.usage().inputTokens(),actual.usage().outputTokens())));
        var tool=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));f.commit(tool,f.tool(tool,"{\"content\":[],\"structuredContent\":{\"answer\":\"真实回执\"}}"));
        var next=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));var messages=requestHistory(f,next);
        assertEquals("结构化任务进度",messages.get(messages.size()-2).textContent());
        assertEquals(Optional.of("execution-call:1"),messages.getLast().toolCallId());
        assertEquals(f.materials,f.program.open(f.run,f.context));
    }

    @Test void structuredTerminationsPreserveReceiptAndUsageWithoutDecodingOutput() {
        for (var finishReason : List.of("length", "content_filter")) {
            for (var json : List.of("{}", "{\"arguments\":{},\"tool_handle\":\"tool:template\",\"type\":\"tool_call\"}")) {
                var f = new Fixture(new ResponseFormat.JsonObject(), ReasoningPolicy.DEFAULT, true);
                f.failCurrentRead = true;
                var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                var output = new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA, json);
                var actual = new ModelResult.Completed(output, "model", new ModelUsage(OptionalLong.of(3), OptionalLong.of(2)),
                        finishReason, new ResultRef("structured-termination"), Optional.empty());
                f.commit(dispatch, new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,
                        new Material(new ResultRef("result-1"), f.codec.encodeOutcome(f.core.encodeOutcome(PortalCallKind.MODEL_GENERATE, actual))),
                        Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.reported(actual.usage().inputTokens(), actual.usage().outputTokens())));
                var expectedReason = finishReason.equals("length") ? "model_output_length" : "model_content_filtered";
                assertTrue(f.materials.decision().isEmpty());
                assertEquals(Optional.of(actual), f.materials.model());
                assertEquals(Optional.of(new ResultRef("result-1")), f.materials.lastResult());
                assertEquals(1, f.materials.usage().businessModelCalls());
                assertEquals(0, f.materials.usage().toolCalls());
                assertEquals(3, f.materials.usage().inputTokens().reportedTokens());
                assertEquals(2, f.materials.usage().outputTokens().reportedTokens());
                assertTrue(f.materials.usage().inputTokens().complete());
                assertTrue(f.materials.usage().outputTokens().complete());
                assertTrue(f.lastAdoption.waiting().isEmpty());
                assertEquals(AgentExecutionProgram.MODEL_RESULT_EVENT, f.lastAdoption.events().getFirst().type());
                var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
                var restored = cold.open(f.run, f.context);
                assertEquals(f.materials, restored);
                var finish = assertInstanceOf(Step.Finish.class, cold.next(f.run, restored));
                assertEquals(RunStatus.FAILED, finish.status());
                assertEquals(expectedReason, AgentExecutionCodec.string(f.codec.parse(
                        finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
            }
        }
    }

    @Test void structuredUnknownToolIsAdoptedAndRejectedWithoutClassifyingOrDispatching() {
        var f = new Fixture(new ResponseFormat.JsonObject(), ReasoningPolicy.DEFAULT, true);
        f.failCurrentRead = true;
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var output = new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA,
                "{\"arguments\":{},\"tool_handle\":\"tool:unknown\",\"type\":\"tool_call\"}");
        var actual = new ModelResult.Completed(output, "model", new ModelUsage(OptionalLong.of(3), OptionalLong.of(2)),
                "stop", new ResultRef("structured-unknown-result"), Optional.empty());
        f.commit(first, new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,
                new Material(new ResultRef("result-1"), f.codec.encodeOutcome(f.core.encodeOutcome(PortalCallKind.MODEL_GENERATE, actual))),
                Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.reported(actual.usage().inputTokens(), actual.usage().outputTokens())));
        assertEquals(List.of(false), f.materials.currentReads());
        assertEquals(0, f.materials.usage().toolCalls());
        var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        var restored = cold.open(f.run, f.context);
        assertEquals(f.materials, restored);
        var finish = assertInstanceOf(Step.Finish.class, cold.next(f.run, restored));
        assertEquals(RunStatus.FAILED, finish.status());
        assertEquals("execution_tool_not_visible", AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
    }

    @Test void nativeControlFieldsAndOptionalProgressUseFrozenPublicSchemas() {
        record RejectedArguments(String wire,String arguments,String reason) { }
        var cases=List.of(
                new RejectedArguments("sf_template","{\"x\":1,\"x\":2}","decision_tool_arguments_invalid_json"),
                new RejectedArguments("sf_template","{} {}","decision_tool_arguments_invalid_json"),
                new RejectedArguments("ask_user","{\"question\":\"问题\",\"type\":\"respond\"}","decision_control_arguments_fields_invalid"),
                new RejectedArguments("ask_user","{}","decision_control_arguments_fields_invalid"),
                new RejectedArguments("plan_get","{\"work_id\":\"work\",\"commentary\":17}","decision_control_commentary_invalid"),
                new RejectedArguments("plan_get","{\"work_id\":\"work\",\"commentary\":\"  \"}","decision_control_commentary_invalid"),
                new RejectedArguments("delegate","{\"target_handle\":\"member:one\",\"instruction\":\"任务\",\"background\":null}","decision_control_arguments_invalid"));
        for(var item:cases) {
            var f=new Fixture(true);var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
            f.commit(dispatch,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("native-case",item.wire(),item.arguments()))),"tool_calls",1,Optional.empty()));
            assertEquals(ExecutionStore.Kind.MODEL,assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials)).action().kind());
            assertTrue(f.materials.messages().getLast().textContent().contains("tool_arguments_invalid"));assertEquals(0,f.materials.usage().toolCalls());
            ModelPort.validateConversation(f.materials.messages());
            assertEquals(f.materials,new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context));
        }
        for(String extra:List.of("",",\"commentary\":null")) {
            var f=new Fixture();var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
            f.commit(dispatch,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("optional-commentary","plan_get","{\"work_id\":\"work\""+extra+"}"))),"tool_calls",1,Optional.empty()));
            assertInstanceOf(AgentAction.Plan.class,assertInstanceOf(AgentDecisionDecoder.Decoded.class,f.materials.decision().orElseThrow()).action());
        }
    }

    @Test void nativeBatchRejectsUnknownAndMixedControlBeforeAnyMemberExecution() {
        for(var calls:List.of(
                List.of(new AssistantContent.ToolCall("batch-a","sf_template","{broken"),new AssistantContent.ToolCall("batch-b","unknown","{}")),
                List.of(new AssistantContent.ToolCall("batch-a","sf_template","{}"),new AssistantContent.ToolCall("batch-b","ask_user","{\"question\":\"问题\"}")))) {
            var f=new Fixture();var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
            boolean unknown=calls.getLast().wireName().equals("unknown");
            var parts=new ArrayList<AssistantContent>(); parts.add(new AssistantContent.Text("supplier-private-body")); parts.addAll(calls);
            var state=new ModelPort.NativeProtocolState(ModelPort.NativeProtocol.RESPONSES,"responses-v1","model",List.of(
                    new TypedPayload("responses.output-item.v1","{\"cipher\":\"supplier-private-state\"}")));
            var original=f.nativeModel(new AssistantTurn(parts),"tool_calls",1,Optional.of("supplier-private-reasoning"),Optional.of(state));
            var prior=f.materials.messages();
            f.commit(dispatch,original);
            assertEquals(0,f.materials.usage().toolCalls());
            assertTrue(f.materials.decision().isEmpty()); assertTrue(f.materials.problem().isEmpty());
            var messages=f.materials.messages(); ModelPort.validateConversation(messages);
            if (unknown) {
                assertEquals(prior,messages.subList(0,prior.size())); assertEquals(prior.size()+1,messages.size());
                assertTrue(original.result().content().canonicalJson().contains("supplier-private-body"));
            } else {
                var assistant=messages.get(messages.size()-calls.size()-2);
                assertEquals(calls.stream().map(AssistantContent.ToolCall::callId).toList(),assistant.toolCalls().stream().map(ModelToolCall::callId).toList());
                for(int i=0;i<calls.size();i++) {
                    var receipt=messages.get(messages.size()-calls.size()-1+i);
                    assertEquals(Optional.of(calls.get(i).callId()),receipt.toolCallId());
                    assertTrue(receipt.textContent().contains("整组调用均未执行"));
                }
            }
            var cold=new AgentExecutionProgram(f,f.core,Fixture.LIMITS);
            assertEquals(f.materials,cold.open(f.run,f.context));
            var retry=assertInstanceOf(Step.Dispatch.class,cold.next(f.run,cold.open(f.run,f.context)));
            assertEquals(ExecutionStore.Kind.MODEL,retry.action().kind());
            if (unknown) {
                var frozen=f.codec.decodeModelRequest(retry.action()).request();
                assertTrue(frozen.messages().containsAll(messages));
                ModelPort.validateConversation(frozen.messages());
                assertTrue(frozen.messages().stream().noneMatch(message -> message.accountingText().contains("supplier-private")
                        || message.accountingText().contains("{broken") || message.accountingText().contains("unknown")));
            }
            f.commit(retry,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("corrected","sf_template","{}"))),"tool_calls",retry.action().ordinal(),Optional.empty()));
            assertEquals(ExecutionStore.Kind.TOOL,assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials)).action().kind());
        }
    }

    @Test void nativeToolSelectionCorrectionsKeepExistingLimitAndModelBudgetAcrossColdRecovery() {
        for(boolean mixedControl:List.of(false,true)) {
            var f=new Fixture();
            for(int attempt=1;attempt<=3;attempt++) {
                var model=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
                var calls=new ArrayList<AssistantContent>();
                calls.add(new AssistantContent.ToolCall("selection:"+attempt,mixedControl?"ask_user":"unknown","{}"));
                if(mixedControl)calls.add(new AssistantContent.ToolCall("business:"+attempt,"sf_template","{}"));
                f.commit(model,f.nativeModel(new AssistantTurn(calls),"tool_calls",model.action().ordinal(),Optional.empty()));
                assertEquals(0,f.materials.usage().toolCalls()); assertTrue(f.materials.pendingHuman().isEmpty());
                assertEquals(attempt,f.materials.usage().businessModelCalls());
                var cold=new AgentExecutionProgram(f,f.core,Fixture.LIMITS);
                assertEquals(f.materials,cold.open(f.run,f.context));
                if(attempt<=2)assertTrue(f.materials.problem().isEmpty());
                else {
                    assertEquals("action_correction_limit",f.materials.problem().orElseThrow().reason());
                    assertEquals(RunStatus.FAILED,assertInstanceOf(Step.Finish.class,cold.next(f.run,f.materials)).status());
                }
            }
        }
        var f=Fixture.withBusinessModelCalls(1);
        var model=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        f.commit(model,f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("budget-unknown","unknown","{}"))),"tool_calls",1,Optional.empty()));
        var cold=new AgentExecutionProgram(f,f.core,Fixture.LIMITS);
        var finish=assertInstanceOf(Step.Finish.class,cold.next(f.run,cold.open(f.run,f.context)));
        assertEquals(RunStatus.TERMINATED,finish.status());
        assertEquals("BUSINESS_MODEL_CALL_BUDGET_EXCEEDED",AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(),AgentExecutionProgram.COMPLETED_SCHEMA),"reason"));
        assertEquals(1,f.materials.usage().businessModelCalls()); assertEquals(0,f.materials.usage().toolCalls());
    }

    @Test void freezesAutoChoiceForNativeConversationWithoutReasoningDependentRules() {
        for (var format : List.<ResponseFormat>of(new ResponseFormat.NativeConversation(), new ResponseFormat.Text())) {
            for (var reasoning : List.of(ReasoningPolicy.DISABLED, ReasoningPolicy.DEFAULT)) {
                for (boolean withTools : List.of(true, false)) {
                    var f = new Fixture(format, reasoning, withTools);
                    var step = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                    var frozen = f.codec.decodeModelRequest(step.action()).request();
                    var expected = withTools?ToolChoice.AUTO:ToolChoice.NONE;
                    assertEquals(expected, frozen.toolChoice());
                    assertEquals(format, frozen.response().format()); assertEquals(reasoning, frozen.reasoning());
                    assertEquals(step.action().request().content(), f.codec.encodeModelRequestMaterials(f.codec.decodeModelRequest(step.action())).root().content());
                }
            }
        }
    }

    /** 生产入口仅将所属冻结目录投影到native枚举；空目录或禁用配置不产生任意目标START。 */
    @Test void modelToolsUsesExactFrozenDelegationDirectoryWithoutOpeningUnknownTargets() {
        var enabled = new Fixture(true); var handles = List.of("member:862143854526469:actual-frozen-target");
        var tools = AgentExecutionProgram.modelTools(enabled.configuration.profile(),List.of(),Map.of(),List.of(),List.of(),List.of(),handles);
        var start = tools.stream().filter(tool -> tool.wireName().equals("delegate")).findFirst().orElseThrow();
        var schema = SessionNativeConversation.object(start.inputSchema().canonicalJson());
        var target = (Map<?,?>) ((Map<?,?>) schema.get("properties")).get("target_handle");
        assertEquals(handles,target.get("enum"));
        var definition = new TypedPayload("json-schema",start.inputSchema().canonicalJson());
        SessionRuntimePayloads.validateAgainstSchema(definition,new TypedPayload("application/json",
                "{\"target_handle\":\"member:862143854526469:actual-frozen-target\",\"instruction\":\"任务\",\"background\":false}"),Fixture.LIMITS);
        assertThrows(IllegalArgumentException.class,() -> SessionRuntimePayloads.validateAgainstSchema(definition,
                new TypedPayload("application/json","{\"target_handle\":\"member:862143854526469\",\"instruction\":\"任务\",\"background\":false}"),Fixture.LIMITS));
        var empty = AgentExecutionProgram.modelTools(enabled.configuration.profile(),List.of(),Map.of(),List.of(),List.of(),List.of(),List.of());
        assertTrue(empty.stream().noneMatch(tool -> tool.wireName().equals("delegate")));
        assertTrue(empty.stream().anyMatch(tool -> tool.wireName().equals("delegate_status")));
        var disabled = new Fixture(false);
        assertTrue(AgentExecutionProgram.modelTools(disabled.configuration.profile(),List.of(),Map.of(),List.of(),List.of(),List.of(),handles)
                .stream().noneMatch(tool -> tool.wireName().equals("delegate")));
    }

    @Test void skillToolsExposeOnlyActivatableHandlesWhilePreservingResourceReads() {
        var f = new Fixture();
        var entry = new SkillActivation.Resource("SKILL.md", "text/markdown", "UTF8", "entry", AgentExecutionCodec.digest("entry"), 5);
        var resource = new SkillActivation.Resource("references/guide.md", "text/markdown", "UTF8", "guide", AgentExecutionCodec.digest("guide"), 5);
        var eligible = new SkillActivation.Descriptor(true, false, SkillActivation.Mode.ON_DEMAND,
                new SkillActivation.Metadata("可调用", "按需加载", List.of()), List.of(entry), Optional.empty(), List.of(), 5);
        var blocked = new SkillActivation.Descriptor(false, false, SkillActivation.Mode.ON_DEMAND,
                new SkillActivation.Metadata("不可调用", "不可激活", List.of()), List.of(entry), Optional.empty(), List.of(), 5);
        var always = new SkillActivation.Descriptor(false, false, SkillActivation.Mode.ALWAYS,
                new SkillActivation.Metadata("预加载", "始终加载", List.of()), List.of(entry, resource), Optional.empty(), List.of(), 5);
        var mounts = List.of(
                new SkillActivation.Mount("skill:eligible", new CapabilityRef(CapabilityType.SKILL, "eligible", "v1"), SkillActivation.Mode.ON_DEMAND, 1000, eligible),
                new SkillActivation.Mount("skill:blocked", new CapabilityRef(CapabilityType.SKILL, "blocked", "v1"), SkillActivation.Mode.ON_DEMAND, 1000, blocked),
                new SkillActivation.Mount("skill:always", new CapabilityRef(CapabilityType.SKILL, "always", "v1"), SkillActivation.Mode.ALWAYS, 1000, always));
        var tools = AgentExecutionProgram.modelTools(f.configuration.profile(), List.of(), Map.of(), List.of(), mounts, List.of(), List.of());
        var use = tools.stream().filter(tool -> tool.wireName().equals("use_skill")).findFirst().orElseThrow();
        var useProperty = (Map<?, ?>) ((Map<?, ?>) SessionNativeConversation.object(use.inputSchema().canonicalJson()).get("properties")).get("skill_handle");
        assertEquals(List.of("skill:eligible"), useProperty.get("enum"));
        assertTrue(use.description().contains("skill:eligible"));
        assertFalse(use.description().contains("skill:blocked"));
        assertFalse(use.description().contains("skill:always"));
        var read = tools.stream().filter(tool -> tool.wireName().equals("read_skill_resource")).findFirst().orElseThrow();
        var readProperty = (Map<?, ?>) ((Map<?, ?>) SessionNativeConversation.object(read.inputSchema().canonicalJson()).get("properties")).get("skill_handle");
        assertEquals(List.of("skill:always", "skill:blocked", "skill:eligible"), readProperty.get("enum"));
        var noEligible = AgentExecutionProgram.modelTools(f.configuration.profile(), List.of(), Map.of(), List.of(), mounts.subList(1, 3), List.of(), List.of());
        assertTrue(noEligible.stream().noneMatch(tool -> tool.wireName().equals("use_skill")));
        assertTrue(noEligible.stream().anyMatch(tool -> tool.wireName().equals("read_skill_resource")));
        var capabilities = mounts.stream().map(SkillActivation.Mount::capability).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var initial = SkillActivation.initial(mounts, capabilities, new SkillActivation.State(List.of()));
        assertEquals(SkillActivation.ResourceStatus.LOADED, SkillActivation.readResource("skill:always", "references/guide.md", mounts, capabilities, initial.state()).status());
        var activated = SkillActivation.activate("skill:eligible", mounts, capabilities, initial.state());
        assertEquals(SkillActivation.Status.ACTIVATED, activated.status());
        assertEquals(SkillActivation.Status.ALREADY_ACTIVE, SkillActivation.activate("skill:eligible", mounts, capabilities, activated.state()).status());
        assertEquals(SkillActivation.Status.NOT_INVOCABLE, SkillActivation.activate("skill:blocked", mounts, capabilities, activated.state()).status());

        var c = f.configuration;
        var modelTools = AgentExecutionProgram.modelTools(c.profile(), c.tools(), Map.of(c.tools().getFirst().target().handle().value(), "template"),
                c.knowledge(), mounts, c.workflows(), List.of());
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), modelTools, c.tools(), c.budget(), c.capabilitySnapshot(),
                c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), mounts, capabilities, c.workflows(), c.initialPlanState(), c.planRequired());
        f.materials = f.program.open(f.run, f.context);
        var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var sentUse = f.codec.decodeModelRequest(dispatch.action()).request().tools().stream().filter(tool -> tool.wireName().equals("use_skill")).findFirst().orElseThrow();
        assertEquals(use.inputSchema(), sentUse.inputSchema());
    }

    @Test void newModelTimeIsLastMeasuredFrozenAndExcludedFromAdoptedHistory() {
        var f=new Fixture();f.output=Optional.of(outputPolicy(false,false,Optional.empty(),Set.of()));
        var history=List.copyOf(f.materials.messages());
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        assertEquals(1, first.events().stream().filter(event -> event.type().equals(AgentExecutionProgram.MODEL_STARTED_EVENT)).count());
        var firstObservation = observation(first.events(), ExecutionObservation.Kind.LLM);
        assertEquals(first.action().action().actionId(), firstObservation.operationId());
        assertEquals(ExecutionObservation.stepId(first.action().action().actionId()), firstObservation.stepId());
        assertEquals(firstObservation.stepId(), firstObservation.parentOperationId());
        assertEquals("1", firstObservation.iteration());
        assertEquals(ExecutionObservation.Status.REGISTERED, firstObservation.status());
        assertEquals(null, firstObservation.elapsedNanos());
        var started=first.events().getFirst();
        assertEquals(AgentExecutionProgram.MODEL_STARTED_EVENT,started.type());
        assertEquals(AgentExecutionProgram.MODEL_STARTED_SCHEMA,started.data().schemaId());
        var startedBody=SessionNativeConversation.object(started.data().canonicalJson());
        assertEquals("DISPATCHED",startedBody.get("status"));
        assertEquals(first.action().action().actionId(),startedBody.get("actionId"));
        assertEquals(Set.of("actionId","status","summary","inputId","rootInputId","traceId","spanId","agentId"),startedBody.keySet());
        var request=f.codec.decodeModelRequest(first.action());
        assertEquals(1,f.timeSamples);assertEquals(request.request().messages(),f.lastMeasuredMessages);
        var sample=AgentExecutionTime.frozen(request.request().messages(),first.action().ordinal());
        assertEquals(SessionFixtures.NOW,sample.sampledAt());assertEquals(ModelRole.USER,request.request().messages().getLast().role());
        assertTrue(request.request().messages().getLast().textContent().startsWith("本次当前可信时间（UTC）："));
        assertTrue(request.request().messages().getLast().textContent().contains("来源为宿主时钟"));
        assertEquals(history,f.materials.messages());
        f.clock=java.time.Clock.fixed(SessionFixtures.NOW.plusSeconds(3600),java.time.ZoneOffset.ofHours(8));
        f.commit(first,f.model(new AgentAction.ToolCall("tool:template",new TypedPayload("application/json","{}")),1));
        assertEquals(AgentExecutionProgram.MODEL_RESULT_EVENT,f.lastAdoption.events().getFirst().type());
        assertTrue(f.materials.messages().stream().noneMatch(value->value.messageId().startsWith("time-context:")));
        int samples=f.timeSamples;assertEquals(f.materials,f.program.open(f.run,f.context));assertEquals(samples,f.timeSamples);
        var tool=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        assertTrue(tool.events().stream().noneMatch(event -> event.type().equals(AgentExecutionProgram.MODEL_STARTED_EVENT)),"工具动作不伪造模型开始事实");
        assertEquals(firstObservation.stepId(), observation(tool.events(), ExecutionObservation.Kind.TOOL).stepId());
        f.commit(tool,f.tool(tool,"{\"content\":[{\"type\":\"text\",\"text\":\"真实工具结果\"}]}"));
        var next=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        assertEquals(1, next.events().stream().filter(event -> event.type().equals(AgentExecutionProgram.MODEL_STARTED_EVENT)).count());
        assertEquals("2", observation(next.events(), ExecutionObservation.Kind.LLM).iteration());
        assertFalse(started.id().equals(next.events().getFirst().id()),"下一模型动作必须有独立开始身份");
        var later=f.codec.decodeModelRequest(next.action());
        assertEquals(2,f.timeSamples);assertEquals(SessionFixtures.NOW.plusSeconds(3600),AgentExecutionTime.frozen(later.request().messages(),next.action().ordinal()).sampledAt());
        assertEquals(later.request().messages(),f.lastMeasuredMessages);
        var laterHistory=requestHistory(f,next);
        assertTrue(laterHistory.getLast().messageId().startsWith("execution-output-policy:"));
        assertEquals(ModelRole.TOOL,laterHistory.get(laterHistory.size()-2).role());
        assertTrue(laterHistory.get(laterHistory.size()-2).textContent().contains("真实工具结果"));
        assertFalse(laterHistory.get(laterHistory.size()-3).toolCalls().isEmpty());
        ModelPort.validateConversation(later.request().messages());
    }

    @Test void alteredOrMisplacedTimeSampleCannotBeAdoptedAsTheOriginalRequest() {
        var f=new Fixture();var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var actual=f.codec.decodeModelRequest(dispatch.action());var messages=actual.request().messages();
        assertThrows(IllegalArgumentException.class,()->AgentExecutionTime.frozen(messages,dispatch.action().ordinal()+1));
        var misplaced=new ArrayList<>(messages);java.util.Collections.swap(misplaced,0,misplaced.size()-1);
        assertThrows(IllegalArgumentException.class,()->AgentExecutionTime.frozen(misplaced,dispatch.action().ordinal()));
        var changed=new ArrayList<>(messages);var last=changed.getLast();changed.set(changed.size()-1,new ModelMessage(last.messageId(),last.role(),last.textContent().replace("来源为宿主时钟","来源为用户时钟")));
        assertThrows(IllegalArgumentException.class,()->AgentExecutionTime.frozen(changed,dispatch.action().ordinal()));
    }

    private static List<ModelMessage> requestHistory(Fixture f,Step.Dispatch dispatch) {
        var messages=f.codec.decodeModelRequest(dispatch.action()).request().messages();
        AgentExecutionTime.frozen(messages,dispatch.action().ordinal());
        return List.copyOf(messages.subList(0,messages.size()-1));
    }
    /** 技术异常与尚未确认的输出准备不能被该类型边界改判成确定失败。 */
    @Test
    void untypedOutputPreparationFailureStillPropagatesWithoutDispatch() {
        var f = new Fixture();
        var failure = new IllegalStateException("preparation_still_processing");
        f.outputPreparationFailure = failure;
        assertEquals(failure, assertThrows(IllegalStateException.class, () -> f.program.next(f.run, f.materials)));
        assertEquals(0, f.materials.usage().businessModelCalls());
        assertEquals(0, f.measurements);
        assertEquals(0, f.timeSamples);
    }

    @Test void frozenToolProjectionOwnsOneSharedNamingRuleAndRetainsDiscoveryIdentity() {
        var f=new Fixture();
        var target=f.entry.target();
        var secondTarget=new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:second"),target.capability(),
                target.deploymentId(),target.deploymentRevision(),target.inputSchema(),target.outputSchema(),target.effect(),
                target.idempotent(),target.timeout(),target.modelInvocable(),target.confirmation(),target.sensitiveFields(),
                target.providerRequirements(),target.outputSchemaDeclared(),target.callIdentityPolicy());
        var secondEntry=new SessionToolDirectory.Entry(secondTarget,f.entry.binding(),f.entry.discovery(),false);
        var identities=Map.of(target.handle().value(),"frozen-template",secondTarget.handle().value(),"frozen-second");
        // 直接消费 Runtime 目录，不依赖平台聊天正文，独立桌面宿主同样收到完整公共规则。
        var tools=SessionNativeConversation.modelTools(f.configuration.profile(),List.of(f.entry,secondEntry),identities,List.of());
        var tool=tools.stream().filter(value->value.kind()==ModelToolKind.TOOL_CALL).findFirst().orElseThrow();
        assertTrue(tool.description().contains("调用使用本次原生函数定义的实际 name"));
        assertTrue(tool.description().contains("logical_name=\"template_render\""));
        assertTrue(tool.description().contains("qualified_name="));
        assertEquals(1,tools.stream().filter(value->value.description().contains("调用使用本次原生函数定义的实际 name")).count());
        assertEquals(1,tools.stream().filter(value->value.description().contains("每批最多")).count());
        assertTrue(tools.stream().filter(value->value.kind()==ModelToolKind.TOOL_CALL)
                .allMatch(value->value.description().startsWith("parallel_safe=")));
        assertTrue(tools.stream().anyMatch(value->value.description().startsWith("parallel_safe=true")));
        assertTrue(tools.stream().filter(value->value.kind()==ModelToolKind.AGENT_ACTION)
                .allMatch(value->value.description().startsWith("parallel_safe=false")));
        var single=SessionNativeConversation.modelTools(f.configuration.profile(),List.of(secondEntry),
                Map.of(secondTarget.handle().value(),"frozen-second"),List.of());
        assertTrue(single.getFirst().batchEligible());
        assertTrue(single.getFirst().description().startsWith("parallel_safe=false"));
        assertTrue(single.getFirst().description().contains("否则按调用顺序串行"));
        assertTrue(single.getFirst().description().contains("写工具、需确认工具和控制函数必须单独调用"));
    }

    /** 生成资格与实际并行标记分离；即使目录标记错误，写和确认工具也不能取得生成资格。 */
    @Test void frozenToolDeclarationsSeparateMultiCallGenerationFromParallelExecution() {
        var f = new Fixture(); var original = f.entry.target();
        for (var effect : ToolEffect.values()) for (var confirmation : ToolPortal.ToolConfirmationPolicy.values())
            for (boolean invocable : List.of(false, true)) for (boolean parallel : List.of(false, true)) {
                if (effect == ToolEffect.READ_ONLY && confirmation == ToolPortal.ToolConfirmationPolicy.ALWAYS) continue;
                var target = new ToolPortal.FrozenToolTarget(original.handle(), original.capability(), original.deploymentId(), original.deploymentRevision(),
                        original.inputSchema(), original.outputSchema(), effect, original.idempotent(), original.timeout(), invocable, confirmation,
                        original.sensitiveFields(), original.providerRequirements(), original.outputSchemaDeclared(), original.callIdentityPolicy());
                var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), parallel);
                var declarations = SessionNativeConversation.modelTools(f.configuration.profile(), List.of(entry),
                        Map.of(target.handle().value(), "frozen-template"), List.of());
                var tool = declarations.stream().filter(value -> value.kind() == ModelToolKind.TOOL_CALL).findFirst().orElseThrow();
                boolean eligible = invocable && effect == ToolEffect.READ_ONLY && confirmation == ToolPortal.ToolConfirmationPolicy.NEVER;
                assertEquals(eligible, tool.batchEligible());
                assertTrue(tool.description().startsWith("parallel_safe=" + (eligible && parallel) + "\n"));
            }
    }

    @Test void dynamicOutputPolicyFollowsCompleteHistoryAndUpdatesWithoutChangingFrozenRecovery() {
        var f=new Fixture();
        f.output=Optional.of(outputPolicy(false,false,Optional.empty(),Set.of()));
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var firstMessages=requestHistory(f,first);
        assertEquals(f.configuration.initialMessages(),firstMessages.subList(0,firstMessages.size()-1));
        assertTrue(firstMessages.getLast().messageId().startsWith("execution-output-policy:"));
        f.commit(first,f.model(new AgentAction.ToolCall("tool:template",new TypedPayload("application/json","{}")),1));
        var tool=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        f.commit(tool,f.tool(tool,"{\"content\":[{\"type\":\"text\",\"text\":\"真实工具结果\"}]}"));
        var old=outputPolicy(false,false,Optional.empty(),Set.of());
        f.output=Optional.of(new AgentExecutionOutputs.Policy(Optional.of(new SessionOutputContract.PreparationHint(
                "更新后执行规则","更新后最终规则","更新后Run状态")),old.respondRejection(),old.executionRejection(),
                old.preparedResult(),old.closingSpec(),false));
        var second=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var frozen=f.codec.decodeModelRequest(second.action());
        var history=requestHistory(f,second);
        assertTrue(history.getLast().textContent().contains("更新后执行规则"));
        assertEquals(1,history.stream().filter(message->message.messageId().startsWith("execution-output-policy:")).count());
        assertEquals(ModelRole.TOOL,history.get(history.size()-2).role());
        assertFalse(history.get(history.size()-3).toolCalls().isEmpty());
        ModelPort.validateConversation(frozen.request().messages());
        f.output=Optional.empty();
        f.commit(second,f.model(new AgentAction.Respond("实际完成",List.of()),second.action().ordinal()));
        int reads=f.outputReads;
        assertEquals(f.materials,new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context));
        assertEquals(reads,f.outputReads,"恢复不能重查或采用当前已撤销规则");
        assertTrue(f.codec.decodeModelRequest(second.action()).request().messages().equals(frozen.request().messages()));
    }

    @Test void emptyOutputObligationDoesNotInjectPolicyAndClearsPreviouslyVisiblePolicy() {
        var f=new Fixture();
        f.output=Optional.of(outputPolicy(false,false,Optional.empty(),Set.of()));
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        assertTrue(requestHistory(f,first).stream().anyMatch(message->message.messageId().startsWith("execution-output-policy:")));
        f.commit(first,f.model(new AgentAction.ToolCall("tool:template",new TypedPayload("application/json","{}")),1));
        var tool=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        f.commit(tool,f.tool(tool,"{\"content\":[{\"type\":\"text\",\"text\":\"真实工具结果\"}]}"));
        f.output=Optional.of(new AgentExecutionOutputs.Policy(Optional.empty(),Optional.empty(),Optional.empty(),
                Optional.empty(),ExecutionBudgetPort.ClosingSpec.TEXT,false));
        var second=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var actual=f.codec.decodeModelRequest(second.action());
        assertTrue(actual.output().isPresent(),"原请求仍冻结宿主预算及输出义务");
        assertTrue(actual.request().messages().stream().noneMatch(message->message.messageId().startsWith("execution-output-policy:")));
        assertTrue(actual.request().messages().stream().anyMatch(message->message.textContent().contains("真实工具结果")));
        assertEquals(actual.request().messages(),f.lastMeasuredMessages);
    }

    @Test void absentOutputDirectiveClearsOnlyPreviousSystemPolicy() {
        var codec=new AgentExecutionCodec(Fixture.LIMITS);
        var user=new ModelMessage("user",ModelRole.USER,"真实输入");
        var old=new ModelMessage("execution-output-policy:1",ModelRole.SYSTEM,"旧规则");
        assertEquals(List.of(user),AgentExecutionOutputs.messages(List.of(old,user),Optional.empty(),2,codec));
        var closing=new AgentExecutionOutputs.Policy(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                ExecutionBudgetPort.ClosingSpec.TEXT,true);
        var messages=AgentExecutionOutputs.messages(List.of(user),Optional.of(new AgentExecutionOutputs.Directive(closing,0)),2,codec);
        assertTrue(messages.getLast().textContent().contains("当前已进入收尾阶段"));
        assertTrue(messages.getLast().textContent().contains("进入收尾不代表工具次数或所有额度已经耗尽"));
    }

    /** 原请求义务在结果前冻结；当前宿主变化不能使原被拒响应在恢复时变成成功。 */
    @Test
    void outputObligationAndActualHintAreFrozenIntoOriginalModelRequestAndRecovery() {
        var f=new Fixture();f.output=Optional.of(outputPolicy(true,false,Optional.empty(),Set.of()));
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var original=f.codec.decodeModelRequest(first.action());
        assertTrue(original.output().orElseThrow().policy().respondRejection().isPresent());
        assertTrue(requestHistory(f,first).getLast().accountingText().contains("真实执行规则"));
        f.output=Optional.of(outputPolicy(false,false,Optional.empty(),Set.of()));
        f.commit(first,f.model(new AgentAction.Respond("未经冻结的完成声明", List.of()),1));
        assertTrue(f.materials.decision().isEmpty());
        var rejectedCandidate=f.materials.messages().stream().filter(message->message.role()==ModelRole.ASSISTANT).findFirst().orElseThrow();
        assertEquals("未经冻结的完成声明",rejectedCandidate.textContent());assertTrue(rejectedCandidate.toolCalls().isEmpty());
        assertEquals(1,f.materials.output().orElseThrow().correctionsUsed());
        int reads=f.outputReads;var recovered=f.program.open(f.run,f.context);
        assertEquals(f.materials,recovered);assertEquals(reads,f.outputReads,"恢复只能读原request.directive");
        var correction=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,recovered));
        assertEquals(1,f.codec.decodeModelRequest(correction.action()).output().orElseThrow().correctionsUsed());
    }

    /** 每次纠正都是真实有预算的MODEL调用，达到固定上限后终结，不产生无限yield或静默完成。 */
    @Test
    void outputCorrectionIsBoundedAndInsufficientClosingBudgetNeverDispatches() {
        var f=new Fixture();f.output=Optional.of(outputPolicy(true,false,Optional.empty(),Set.of()));
        for(int turn=1;turn<=AgentExecutionOutputs.MAX_CORRECTIONS+1;turn++) {
            var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
            f.commit(dispatch,f.model(new AgentAction.Respond("仍未正式交付", List.of()),turn));
        }
        var ended=assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials));
        assertEquals(RunStatus.FAILED,ended.status());
        assertEquals("output_correction_limit",AgentExecutionCodec.string(f.codec.parse(ended.events().getFirst().data(),AgentExecutionProgram.COMPLETED_SCHEMA),"reason"));
        var insufficient=new Fixture();insufficient.output=Optional.of(outputPolicy(true,false,Optional.empty(),Set.of()));insufficient.outputBudgetInsufficient=true;
        var rejected=assertInstanceOf(Step.Finish.class,insufficient.program.next(insufficient.run,insufficient.materials));
        assertEquals(RunStatus.TERMINATED,rejected.status());assertEquals(1,insufficient.outputAdmissions);
        assertEquals(0,insufficient.materials.usage().businessModelCalls());
        var terminal=ExecutionTerminationObservation.read(rejected.events().getFirst().type(),rejected.events().getFirst().data()).orElseThrow();
        assertEquals(ExecutionTerminationObservation.BUDGET_FAILURE_CODE,terminal.reason());
        assertEquals(60_000,terminal.budgetFailure().orElseThrow().shortfalls().getFirst().deficit());
        assertEquals(240_000,terminal.budgetFailure().orElseThrow().timeCalculation().requiredMillis());
    }

    /** 收尾只准准确冻结目标；最终成果引用采用原MODEL请求中的宿主证明，重复finish不再发模型。 */
    @Test
    void closingUsesExactTargetsAndFinishesWithFrozenPreparedResultWithoutAnotherCall() {
        var f=new Fixture();f.output=Optional.of(outputPolicy(false,true,Optional.of("task-result:original"),Set.of("tool:template")));
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));assertTrue(first.budget().closing());
        f.commit(first,f.model(new AgentAction.ToolCall("tool:template",new TypedPayload("application/json","{}")),1));
        var tool=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));assertTrue(tool.budget().closing());
        f.commit(tool,f.tool(tool,"{\"content\":[],\"structuredContent\":{\"answer\":\"真实冻结回执\"}}"));
        var last=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        f.commit(last,f.model(new AgentAction.Respond("已确认成果", List.of()),3));
        f.output=Optional.of(outputPolicy(true,false,Optional.of("task-result:changed"),Set.of()));
        int measured=f.measurements,reads=f.outputReads;
        var finish=assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials));
        assertEquals("task-result:original",AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(),AgentExecutionProgram.COMPLETED_SCHEMA),"taskResultRef"));
        assertEquals(finish,f.program.next(f.run,f.materials));assertEquals(measured,f.measurements);assertEquals(reads,f.outputReads);
    }

    /** 模型展示消息不承载可信义务；纠错计数只来自已采纳的原请求。 */
    @Test void outputProjectionDoesNotDuplicateInstructionsOrExposePrivatePreparationState() {
        var f=new Fixture();f.output=Optional.of(outputPolicy(false,false,Optional.of("task-result:private"),Set.of("tool:template")));
        var dispatch=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var original=f.codec.decodeModelRequest(dispatch.action());
        String visible=requestHistory(f,dispatch).getLast().textContent();
        assertTrue(visible.contains("真实最终答复规则"));assertTrue(visible.contains("原Run成果状态"));
        assertFalse(visible.contains("真实执行规则"));assertFalse(visible.contains("task-result:private"));
        for(String internal:List.of("stableInstructions","finalAnswerInstructions","receiptInputUpperBound","modelCalls","correctionsUsed"))
            assertFalse(visible.contains(internal),internal);
        assertEquals("task-result:private",original.output().orElseThrow().policy().preparedResult().orElseThrow());
        f.commit(dispatch,f.model(new AgentAction.Respond("已确认结果",List.of()),1));
        assertEquals(0,f.materials.output().orElseThrow().correctionsUsed());
        assertEquals(f.materials,f.program.open(f.run,f.context));
    }

    @Test void finalizedAnswerRequestClosesToolsAndMeasuresTheExactProjectedRequest() {
        var f = new Fixture();
        var policy = outputPolicy(false, false, Optional.of("task-result:frozen"), Set.of());
        f.output = Optional.of(new AgentExecutionOutputs.Policy(policy.hint(), policy.respondRejection(),
                Optional.of(new SessionOutputContract.ActionRejection("TASK_OUTPUTS_FROZEN", "清单已冻结，直接答复")),
                policy.preparedResult(), policy.closingSpec(), false));
        var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeModelRequest(dispatch.action()).request();
        assertFalse(f.configuration.modelTools().isEmpty());
        assertTrue(request.tools().isEmpty()); assertEquals(ToolChoice.NONE, request.toolChoice());
        assertEquals(request.messages(), f.lastMeasuredMessages); assertEquals(request.tools(), f.lastMeasuredTools);
        assertTrue(requestHistory(f,dispatch).getLast().textContent().contains("真实最终答复规则"));
        f.commit(dispatch, f.model(new AgentAction.Respond("已核验结果", List.of()), 1));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.COMPLETED, finish.status());
        assertEquals("task-result:frozen", AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "taskResultRef"));
    }

    @Test void ordinaryAndBudgetClosingRequestsKeepTheirActualTools() {
        var prompt = new SessionPrompt(List.of(
                new SessionPrompt.Module("host", SessionPrompt.Type.HOST, "1", "权限与事实边界"),
                new SessionPrompt.Module("code", SessionPrompt.Type.RUNTIME_CODE, "1", "仍可执行的收尾工具指导")));
        for (boolean closing : List.of(false, true)) {
            var f = new Fixture();
            f.usePrompt(prompt);
            f.output = Optional.of(outputPolicy(false, closing, Optional.empty(), Set.of("tool:template")));
            var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var request = f.codec.decodeModelRequest(dispatch.action()).request();
            assertEquals(closing ? f.configuration.modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL && tool.toolHandle().equals("tool:template")).toList() : f.configuration.modelTools(), request.tools()); assertEquals(ToolChoice.AUTO, request.toolChoice());
            assertEquals(request.tools(), f.lastMeasuredTools); assertEquals(closing, dispatch.budget().closing());
            assertEquals(prompt.render(), request.messages().stream().filter(message -> message.messageId().equals("system")).findFirst().orElseThrow().textContent());
        }
    }

    @Test void businessAdmissionProtectsOnlyMeasuredClosingToolsWithoutPreparingMediaAgain() {
        var f = new Fixture();
        f.output = Optional.of(outputPolicy(false, false, Optional.empty(), Set.of("tool:template")));
        f.toolSensitiveMeasurements = true;
        var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeModelRequest(dispatched.action()).request();
        assertFalse(dispatched.budget().closing());
        assertEquals(f.configuration.modelTools(), request.tools());
        assertTrue(f.admittedClosingInput < f.admittedBusinessInput);
        assertEquals(dispatched.budget().resources().inputTokens(), f.admittedBusinessInput);
        assertEquals(1, f.mediaPreparations);
        assertEquals(1, f.timeSamples);
    }

    @Test void budgetObservationIsFrozenAndAdoptedWithoutReadingTheChangedLedger() {
        var f=new Fixture();
        var run=f.run.identity().runRef();
        f.budgetObservation=Optional.of(new ExecutionBudgetPort.Snapshot(
                new ExecutionBudgetPort.Registration(run,run,ExecutionBudgetPort.Role.PRIMARY,new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD,"test")),
                ExecutionBudgetPort.Resources.ZERO,ExecutionBudgetPort.Resources.ZERO,ExecutionBudgetPort.Resources.ZERO,ExecutionBudgetPort.Resources.ZERO,
                new ExecutionBudgetPort.Closing(ExecutionBudgetPort.Resources.ZERO,0),ExecutionBudgetPort.Phase.BUSINESS,0,0,0));
        var dispatched=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var request=f.codec.decodeModelRequest(dispatched.action()).request();
        assertTrue(request.messages().stream().anyMatch(message -> message.messageId().startsWith("execution-budget:")&&message.role()==ModelRole.USER&&message.textContent().contains("仅计量")));
        f.budgetObservation=Optional.empty();
        int reads=f.budgetReads;
        f.commit(dispatched,f.model(new AgentAction.Respond("按已有事实回答。",List.of()),1));
        assertEquals(reads,f.budgetReads);
        assertEquals(f.materials,f.program.open(f.run,f.context));
        assertEquals(reads,f.budgetReads);
        assertEquals(RunStatus.COMPLETED,assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials)).status());
    }

    @Test void budgetClosingWithoutCleanupToolsDisablesCallsOnTheSameAdmission() {
        var f = new Fixture();
        f.output = Optional.of(new AgentExecutionOutputs.Policy(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),ExecutionBudgetPort.ClosingSpec.TEXT,false));
        f.outputSwitchesToClosing = true;
        var dispatched = assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var frozen = f.codec.decodeModelRequest(dispatched.action());
        assertTrue(frozen.output().orElseThrow().policy().closing());
        assertTrue(frozen.request().tools().isEmpty());
        assertEquals(ToolChoice.NONE,frozen.request().toolChoice());
        assertTrue(frozen.request().messages().stream().anyMatch(message -> message.textContent().contains("本轮工具调用已关闭")));
        assertEquals(frozen.request().tools(),f.lastMeasuredTools);
        assertEquals(frozen.request().messages(),f.lastMeasuredMessages);
        f.commit(dispatched,f.model(new AgentAction.Respond("已有结果已确认，尚有未完成查询。",List.of()),1));
        assertEquals(f.materials,f.program.open(f.run,f.context));
        assertEquals(RunStatus.COMPLETED,assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials)).status());
    }

    @Test void outputAdmissionSwitchToClosingKeepsOriginalTimeLastAndAdoptsCompletedModel() {
        var owner = SessionFixtures.IDENTITY.owner().sessionRef();
        var source = new MediaRef(owner, "original-image", "1".repeat(64), "image/png", 1_000, 2400, 1600);
        var variant = new MediaRef(owner, "prepared-image", "2".repeat(64), "image/png", 100, 512, 341);
        var f = new Fixture(source);
        f.preparedVariant = Optional.of(variant);
        f.output = Optional.of(outputPolicy(false, false, Optional.empty(), Set.of("tool:template")));
        f.outputSwitchesToClosing = true;
        var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var frozen = f.codec.decodeModelRequest(dispatch.action());
        var request = frozen.request();
        assertTrue(dispatch.budget().closing());
        assertTrue(frozen.output().orElseThrow().policy().closing());
        assertEquals(request.messages(), f.lastMeasuredMessages);
        assertEquals(request.tools(), f.lastMeasuredTools);
        assertEquals(f.configuration.modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL && tool.toolHandle().equals("tool:template")).toList(), request.tools());
        assertEquals(ToolChoice.AUTO, request.toolChoice());
        assertEquals(1, f.timeSamples);
        assertEquals(1, f.mediaPreparations);
        assertEquals(source, frozen.transformations().getFirst().source());
        assertEquals(variant, frozen.transformations().getFirst().prepared());
        assertEquals(variant, assertInstanceOf(ModelContent.Image.class, request.messages().getFirst().contents().getLast()).media());

        f.clock = java.time.Clock.fixed(SessionFixtures.NOW.plusSeconds(3600), java.time.ZoneOffset.UTC);
        f.commit(dispatch, f.model(new AgentAction.Respond("已完成", List.of()), 1));

        assertEquals(1, f.timeSamples);
        assertEquals(SessionFixtures.NOW,
                AgentExecutionTime.frozen(request.messages(), dispatch.action().ordinal()).sampledAt());
        assertEquals("time-context:" + dispatch.action().ordinal(), request.messages().getLast().messageId());
        assertEquals("execution-output-policy:" + dispatch.action().ordinal(),
                request.messages().get(request.messages().size() - 2).messageId());
        assertEquals(1, f.materials.usage().businessModelCalls());
        assertEquals(source, assertInstanceOf(ModelContent.Image.class, f.materials.messages().getFirst().contents().getLast()).media());
        assertTrue(f.materials.messages().stream().noneMatch(message -> message.messageId().startsWith("time-context:")));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.COMPLETED, finish.status());
        assertEquals(1, f.timeSamples);
        assertEquals(1, f.mediaPreparations);
    }

    @Test void finalAnswerRequestOmitsClosedCapabilityGuidanceAndKeepsSourcesFactsAndPlan() {
        var prompt = new SessionPrompt(List.of(
                new SessionPrompt.Module("host", SessionPrompt.Type.HOST, "1", "权限与授权边界"),
                new SessionPrompt.Module("platform", SessionPrompt.Type.PLATFORM, "1", "平台默认策略"),
                new SessionPrompt.Module("agent", SessionPrompt.Type.AGENT, "1", "当前业务角色与输出语言"),
                new SessionPrompt.Module("evidence", SessionPrompt.Type.RUNTIME, "1", "UNKNOWN不等于成功；来源与事实准确"),
                new SessionPrompt.Module("actions", SessionPrompt.Type.RUNTIME_ACTIONS, "1", "关闭后的交互操作指导"),
                new SessionPrompt.Module("tools", SessionPrompt.Type.RUNTIME_TOOLS, "1", "关闭后的工具调用指导"),
                new SessionPrompt.Module("knowledge", SessionPrompt.Type.RUNTIME_KNOWLEDGE, "1", "知识引用必须使用真实citationId"),
                new SessionPrompt.Module("code", SessionPrompt.Type.RUNTIME_CODE, "1", "关闭后的代码生成指导"),
                new SessionPrompt.Module("plan", SessionPrompt.Type.PLAN_POLICY, "1", "冻结规划政策")));
        var f = new Fixture(new PlanPort.State(true, true, true, Optional.empty()), true);
        f.usePrompt(prompt);
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var ordinary = requestHistory(f, first);
        assertEquals(prompt.render(), ordinary.stream().filter(message -> message.messageId().equals("system")).findFirst().orElseThrow().textContent());
        f.commit(first, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[],\"structuredContent\":{\"answer\":\"原始成功事实\"}}"));
        var before = List.copyOf(f.materials.messages());
        var policy = outputPolicy(false, false, Optional.of("task-result:frozen"), Set.of());
        f.output = Optional.of(new AgentExecutionOutputs.Policy(policy.hint(), policy.respondRejection(),
                Optional.of(new SessionOutputContract.ActionRejection("TASK_OUTPUTS_FROZEN", "清单已冻结")),
                policy.preparedResult(), policy.closingSpec(), false));
        var last = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeModelRequest(last.action()).request();
        var header = request.messages().stream().filter(message -> message.messageId().equals("system")).findFirst().orElseThrow().textContent();
        for (String retained : List.of("权限与授权边界", "平台默认策略", "当前业务角色与输出语言", "UNKNOWN不等于成功", "真实citationId"))
            assertTrue(header.contains(retained), retained);
        assertFalse(header.contains("关闭后的"));
        assertTrue(request.messages().stream().anyMatch(message -> message.textContent().equals("冻结规划政策")));
        assertEquals(before.stream().filter(message -> message.role() != ModelRole.SYSTEM).toList(),
                requestHistory(f, last).stream().filter(message -> message.role() != ModelRole.SYSTEM).toList());
        assertEquals(before.stream().filter(message -> message.messageId().equals("system")).findFirst().orElseThrow().textContent(), prompt.render());
        assertEquals(request.messages(), f.lastMeasuredMessages); assertTrue(request.tools().isEmpty());
        f.commit(last, f.model(new AgentAction.Respond("按原始事实交付", List.of()), 3));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test void finalAnswerAlwaysUsesCompactProjectionRegardlessOfPrefixSupportAndNeverReopensTools() {
        for (var support : ModelPort.CapabilitySupportStatus.values()) for (boolean attemptsTool : List.of(false, true)) {
            var f = new Fixture();
            var prompt = new SessionPrompt(List.of(
                    new SessionPrompt.Module("host", SessionPrompt.Type.HOST, "1", "稳定核心"),
                    new SessionPrompt.Module("operations", SessionPrompt.Type.RUNTIME_TOOLS, "1", "能力操作规则")));
            f.usePrompt(prompt);
            f.usePrefix(new ModelPort.PromptPrefixCapabilities(ModelPort.CapabilitySupportStatus.UNVERIFIED,
                    ModelPort.ToolUpdateMode.NONE, support));
            var initial = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var firstRequest = f.codec.decodeModelRequest(initial.action()).request();
            f.commit(initial, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
            var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"实际完成\"}]}"));
            var before=List.copyOf(f.materials.messages());
            var policy = outputPolicy(false, false, Optional.of("task-result:frozen"), Set.of());
            f.output = Optional.of(new AgentExecutionOutputs.Policy(policy.hint(), policy.respondRejection(),
                    Optional.of(new SessionOutputContract.ActionRejection("TASK_OUTPUTS_FROZEN", "已有成果只需最终答复")),
                    policy.preparedResult(), policy.closingSpec(), false));
            var finalCall = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var request = f.codec.decodeModelRequest(finalCall.action()).request();
            assertEquals(ToolChoice.NONE, request.toolChoice());
            assertFalse(firstRequest.tools().isEmpty());
            assertTrue(firstRequest.messages().getFirst().textContent().contains("能力操作规则"));
            assertTrue(request.tools().isEmpty());
            assertEquals("稳定核心",request.messages().getFirst().textContent());
            assertFalse(request.messages().getFirst().textContent().contains("能力操作规则"));
            assertEquals(before.stream().filter(message->message.role()!=ModelRole.SYSTEM).toList(),
                    requestHistory(f, finalCall).stream().filter(message->message.role()!=ModelRole.SYSTEM).toList());
            assertTrue(request.messages().stream().anyMatch(message->message.role()==ModelRole.TOOL&&message.textContent().contains("实际完成")));
            assertEquals(request.messages(),f.lastMeasuredMessages);assertEquals(List.of(),f.lastMeasuredTools);
            if (attemptsTool) {
                f.commit(finalCall, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("forbidden-final", "sf_template", "{}"))),
                        "tool_calls", 3, Optional.empty()));
                var stopped = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
                assertEquals(RunStatus.FAILED, stopped.status());
                assertEquals("model_tool_choice_none_violated", AgentExecutionCodec.string(
                        f.codec.parse(stopped.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
                assertEquals(1, f.materials.usage().toolCalls());
            } else {
                f.commit(finalCall, f.model(new AgentAction.Respond("最终答复", List.of()), 3));
                assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
            }
        }
    }

    @Test void planningPolicyKeepsPrimarySystemFirstAndUsesOnlyDeclaredHistoryUpdates() {
        for (var support : ModelPort.CapabilitySupportStatus.values()) {
            var f = new Fixture(PlanPort.State.absent(), true);
            f.usePrompt(new SessionPrompt(List.of(
                    new SessionPrompt.Module("host", SessionPrompt.Type.HOST, "1", "稳定核心"),
                    new SessionPrompt.Module("plan", SessionPrompt.Type.PLAN_POLICY, "1", "冻结规划政策"))));
            f.usePrefix(new ModelPort.PromptPrefixCapabilities(support, ModelPort.ToolUpdateMode.NONE, ModelPort.CapabilitySupportStatus.UNVERIFIED));
            var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var messages = requestHistory(f, dispatch);
            assertEquals("system", messages.getFirst().messageId());
            int policy = -1, user = -1;
            for (int index = 0; index < messages.size(); index++) {
                if (messages.get(index).messageId().equals("execution-plan-policy")) policy = index;
                if (messages.get(index).role() == ModelRole.USER) user = index;
            }
            assertTrue(policy > 0);
            assertEquals(support == ModelPort.CapabilitySupportStatus.SUPPORTED, policy > user);
            assertEquals(f.codec.decodeModelRequest(dispatch.action()).request().messages(), f.lastMeasuredMessages);
            f.commit(dispatch, f.model(new AgentAction.AskUser("必要确认"), 1));
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
    }

    @Test void actualPlanningRequestContainsTheFrozenPolicyButPlainRequestDoesNot() {
        for (boolean required : List.of(false, true)) {
            var f = new Fixture(PlanPort.State.absent(), required);
            var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var request = f.codec.decodeModelRequest(dispatch.action()).request();
            assertEquals(required, request.messages().stream().anyMatch(message -> message.role() == ModelRole.SYSTEM
                    && message.textContent().contains("冻结规划政策")));
            assertEquals(request.messages(), f.lastMeasuredMessages);
            f.commit(dispatch, f.model(new AgentAction.AskUser("确认必要范围"), 1));
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
        var existing = new Fixture(new PlanPort.State(true, false, false, Optional.empty()), false);
        var dispatch = assertInstanceOf(Step.Dispatch.class, existing.program.next(existing.run, existing.materials));
        assertTrue(requestHistory(existing, dispatch).stream().anyMatch(message -> message.role() == ModelRole.SYSTEM
                && message.textContent().contains("冻结规划政策")));
    }

    @Test void activatingRequiresPlanSkillEnablesFrozenPolicyAndTheSameAdmissionGate() {
        String entry = "专业任务必须先计划";
        var descriptor = new SkillActivation.Descriptor(true, false, SkillActivation.Mode.ON_DEMAND,
                new SkillActivation.Metadata("规划包", "冻结说明", List.of(), true), List.of(
                        new SkillActivation.Resource("SKILL.md", "text/markdown", "UTF8", entry, AgentExecutionCodec.digest(entry),
                                entry.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)), Optional.empty(), List.of(), 5);
        var mount = new SkillActivation.Mount("skill:planning", new CapabilityRef(CapabilityType.SKILL, "planning", "v1"),
                SkillActivation.Mode.ON_DEMAND, 1_000, descriptor);
        var f = new Fixture(List.of(mount));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertTrue(requestHistory(f, first).stream().noneMatch(message -> message.textContent().contains("冻结规划政策")));
        f.commit(first, f.model(new AgentAction.ActivateSkill("skill:planning"), 1));
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertTrue(requestHistory(f, second).stream().anyMatch(message -> message.role() == ModelRole.SYSTEM
                && message.textContent().contains("冻结规划政策")));
        f.commit(second, f.model(new AgentAction.Respond("不应跳过计划", List.of()), 2));
        assertTrue(f.materials.decision().isEmpty());
        assertTrue(f.materials.messages().getLast().textContent().contains("plan_required_before_execution"));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var third = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(1, requestHistory(f, third).stream().filter(message -> message.role() == ModelRole.SYSTEM
                && message.textContent().contains("冻结规划政策")).count());
    }

    /** 固定测试义务明确来自宿主，模型只对反馈作出下一次实际决定。 */
    private static AgentExecutionOutputs.Policy outputPolicy(boolean rejectRespond,boolean closing,Optional<String> result,Set<String> tools) {
        return new AgentExecutionOutputs.Policy(Optional.of(new SessionOutputContract.PreparationHint("真实执行规则","真实最终答复规则","原Run成果状态")),
                rejectRespond?Optional.of(new SessionOutputContract.ActionRejection("TASK_OUTPUT_RESOLUTION_REQUIRED","先执行实际成果冻结")):Optional.empty(),
                Optional.empty(),result,new ExecutionBudgetPort.ClosingSpec(2,1,1,100,List.of("真实收尾义务"),tools),closing);
    }
    @Test void declaredToolErrorKeepsItsFeedbackAndPublishesBusinessFailure() {
        for (boolean error : List.of(false, true)) {
            var f = new Fixture();
            var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(first, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
            var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(tool, f.tool(tool, "{\"content\":[],\"isError\":" + error + ",\"structuredContent\":{\"answer\":\"ACTUAL_RESULT\"}}"));
            var event = f.codec.parse(f.lastAdoption.events().getFirst().data(), AgentExecutionProgram.TOOL_RESULT_SCHEMA);
            assertEquals("SUCCEEDED", AgentExecutionCodec.string(event, "status"));
            assertTrue(event.path("outputAccepted").booleanValue());
            assertEquals(error, event.path("businessFailed").booleanValue());
            var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            assertTrue(requestHistory(f, next).getLast().textContent().contains("ACTUAL_RESULT"));
        }
    }

    @Test void batchToolErrorProducesFailureForOnlyItsOwnProcess() {
        var f = new Fixture();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.batchModel(List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"))), 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials));
        for (int i = 0; i < batch.calls().size(); i++) {
            var call = batch.calls().get(i);
            var actual = new BatchCall(call.action(), call.attemptId(), f.tool(call, "{\"content\":[],\"isError\":" + (i == 0) + "}"));
            var result = f.program.inspectBatch(f.run, f.materials, actual, i + 1);
            var event = f.codec.parse(result.events().getFirst().data(), AgentExecutionProgram.TOOL_RESULT_SCHEMA);
            assertEquals(i == 0, event.path("businessFailed").booleanValue());
            assertEquals("SUCCEEDED", AgentExecutionCodec.string(event, "status"));
        }
    }

    /** 第二次实际请求含真实安全工具结果，正文、调用配对、配置读取和增量存储均可观察。 */
    @Test
    void carriesActualSafeToolResultIntoNextModelAndFinishesFromRealRespondDecision() {
        var f = new Fixture();
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeModelRequest(first.action()).request();
        assertEquals(f.configuration.initialMessages(), requestHistory(f,first));
        f.commit(first, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var frozenTool = f.codec.decodeTool(tool.action().request().content());
        assertEquals("sf_template", frozenTool.wireName());
        assertEquals(f.entry.target(), frozenTool.target());
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"secret-value\"}],\"structuredContent\":{\"answer\":\"REAL_TOOL_VALUE\",\"secret\":\"secret-value\"}}"));
        var process = f.lastAdoption.events().getFirst();
        assertEquals(AgentExecutionProgram.TOOL_RESULT_EVENT, process.type());
        var publicResult = f.codec.parse(process.data(), AgentExecutionProgram.TOOL_RESULT_SCHEMA);
        assertEquals("tool:template", AgentExecutionCodec.string(publicResult, "toolId"));
        assertEquals("SUCCEEDED", AgentExecutionCodec.string(publicResult, "status"));
        assertTrue(publicResult.get("outputAccepted").booleanValue());
        assertFalse(publicResult.path("businessFailed").booleanValue());
        assertEquals("工具调用已完成", AgentExecutionCodec.string(publicResult, "summary"));
        assertFalse(process.data().canonicalJson().contains("secret-value"));
        assertFalse(process.data().canonicalJson().contains("REAL_TOOL_VALUE"));
        assertFalse(f.context.content().canonicalJson().contains("REAL_TOOL_VALUE"));
        assertTrue(f.context.content().canonicalJson().length() < 1_000);
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var next = requestHistory(f,second);
        var assistant = next.get(next.size() - 2);
        var feedback = next.getLast();
        assertEquals(ModelRole.ASSISTANT, assistant.role());
        assertEquals(ModelRole.TOOL, feedback.role());
        assertEquals(assistant.toolCalls().getFirst().callId(), feedback.toolCallId().orElseThrow());
        assertTrue(feedback.textContent().contains("REAL_TOOL_VALUE"));
        assertFalse(feedback.textContent().contains("secret-value"));
        assertEquals(Optional.of("原推理"), assistant.reasoningContent());
        assertEquals(1, f.configurations);
        assertEquals(0, f.recoveryReads);
        f.commit(second, f.model(new AgentAction.Respond("完成：REAL_TOOL_VALUE", List.of()), 3));
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.COMPLETED, finish.status());
        assertEquals(AgentExecutionProgram.COMPLETED_EVENT, finish.events().getFirst().type());
        assertTrue(finish.events().getFirst().data().canonicalJson().contains("完成：REAL_TOOL_VALUE"));
        var event = f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA);
        AgentExecutionCodec.fields(event, "text", "reason", "status", "resultRef", "taskResultRef", "inputId", "rootInputId", "traceId", "spanId", "agentId", "evidence");
        assertTrue(event.get("taskResultRef").isNull());
        assertEquals(SessionFixtures.INPUT.value(), AgentExecutionCodec.string(event, "inputId"));
        assertEquals(SessionFixtures.IDENTITY.trace().rootInputId().value(), AgentExecutionCodec.string(event, "rootInputId"));
        assertEquals(SessionFixtures.IDENTITY.trace().traceId(), AgentExecutionCodec.string(event, "traceId"));
        assertEquals(SessionFixtures.IDENTITY.trace().spanId(), AgentExecutionCodec.string(event, "spanId"));
        assertEquals(SessionFixtures.IDENTITY.agentId().value(), AgentExecutionCodec.string(event, "agentId"));
    }

    /** 重建程序仅读取base.input和确切原结果引用，恢复后不需要旧checkpoint或重新调用工具。 */
    @Test
    void rebuildsTypedMaterialsFromDurableInputAndSavedReferenceDeltas() {
        var f = new Fixture();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"原真实工具结果\"}]}"));
        int preparations = f.mediaPreparations;
        var rebuilt = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        var restored = rebuilt.open(f.run, f.context);
        assertEquals(f.materials, restored);
        assertEquals(2, f.configurations);
        assertTrue(f.recoveryReads > 0);
        assertEquals(preparations, f.mediaPreparations);
        var next = assertInstanceOf(Step.Dispatch.class, rebuilt.next(f.run, restored));
        assertEquals(ExecutionStore.Kind.MODEL, next.action().kind());
        assertTrue(requestHistory(f,next).getLast().textContent().contains("原真实工具结果"));
    }

    /** 已保存的参数拒绝反馈和后续冻结请求在重建时保持原回执，不重派工具。 */
    @Test
    void rebuildsSavedMcpArgumentFailureAndFollowingFrozenModelRequest() {
        var f = new Fixture();
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.TOOL, tool.action().kind());
        var receipt = new ResultRef("original-mcp-receipt");
        var failure = new ToolOutcome.Failed(new PortFailure("mcp_tool_arguments_invalid", RetryMode.NONE, receipt));
        var raw = f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeTool(PortalCallKind.TOOL_INVOKE, failure);
        var feedback = new Material(new ResultRef("saved-mcp-feedback"), new TypedPayload(AgentExecutionCodec.TOOL_FAILURE_SCHEMA,
                "{\"reasonCode\":\"mcp_tool_arguments_invalid\",\"resultRef\":\"original-mcp-receipt\"}"));
        var result = new Material(new ResultRef("saved-mcp-result"), f.codec.encodeOutcome(raw));
        f.commit(tool, new CapabilityResult(ExecutionStore.ResultKind.FAILED, result, Optional.of(feedback), Optional.empty(), ExecutionBudgetPort.Usage.unreported()));

        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, next.action().kind());
        var frozenHistory = requestHistory(f, next);
        var text = frozenHistory.getLast().textContent();
        assertTrue(text.contains("mcp_tool_arguments_invalid"));
        assertTrue(text.contains("original-mcp-receipt"));
        assertTrue(text.contains("工具拒绝参数，未取得可用数据"));
        f.commit(next, f.model(new AgentAction.Respond("已按原反馈处理", List.of()), next.action().ordinal()));

        var rebuilt = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        var restored = rebuilt.open(f.run, f.context);
        assertEquals(f.materials, restored);
        assertEquals(text, restored.messages().stream().filter(message -> message.role() == ModelRole.TOOL).findFirst().orElseThrow().textContent());
        assertEquals(frozenHistory, restored.messages().subList(0, frozenHistory.size()));
        assertEquals(result, f.saved.stream().filter(material -> material.reference().equals(result.reference())).findFirst().orElseThrow());
        assertEquals(feedback, f.saved.stream().filter(material -> material.reference().equals(feedback.reference())).findFirst().orElseThrow());
        assertTrue(f.recoveryReads > 0);
        assertEquals(1, restored.usage().toolCalls());
        assertInstanceOf(Step.Finish.class, rebuilt.next(f.run, restored));
    }

    /** 写工具的确定失败仍保留副作用不确定性；恢复不能重复派发或重复计费。 */
    @Test
    void mcpWriteArgumentFailureReachesModelAndSurvivesColdRecovery() {
        var f = new Fixture(); var c = f.configuration; var old = f.entry.target();
        var target = new ToolPortal.FrozenToolTarget(old.handle(), old.capability(), old.deploymentId(), old.deploymentRevision(),
                old.inputSchema(), old.outputSchema(), ToolEffect.NON_IDEMPOTENT_WRITE, false, old.timeout(), old.modelInvocable(),
                old.confirmation(), old.sensitiveFields(), old.providerRequirements(), old.outputSchemaDeclared(), old.callIdentityPolicy());
        var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.MCP, "write-server", "write-record");
        var entry = new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(binding.serverIdentity(),
                binding.serverIdentity() + "/" + binding.toolName(), binding.toolName(), Optional.empty(), "写入记录"), false);
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), c.modelTools(), List.of(entry), c.budget(),
                c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(),
                c.workflows(), c.initialPlanState(), c.planRequired());
        f.materials = f.program.open(f.run, f.context);
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.TOOL, tool.action().kind());
        var request = f.codec.decodeTool(tool.action().request().content());
        assertEquals(ToolEffect.NON_IDEMPOTENT_WRITE, request.target().effect());
        var failure = new ToolOutcome.Failed(new PortFailure("mcp_tool_arguments_invalid_after_dispatch", RetryMode.NONE, new ResultRef("original-write-receipt")));
        var raw = f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeTool(PortalCallKind.TOOL_INVOKE, failure);
        f.commit(tool, new CapabilityResult(ExecutionStore.ResultKind.FAILED,
                new Material(new ResultRef("saved-write-result"), f.codec.encodeOutcome(raw)),
                f.codec.safeToolFeedback(request, failure).map(value -> new Material(new ResultRef("saved-write-feedback"), value)),
                Optional.empty(), ExecutionBudgetPort.Usage.unreported()));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, next.action().kind());
        var text = requestHistory(f, next).getLast().textContent();
        assertTrue(text.contains("原因码：mcp_tool_arguments_invalid_after_dispatch"));
        assertTrue(text.contains("结果引用：original-write-receipt"));
        assertTrue(text.contains("工具提示参数可能有误"));
        assertTrue(text.contains("失败不代表未执行或没有副作用"));
        assertTrue(text.contains("先依据本地结果引用核查已有副作用与幂等约束"));
        assertTrue(text.contains("有权限的人员核查"));
        assertTrue(text.contains("确认安全后依据本次已授权工具的真实 Schema 考虑修正参数"));
        assertTrue(text.contains("不要盲目重发"));
        var usage = f.materials.usage();
        assertEquals(1, usage.toolCalls());
        var rebuilt = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        var restored = rebuilt.open(f.run, f.context);
        assertEquals(f.materials, restored);
        assertEquals(usage, restored.usage());
        assertEquals(text, restored.messages().getLast().textContent());
        var resumed = assertInstanceOf(Step.Dispatch.class, rebuilt.next(f.run, restored));
        assertEquals(ExecutionStore.Kind.MODEL, resumed.action().kind());
        assertEquals(text, requestHistory(f, resumed).getLast().textContent());
        assertEquals(usage, restored.usage());
    }

    /** 批次乱序回调只在完整后形成一条assistant/N条tool，精确引用恢复保留原配对与用量。 */
    @Test
    void adoptsCompleteToolBatchInOriginalOrderAndRestoresWithoutRepreparingMedia() {
        var f = new Fixture();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var calls = List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}")));
        f.commit(model, f.batchModel(calls, 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials));
        assertEquals(model.action().action(), batch.sourceModelAction()); assertEquals(2, batch.calls().size());
        var first = batch.calls().getFirst(); var second = batch.calls().getLast();
        var firstActual = new BatchCall(first.action(), first.attemptId(), f.tool(first, "{\"content\":[{\"type\":\"text\",\"text\":\"FIRST_ACTUAL\"}]}"));
        var secondActual = new BatchCall(second.action(), second.attemptId(), f.tool(second, "{\"content\":[{\"type\":\"text\",\"text\":\"SECOND_ACTUAL\"}]}"));
        var before = f.materials;
        assertTrue(f.program.inspectBatch(f.run, before, secondActual, 2).waiting().isEmpty());
        assertEquals(before, f.materials);
        assertThrows(IllegalArgumentException.class, () -> f.program.adoptBatch(f.run, before, List.of(new BatchOutcome.Actual(2, secondActual))));
        f.commitBatch(List.of(secondActual, firstActual));
        assertEquals(2, f.materials.usage().toolCalls()); assertEquals(1, f.materials.usage().businessModelCalls());
        var messages = f.materials.messages(); var assistant = messages.get(messages.size() - 3);
        assertEquals(2, assistant.toolCalls().size()); assertEquals(Optional.of("原批次推理"), assistant.reasoningContent());
        assertEquals("FIRST_ACTUAL", messages.get(messages.size() - 2).textContent()); assertEquals("SECOND_ACTUAL", messages.getLast().textContent());
        assertEquals("provider-call:1:1", messages.get(messages.size() - 2).toolCallId().orElseThrow());
        assertEquals("provider-call:1:2", messages.getLast().toolCallId().orElseThrow());
        assertFalse(f.context.content().canonicalJson().contains("FIRST_ACTUAL"));
        int preparations = f.mediaPreparations;
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        assertEquals(preparations, f.mediaPreparations);
        assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)).action().kind());
    }

    @Test void nonParallelReadOnlyCallsKeepNativeIdsAndRestoreTheirSerialMode() {
        for (boolean storedEligible : List.of(false, true)) {
            var f = new Fixture(); var c = f.configuration;
            var entry = new SessionToolDirectory.Entry(f.entry.target(), f.entry.binding(), f.entry.discovery(), false);
            var projected = SessionNativeConversation.modelTools(c.profile(), List.of(entry), Map.of(entry.target().handle().value(), "frozen-template"), List.of());
            assertTrue(projected.stream().filter(tool -> tool.kind() == ModelToolKind.TOOL_CALL).allMatch(ModelTool::batchEligible));
            assertTrue(projected.stream().filter(tool -> tool.kind() == ModelToolKind.TOOL_CALL).allMatch(tool -> tool.description().startsWith("parallel_safe=false")));
            // 已冻结的旧false声明保持原值；新声明true也不授予实际并行。
            var declarations = projected.stream().map(tool -> tool.kind() == ModelToolKind.TOOL_CALL
                    ? new ModelTool(tool.wireName(), tool.toolHandle(), tool.description(), tool.inputSchema(), tool.strict(), tool.kind(), storedEligible) : tool).toList();
            f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), declarations, List.of(entry), c.budget(),
                    c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(),
                    c.workflows(), c.initialPlanState(), c.planRequired());
            f.materials = f.program.open(f.run, f.context);
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.batchModel(List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                    new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"))), 1));
            assertFalse(f.program.batchParallel(f.materials));
            var restored = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
            assertFalse(f.program.batchParallel(restored));
            assertEquals(storedEligible, restored.configuration().modelTools().stream().filter(tool -> tool.kind() == ModelToolKind.TOOL_CALL).findFirst().orElseThrow().batchEligible());
            var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, restored));
            var actuals = batch.calls().stream().map(call -> new BatchCall(call.action(), call.attemptId(), f.tool(call, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"))).toList();
            f.commitBatch(actuals);
            var messages = f.materials.messages();
            assertEquals(List.of("provider-call:1:1", "provider-call:1:2"), messages.get(messages.size()-3).toolCalls().stream().map(ModelToolCall::callId).toList());
            assertEquals("provider-call:1:1", messages.get(messages.size()-2).toolCallId().orElseThrow());
            assertEquals("provider-call:1:2", messages.getLast().toolCallId().orElseThrow());
            ModelPort.validateConversation(messages);
            var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS); var completed = cold.open(f.run, f.context);
            assertEquals(f.materials, completed); assertEquals(2, completed.usage().toolCalls());
            assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, cold.next(f.run, completed)).action().kind(),
                    "已提交批次冷恢复后进入下一模型轮，不再次产生工具派发");
        }
    }


    @Test void definiteDispatchAuthorizationDenialPublishesFailureButOtherRefusalsStayUnclassified() {
        var f = new Fixture();
        var finish = f.program.dispatchRejected(f.run, f.materials, "execution_dispatch_unauthorized").orElseThrow();
        assertEquals(RunStatus.FAILED, finish.status());
        var data = f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA);
        assertEquals("execution_dispatch_unauthorized", AgentExecutionCodec.string(data, "reason"));
        assertEquals("FAILED", AgentExecutionCodec.string(data, "status"));
        for (String reason : List.of("execution_dispatch_paused", "execution_lease_or_revision_changed", "execution_dispatch_actor_changed", "execution_result_unknown", "unknown")) {
            assertTrue(f.program.dispatchRejected(f.run, f.materials, reason).isEmpty());
            assertTrue(f.program.batchDispatchRejectionAdoption(f.run, f.materials, List.of(), reason).isEmpty());
        }
    }

    @Test void deniedBatchContinuationRetainsActualPhysicalCallsAndRecoversItsStoppedContext() {
        var f = new Fixture(); var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.batchModel(List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"))), 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials)); var history = f.materials.messages();
        var one = new BatchCall(batch.calls().getFirst().action(), batch.calls().getFirst().attemptId(), f.inputRequired("one", "one"));
        var two = new BatchCall(batch.calls().getLast().action(), batch.calls().getLast().attemptId(), f.tool(batch.calls().getLast(), "{\"content\":[{\"type\":\"text\",\"text\":\"ACTUAL_SIBLING\"}]}"));
        for (var actual : List.of(one, two)) {
            f.saved.add(actual.action().request()); f.saved.addAll(actual.action().requestParts()); f.saved.add(actual.result().result()); actual.result().feedback().ifPresent(f.saved::add);
        }
        f.readyBatch();
        var adoption = f.program.batchDispatchRejectionAdoption(f.run, f.materials, List.of(new BatchOutcome.Actual(1, one), new BatchOutcome.Actual(2, two)), "execution_dispatch_unauthorized").orElseThrow();
        assertEquals(2, adoption.next().toolCalls()); assertEquals(history, adoption.materials().messages());
        assertEquals("execution_dispatch_unauthorized", adoption.materials().problem().orElseThrow().reason());
        f.saved.add(adoption.context()); f.context = adoption.context(); f.materials = adoption.materials(); f.run = f.run(adoption.next(), f.run.revision() + 1);
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test void correctedToolArgumentsDispatchOnlyTheNewValidCallAndRecoverItsActualResult() {
        var f = new Fixture();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("rejected-array", "sf_template", "[]"))), "tool_calls", 1, Optional.empty()));
        var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS); var restored = cold.open(f.run, f.context);
        var correction = assertInstanceOf(Step.Dispatch.class, cold.next(f.run, restored));
        assertEquals(ExecutionStore.Kind.MODEL, correction.action().kind());
        ModelPort.validateConversation(requestHistory(f, correction));
        f.commit(correction, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("corrected-object", "sf_template", "{}"))), "tool_calls", correction.action().ordinal(), Optional.empty()));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.TOOL, tool.action().kind());
        assertEquals("corrected-object", f.codec.decodeTool(tool.action().request().content()).callId());
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"ACTUAL_CORRECTED_RESULT\"}]}"));
        assertEquals(1, f.materials.usage().toolCalls());
        assertEquals("ACTUAL_CORRECTED_RESULT", f.materials.messages().getLast().textContent());
        ModelPort.validateConversation(f.materials.messages());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test void parameterCorrectionStopsAtFrozenModelBudgetWithoutAnyToolDispatch() {
        var f = Fixture.withBusinessModelCalls(1);
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.ToolCall("bad-json", "sf_template", "[]"))), "tool_calls", 1, Optional.empty()));
        assertTrue(f.materials.problem().isEmpty()); assertTrue(f.materials.decision().isEmpty());
        assertEquals(0, f.materials.usage().toolCalls()); ModelPort.validateConversation(f.materials.messages());
        var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS); var restored = cold.open(f.run, f.context);
        assertEquals(f.materials, restored);
        var finish = assertInstanceOf(Step.Finish.class, cold.next(f.run, restored));
        assertEquals(RunStatus.TERMINATED, finish.status());
        assertEquals("BUSINESS_MODEL_CALL_BUDGET_EXCEEDED", AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
    }

    @Test void invalidToolArgumentsUseSameAdmissionForSingleBatchAndMixedResourceAcrossColdRecovery() {
        for (int size : List.of(1, 2, 3)) {
            var f = skillBatchFixture(10); var c = f.configuration; var old = f.entry.target();
            var schema = new TypedPayload("json-schema", "{\"type\":\"object\",\"properties\":{\"browser\":{\"type\":\"string\"}},\"additionalProperties\":false}");
            var target = new ToolPortal.FrozenToolTarget(old.handle(), old.capability(), old.deploymentId(), old.deploymentRevision(), schema,
                    old.outputSchema(), old.effect(), old.idempotent(), old.timeout(), old.modelInvocable(), old.confirmation(), old.sensitiveFields(), old.providerRequirements(), old.outputSchemaDeclared(), old.callIdentityPolicy());
            var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), true);
            var declarations = c.modelTools().stream().map(tool -> tool.kind() == ModelToolKind.TOOL_CALL
                    ? new ModelTool(tool.wireName(), tool.toolHandle(), tool.description(), schema, tool.strict(), tool.kind(), true) : tool).toList();
            f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), declarations, List.of(entry), c.budget(), c.capabilitySnapshot(), c.capabilityRef(),
                    c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired());
            f.materials = f.program.open(f.run, f.context); var initialSkills = f.materials.skills();
            for (int attempt = 1; attempt <= 3; attempt++) {
                var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                var calls = new ArrayList<AssistantContent>();
                calls.add(new AssistantContent.ToolCall("bad:" + attempt, "sf_template", "{\"parameters\":{}}"));
                if (size >= 2) calls.add(new AssistantContent.ToolCall("valid:" + attempt, "sf_template", "{}"));
                if (size == 3) calls.add(skillResourceCall("resource:" + attempt, "references/guide.md"));
                f.commit(model, f.nativeModel(new AssistantTurn(calls), "tool_calls", model.action().ordinal(), Optional.of("原始推理")));
                assertEquals(initialSkills, f.materials.skills()); assertEquals(0, f.materials.usage().toolCalls());
                var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
                assertEquals(f.materials, cold.open(f.run, f.context));
                if (attempt <= 2) {
                    assertTrue(f.materials.problem().isEmpty()); assertTrue(f.materials.decision().isEmpty());
                    var messages = f.materials.messages(); ModelPort.validateConversation(messages);
                    assertTrue(messages.getLast().textContent().contains("/parameters"));
                    assertTrue(messages.getLast().textContent().contains("additionalProperties"));
                    var assistant = messages.get(messages.size() - calls.size() - 2);
                    assertEquals(calls.size(), assistant.toolCalls().size()); assertEquals(Optional.of("原始推理"), assistant.reasoningContent());
                    assertEquals("{\"parameters\":{}}", assistant.toolCalls().getFirst().arguments().canonicalJson());
                } else {
                    assertEquals("action_correction_limit", f.materials.problem().orElseThrow().reason());
                    assertEquals(RunStatus.FAILED, assertInstanceOf(Step.Finish.class, cold.next(f.run, cold.open(f.run, f.context))).status());
                }
            }
        }
    }

    @Test void invalidBatchSchemaEffectOrConfirmationFinishesBeforeAnyToolDispatch() {
        for (var reason : List.of("schema", "write", "confirmation")) {
            var f = new Fixture(); var c = f.configuration; var old = f.entry.target();
            var target = new ToolPortal.FrozenToolTarget(old.handle(), old.capability(), old.deploymentId(), old.deploymentRevision(),
                    reason.equals("schema") ? new TypedPayload("json-schema", "{\"type\":\"object\",\"required\":[\"required_value\"]}") : old.inputSchema(),
                    old.outputSchema(), reason.equals("write") ? ToolEffect.IDEMPOTENT_WRITE : old.effect(), old.idempotent(), old.timeout(), old.modelInvocable(),
                    reason.equals("confirmation") ? ToolPortal.ToolConfirmationPolicy.POLICY : old.confirmation(), old.sensitiveFields(), old.providerRequirements(), old.outputSchemaDeclared(), old.callIdentityPolicy());
            var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), false);
            var declarations = c.modelTools().stream().map(tool -> tool.kind() == ModelToolKind.TOOL_CALL
                    ? new ModelTool(tool.wireName(), tool.toolHandle(), tool.description(), target.inputSchema(), tool.strict(), tool.kind(), false) : tool).toList();
            f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), declarations, List.of(entry), c.budget(),
                    c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(),
                    c.workflows(), c.initialPlanState(), c.planRequired());
            f.materials = f.program.open(f.run, f.context);
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.batchModel(List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                    new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"))), 1));
            assertEquals(0, f.materials.usage().toolCalls()); assertEquals(1, f.materials.ordinal());
            var restored = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
            assertEquals(f.materials, restored);
            if (reason.equals("schema")) {
                assertTrue(f.materials.problem().isEmpty()); assertTrue(f.materials.decision().isEmpty());
                assertTrue(f.materials.messages().getLast().textContent().contains("/required_value"));
                assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, restored)).action().kind());
            } else {
                assertEquals("tool_batch_ineligible", f.materials.problem().orElseThrow().reason());
                assertEquals(RunStatus.FAILED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, restored)).status());
            }
        }
    }

    @Test void exhaustedCommonBudgetDoesNotClassifyInvalidBatch() {
        for (var reason : List.of("duplicate", "write")) {
            var f = new Fixture();
            if (reason.equals("write")) {
                var c = f.configuration; var old = f.entry.target();
                var target = new ToolPortal.FrozenToolTarget(old.handle(), old.capability(), old.deploymentId(), old.deploymentRevision(),
                        old.inputSchema(), old.outputSchema(), ToolEffect.NON_IDEMPOTENT_WRITE, false, old.timeout(), old.modelInvocable(),
                        old.confirmation(), old.sensitiveFields(), old.providerRequirements(), old.outputSchemaDeclared(), old.callIdentityPolicy());
                var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), false);
                f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), c.modelTools(), List.of(entry), c.budget(),
                        c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(),
                        c.workflows(), c.initialPlanState(), c.planRequired());
                f.materials = f.program.open(f.run, f.context);
            }
            var first = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}"));
            var second = reason.equals("duplicate") ? first : new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"));
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.elapsed = Duration.ofMinutes(6); f.failCurrentRead = true;
            f.commit(model, f.batchModel(List.of(first, second), 1));
            assertEquals(List.of(false, false), f.materials.currentReads());
            assertEquals(0, f.materials.usage().toolCalls());
            var restored = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
            assertEquals(f.materials, restored);
            var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, restored));
            assertEquals(RunStatus.TERMINATED, finish.status());
            assertEquals("WALL_TIME_BUDGET_EXCEEDED", AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
        }
    }

    /** 任何未决或重复槽都不暴露部分可派发动作；整组已保存结果只复用原安全消息。 */
    @Test
    void reusesWholeSavedToolBatchWithoutNewToolAttemptsAndRejectsUnknownOrDuplicateSlots() {
        var f = new Fixture();
        var calls = List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}")));
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(model, f.batchModel(calls, 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials));
        var actuals = batch.calls().stream().map(call -> new BatchCall(call.action(), call.attemptId(), f.tool(call, "{\"content\":[{\"type\":\"text\",\"text\":\"SAVED_ACTUAL_" + call.action().ordinal() + "\"}]}"))).toList();
        var pending = new BatchCall(batch.calls().getFirst().action(), batch.calls().getFirst().attemptId(), f.inputRequired("batch", "opaque-batch"));
        assertTrue(f.program.inspectBatch(f.run, f.materials, pending, 1).waiting().isPresent());
        assertThrows(IllegalArgumentException.class, () -> f.program.adoptBatch(f.run, f.materials, List.of(new BatchOutcome.Actual(1, pending), new BatchOutcome.Actual(2, actuals.getLast()))));
        f.commitBatch(actuals);
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(next, f.batchModel(calls, 4));
        var reused = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, reused.action().kind()); assertEquals(2, f.materials.usage().toolCalls());
        var messages = requestHistory(f,reused);
        assertEquals("provider-call:4:2", messages.getLast().toolCallId().orElseThrow());
        assertTrue(messages.getLast().textContent().contains("SAVED_ACTUAL_3"));
        assertTrue(messages.getLast().textContent().contains("未重新执行工具"));
        f.commit(reused, f.model(new AgentAction.Respond("批次复用完成", List.of()), 5));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var duplicate = new Fixture(); var first = assertInstanceOf(Step.Dispatch.class, duplicate.program.next(duplicate.run, duplicate.materials));
        duplicate.commit(first, duplicate.batchModel(List.of(calls.getFirst(), calls.getFirst()), 1));
        assertEquals(RunStatus.FAILED, assertInstanceOf(Step.Finish.class, duplicate.program.next(duplicate.run, duplicate.materials)).status());
    }

    @Test void currentReadBatchKeepsEachFrozenSlotAcrossRestore() {
        var f = new Fixture(); f.currentRead = true;
        var calls = List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}")));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.batchModel(calls, 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials));
        var actual = batch.calls().stream().map(call -> new BatchCall(call.action(), call.attemptId(),
                f.tool(call, "{\"content\":[{\"type\":\"text\",\"text\":\"FIRST\"}]}"))).toList();
        f.commitBatch(actual);
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(model, f.batchModel(calls, 4));
        f.failCurrentRead = true;
        f.materials = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
        var refreshed = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials));
        assertEquals(2, refreshed.calls().size());
        var second = refreshed.calls().stream().map(call -> new BatchCall(call.action(), call.attemptId(),
                f.tool(call, "{\"content\":[{\"type\":\"text\",\"text\":\"SECOND\"}]}"))).toList();
        f.commitBatch(second);
        assertEquals(4, f.materials.usage().toolCalls());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    /** 一个复用槽和一个新槽共享原生回合；新Action序号按新调用数而非原slot分配。 */
    @Test void mixedBatchReusesOriginalSingleActionAndCountsOnlyFreshPhysicalAttempt() {
        var f = new Fixture(); var oldCall = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}"));
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(model, f.model(oldCall, 1));
        var old = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(old, f.tool(old, "{\"content\":[{\"type\":\"text\",\"text\":\"ORIGINAL_SINGLE\"}]}"));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(next, f.batchModel(List.of(oldCall, new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"))), 3));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials));
        var reused = assertInstanceOf(ExecutionStore.BatchMember.Reused.class, batch.members().getFirst());
        assertEquals(old.action().action(), reused.action()); assertEquals(old.attemptId(), reused.attemptId());
        var fresh = batch.calls().getFirst(); assertEquals(1, batch.calls().size()); assertEquals(4, fresh.action().ordinal());
        var outcomes = List.<BatchOutcome>of(new BatchOutcome.Reused(1, reused), new BatchOutcome.Actual(2, new BatchCall(fresh.action(), fresh.attemptId(), f.tool(fresh, "{\"content\":[{\"type\":\"text\",\"text\":\"ONLY_FRESH\"}]}"))));
        f.commitBatchOutcomes(outcomes); assertEquals(2, f.materials.usage().toolCalls()); assertEquals(4, f.materials.ordinal());
        assertEquals(3, f.materials.progress().size());
        assertEquals(List.of(1, 1, 2), f.materials.progress().stream().map(AgentToolProgress.Sample::slot).toList());
        assertTrue(f.materials.messages().get(f.materials.messages().size() - 2).textContent().contains("ORIGINAL_SINGLE"));
        assertTrue(f.materials.messages().get(f.materials.messages().size() - 2).textContent().contains("未重新执行工具"));
        assertEquals("ONLY_FRESH", f.materials.messages().getLast().textContent());
        assertEquals(2, f.materials.completedTools().size()); assertEquals(old.action().action(), f.materials.completedTools().getFirst().source().action());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    /** 多等待及重复输入只在最后完整采纳一次，opaque state、精确response与实际代数全部保留。 */
    @Test void resumesEachAcceptedBatchWaitWithoutPartialHistoryAndCountsEveryRoundTrip() {
        var f = new Fixture(); var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.batchModel(List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"))), 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials)); var history = f.materials.messages();
        var first = new BatchCall(batch.calls().getFirst().action(), batch.calls().getFirst().attemptId(), f.inputRequired("one", "OPAQUE_ONE"));
        var second = new BatchCall(batch.calls().getLast().action(), batch.calls().getLast().attemptId(), f.inputRequired("two", "OPAQUE_TWO"));
        var responses = List.of(f.batchResponse(first, "actor-one"), f.batchResponse(second, "actor-two")); f.readyBatch();
        var plan = assertInstanceOf(BatchPlan.Resume.class, f.program.batchContinuation(f.run, f.materials,
                List.of(new BatchOutcome.Actual(2, second), new BatchOutcome.Actual(1, first)), responses));
        @SuppressWarnings("unchecked") var resumed = (List<Step.Dispatch>) plan.calls();
        assertEquals(history, f.materials.messages()); assertEquals(0, f.materials.usage().toolCalls());
        var request = f.codec.decodeTool(resumed.getFirst().action().request().content());
        assertEquals(Optional.of("OPAQUE_ONE"), request.continuation().orElseThrow().requestState());
        assertEquals(responses.getFirst().response().reference(), request.continuation().orElseThrow().response());
        var repeated = new BatchCall(resumed.getFirst().action(), resumed.getFirst().attemptId(), f.inputRequired("again", "OPAQUE_AGAIN"));
        var finished = new BatchCall(resumed.getLast().action(), resumed.getLast().attemptId(), f.tool(resumed.getLast(), "{\"content\":[{\"type\":\"text\",\"text\":\"SECOND_FINAL\"}]}"));
        var again = f.batchResponse(repeated, "third-actor");
        var nextPlan = assertInstanceOf(BatchPlan.Resume.class, f.program.batchContinuation(f.run, f.materials,
                List.of(new BatchOutcome.Actual(1, repeated), new BatchOutcome.Actual(2, finished)), List.of(again)));
        @SuppressWarnings("unchecked") var nextCalls = (List<Step.Dispatch>) nextPlan.calls(); assertEquals(1, nextCalls.size());
        assertTrue(nextCalls.getFirst().attemptId().endsWith(":attempt:3"));
        var finalFirst = new BatchCall(nextCalls.getFirst().action(), nextCalls.getFirst().attemptId(), f.tool(nextCalls.getFirst(), "{\"content\":[{\"type\":\"text\",\"text\":\"FIRST_FINAL\"}]}"));
        f.commitBatchOutcomes(List.of(new BatchOutcome.Actual(1, finalFirst), new BatchOutcome.Actual(2, finished)));
        assertEquals(5, f.materials.usage().toolCalls()); assertEquals(history.size() + 3, f.materials.messages().size());
        assertEquals("FIRST_FINAL", f.materials.messages().get(f.materials.messages().size() - 2).textContent());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    /** 续跑预算已用尽仍准确保存最初两次输入等待，终止增量不追加半个native回合。 */
    @Test void exhaustedBatchContinuationProducesExactStopDeltaAndPhysicalCounts() {
        var f = new Fixture(2); var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.batchModel(List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")), new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}"))), 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials)); var original = f.materials.messages();
        var one = new BatchCall(batch.calls().getFirst().action(), batch.calls().getFirst().attemptId(), f.inputRequired("one", "one"));
        var two = new BatchCall(batch.calls().getLast().action(), batch.calls().getLast().attemptId(), f.inputRequired("two", "two"));
        var responses = List.of(f.batchResponse(one, "one"), f.batchResponse(two, "two")); f.readyBatch();
        var stopped = assertInstanceOf(BatchPlan.Terminate.class, f.program.batchContinuation(f.run, f.materials, List.of(new BatchOutcome.Actual(1, one), new BatchOutcome.Actual(2, two)), responses));
        @SuppressWarnings("unchecked") var adoption = (Adoption<AgentExecutionProgram.Materials>) stopped.adoption();
        assertEquals(RunStatus.TERMINATED, stopped.status()); assertEquals(2, adoption.next().toolCalls()); assertEquals(original, adoption.materials().messages());
        f.saved.add(adoption.context()); f.context = adoption.context(); f.materials = adoption.materials(); f.run = f.run(adoption.next(), f.run.revision() + 1);
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    /** 委派真实回执按所属codec恢复；不同已提交模型Action保持新命令，不能纯缓存跳过Host当前权限。 */
    @Test
    void adoptsActualDelegationReceiptAndDispatchesRepeatedNewModelCommandThroughHost() {
        var f = new Fixture(true); var command = DelegationCommand.delegate("member:writer", "完成子任务", true);
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(model, f.model(new AgentAction.Delegate(command), 1));
        var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); assertEquals(ExecutionStore.Kind.DELEGATION, dispatched.action().kind());
        var codec = new SessionExecutionDelegations(Fixture.LIMITS); var request = codec.decodeRequest(dispatched.action().request().content());
        assertEquals(command,request.command()); assertEquals("delegate", request.wireName()); assertEquals("provider-call:1", request.callId());
        f.commit(dispatched, f.delegation(dispatched, new TypedPayload("application/json", "{\"status\":\"accepted\",\"delegation_id\":\"actual-delegation\"}"), Optional.empty()));
        assertEquals(1, f.materials.usage().toolCalls()); assertTrue(f.materials.pendingDependency().isEmpty());
        var messages = f.materials.messages(); var assistant = messages.get(messages.size() - 2);
        assertEquals("delegate", assistant.toolCalls().getFirst().wireName()); assertEquals(Optional.of("原推理"), assistant.reasoningContent());
        assertEquals("provider-call:1", messages.getLast().toolCallId().orElseThrow()); assertTrue(messages.getLast().textContent().contains("actual-delegation"));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(next, f.model(new AgentAction.Delegate(command), 3));
        var repeated = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.DELEGATION, repeated.action().kind()); assertEquals(4, repeated.action().ordinal());
        assertEquals("provider-call:3", codec.decodeRequest(repeated.action().request().content()).callId());
    }

    /** 持久委派等待不追加半条native回合；最终系统响应只本地采纳一次并保留原身份和实际结果。 */
    @Test
    void consumesSavedDelegationDependencyResponseWithoutRedispatchingOriginalCommand() {
        var f = new Fixture(true); var command = DelegationCommand.delegate("member:writer", "等待实际完成", false);
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(new AgentAction.Delegate(command), 1));
        var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); var messages = f.materials.messages();
        var dependency = new SessionExecutionDelegations.Dependency("business-wait", List.of("actual-delegation"),
                List.of(new com.skillforge.runtime.api.RunRef(SessionFixtures.TENANT, "actual-child-run")));
        f.commit(dispatched, f.delegation(dispatched, new TypedPayload("application/json", "{\"status\":\"waiting\"}"), Optional.of(dependency)));
        var pending = assertInstanceOf(AgentExecutionProgram.PendingDelegation.class, f.materials.pendingDependency().orElseThrow());
        assertEquals(messages, f.materials.messages()); assertEquals(1, f.materials.usage().toolCalls());
        assertEquals(ExecutionStore.WaitKind.DELEGATION, f.lastAdoption.waiting().orElseThrow().kind());
        var publicWait = f.codec.parse(f.lastAdoption.events().getFirst().data(), AgentExecutionProgram.WAITING_SCHEMA);
        assertEquals("delegation", AgentExecutionCodec.string(publicWait, "kind")); assertTrue(publicWait.get("question").isNull()); assertTrue(publicWait.get("requests").isEmpty());
        assertFalse(f.lastAdoption.events().getFirst().data().canonicalJson().contains("actual-child-run"));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var responseCodec = new SessionExecutionDelegations(Fixture.LIMITS);
        var waiting = f.accept(pending.waiting(), ExecutionStore.WaitKind.DELEGATION, Map.of(),
                responseCodec.encodeResponse(new TypedPayload("application/json", "{\"results\":[{\"status\":\"completed\",\"output\":\"ACTUAL_CHILD_OUTPUT\"}]}")),
                Optional.of(new ExecutionStore.DelegationWait(dependency.businessWaitId(), dependency.delegationIds(), dependency.children())));
        f.commitHuman(waiting);
        assertEquals(waiting.acceptedResponse().orElseThrow().input(), f.materials.observationInput());
        assertTrue(f.materials.pendingDependency().isEmpty()); assertEquals(1, f.materials.usage().toolCalls()); assertEquals(1, f.materials.usage().businessModelCalls());
        assertTrue(f.materials.messages().getLast().textContent().contains("ACTUAL_CHILD_OUTPUT"));
        assertEquals("provider-call:1", f.materials.messages().getLast().toolCallId().orElseThrow());
        assertFalse(f.context.content().canonicalJson().contains("ACTUAL_CHILD_OUTPUT"));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var resumedModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, resumedModel.action().kind());
        assertResumedObservation(resumedModel, ExecutionObservation.Kind.LLM, waiting.acceptedResponse().orElseThrow().input());
    }

    /** 模型派生图片只属于该次请求，下一轮始终由原source生成，恢复也不再次prepare。 */
    @Test
    void keepsOriginalImageSourceAcrossPreparedModelRequestsAndRecovery() {
        var owner = SessionFixtures.IDENTITY.owner().sessionRef(); var source = new MediaRef(owner, "original-image", "1".repeat(64), "image/png", 1_000, 2400, 1600);
        var variant = new MediaRef(owner, "prepared-image", "2".repeat(64), "image/png", 100, 512, 341); var f = new Fixture(source); f.preparedVariant = Optional.of(variant);
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(source, f.codec.decodeModelRequest(first.action()).transformations().getFirst().source());
        f.commit(first, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        assertEquals(source, assertInstanceOf(ModelContent.Image.class, f.materials.messages().getFirst().contents().getLast()).media());
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"实际结果\"}]}"));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); var request = f.codec.decodeModelRequest(next.action());
        assertEquals(source, request.transformations().getFirst().source()); assertEquals(variant, request.transformations().getFirst().prepared());
        f.commit(next, f.model(new AgentAction.Respond("图像完成", List.of()), 3)); int preparations = f.mediaPreparations;
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context)); assertEquals(preparations, f.mediaPreparations);
    }

    /** 本地采纳不等于模型已消费；原MODEL之后的新补充不改变其决定或Plan等待观察序号。 */
    @Test
    void freezesModelSupplementalThroughAndPreservesSelectedDecisionWhenNewInputArrives() {
        var f = new Fixture(); f.commitSupplement("first", "模型应看到第一条", 1);
        assertEquals(new AgentExecutionProgram.SteeringState(1, 0), f.materials.steering()); assertEquals(0, f.materials.usage().businessModelCalls());
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); assertEquals(1, f.codec.decodeModelRequest(model.action()).supplementalIncludedThrough());
        assertResumedObservation(model, ExecutionObservation.Kind.LLM, new InputId("supplement-first"));
        var command = new PlanCommand(PlanCommand.Operation.GET, "actual-work", 0, Optional.empty(), "", "", "", List.of(), List.of());
        f.commit(model, f.model(new AgentAction.Plan(command), 1)); var selected = f.materials.decision(); f.commitSupplement("second", "尚未被原模型看到", 2);
        assertEquals(selected, f.materials.decision()); assertEquals(new AgentExecutionProgram.SteeringState(2, 1), f.materials.steering());
        var plan = assertInstanceOf(Step.PlanLocal.class, f.program.next(f.run, f.materials));
        assertEquals(1, new SessionExecutionPlans(Fixture.LIMITS).decodeRequest(plan.request().content()).supplementalIncludedThrough());
        f.commitPlan(plan, new SessionExecutionPlans.Result(new TypedPayload("application/json", "{\"status\":\"completed\"}"), Optional.empty(), PlanPort.State.absent()));
        assertEquals(0, f.materials.usage().toolCalls()); assertEquals(1, f.materials.usage().businessModelCalls());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); assertEquals(2, f.codec.decodeModelRequest(next.action()).supplementalIncludedThrough());
        assertResumedObservation(next, ExecutionObservation.Kind.LLM, new InputId("supplement-second"));
    }

    /** 同次活动材料采用Store返回的补充操作者后，紧接的模型或工具准备不再携带旧身份。 */
    @Test
    void usesCommittedSupplementalActorForImmediateModelAndToolDispatchWithoutReloading() {
        for (boolean tool : List.of(false, true)) {
            var f = new Fixture();
            if (tool) {
                var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                f.commit(model, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
            }
            int reads = f.recoveryReads;
            var actor = new VerifiedIdentity(SessionFixtures.ADMISSION.principal(), SessionFixtures.OWNER,
                    SessionFixtures.ADMISSION.scopes(), 2, new ResultRef("saved-steering-actor"));
            var input = new InputId("actor-steering"); var payload = new AgentExecutionSteering(Fixture.LIMITS).encode(input, "使用补充后的执行身份");
            var content = new Material(new ResultRef("actor-steering-content"), payload);
            var fact = new ExecutionStore.SupplementalInput(f.run.identity().runRef(), input, content.reference(),
                    AgentExecutionCodec.digest(payload.schemaId() + "\0" + payload.canonicalJson()), actor.verificationRef(), 1, OptionalLong.empty());
            var candidate = f.program.acceptInput(f.run, f.materials, fact, content);
            assertEquals(SessionFixtures.ADMISSION, candidate.materials().configuration().actor()); assertEquals(0, f.actorChanges);
            var before = f.run;
            f.run = new ExecutionStore.Run(before.identity(), before.configuration(), before.status(), before.revision() + 1, before.fence(), before.cancelled(), before.phase(),
                    candidate.next(), before.lease(), before.currentAction(), before.currentBatch(), before.waiting(), actor.verificationRef(), before.nextDueAt(), before.createdAt(), before.updatedAt());
            var applied = new ExecutionStore.SupplementalInput(fact.run(), fact.input(), fact.content(), fact.fingerprint(), fact.actorRef(), fact.ordinal(), OptionalLong.of(f.run.revision()));
            f.materials = f.program.inputCommitted(new ExecutionStore.InputAcceptance(f.run, applied, actor, true), candidate.materials());
            var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            assertEquals(tool ? ExecutionStore.Kind.TOOL : ExecutionStore.Kind.MODEL, next.action().kind());
            assertEquals(actor, next.actor()); assertEquals(actor, f.materials.configuration().actor()); assertEquals(actor, f.activeActor);
            assertEquals(f.configuration.withActor(actor), f.materials.configuration());
            assertEquals(before.identity(), f.run.identity()); assertEquals(1, f.actorChanges);
            assertEquals(1, f.configurations); assertEquals(reads, f.recoveryReads);
        }
    }

    /** 计划门禁沿原纯规则反馈模型；真实Plan等待只本地消费系统结果，不产生能力调用。 */
    @Test
    void rejectsIncompletePlanFinalAnswerLocallyAndConsumesActualPlanWaitState() {
        var state = new PlanPort.State(true, false, false, Optional.empty()); var f = new Fixture(state, true);
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(new AgentAction.Respond("不能提前完成", List.of()), 1));
        assertTrue(f.materials.decision().isEmpty()); assertTrue(f.materials.messages().getLast().textContent().contains("plan_steps_not_complete")); assertEquals(0, f.materials.usage().toolCalls());
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var waitCommand = new PlanCommand(PlanCommand.Operation.WAIT, "actual-work", 1, Optional.empty(), "", "", "", List.of(), List.of("member-step"));
        f.commit(next, f.model(new AgentAction.Plan(waitCommand), 2)); var plan = assertInstanceOf(Step.PlanLocal.class, f.program.next(f.run, f.materials));
        var dependency = new ExecutionStore.PlanWait("actual-work", 1, "actual-plan-wait");
        f.commitPlan(plan, new SessionExecutionPlans.Result(new TypedPayload("application/json", "{\"work_id\":\"actual-work\",\"plan_revision\":1}"), Optional.of(dependency), state));
        var pending = assertInstanceOf(AgentExecutionProgram.PendingPlan.class, f.materials.pendingDependency().orElseThrow());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var done = new PlanPort.State(true, true, true, Optional.empty()); var response = new SessionExecutionPlans(Fixture.LIMITS).encodeResponse(new TypedPayload("application/json", "{\"wait_satisfied\":true}"), done);
        f.commitHuman(f.accept(pending.waiting(), ExecutionStore.WaitKind.PLAN, Map.of(), response, Optional.of(dependency)));
        assertEquals(done, f.materials.planState()); assertEquals(0, f.materials.usage().toolCalls()); assertTrue(f.materials.pendingDependency().isEmpty());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    /** Workflow真实终态进入原native配对，恢复保留完整原PortalOutcome及真实输出。 */
    @Test
    void adoptsActualWorkflowTerminalFromFrozenBindingAndRestoresItsNativePair() {
        var binding = workflowBinding(); var f = new Fixture(binding); var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(new AgentAction.Dispatch(binding.target().handle().value()), 1)); var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.WORKFLOW, dispatched.action().kind()); var request = (SessionExecutionWorkflows.Dispatch) new SessionExecutionWorkflows(Fixture.LIMITS).decode(dispatched.action().request().content());
        assertEquals(AgentExecutionProgram.WORKFLOW_STARTED_EVENT, dispatched.events().getFirst().type());
        var started = f.codec.parse(dispatched.events().getFirst().data(), AgentExecutionProgram.WORKFLOW_STARTED_SCHEMA);
        assertEquals(dispatched.action().action().actionId(), AgentExecutionCodec.string(started, "actionId"));
        assertEquals("DISPATCHED", AgentExecutionCodec.string(started, "status")); assertFalse(started.has("parameters"));
        assertEquals(binding.parameters(), request.parameters()); assertEquals(binding.target(), request.target());
        f.commit(dispatched, f.workflow(dispatched, new WorkflowResult.Completed(request.executionRef(), new TypedPayload("application/json", "{\"answer\":\"ACTUAL_WORKFLOW_OUTPUT\"}"), 4, new ResultRef("actual-workflow-business-result")), Optional.empty()));
        assertEquals(1, f.materials.usage().toolCalls()); assertEquals("provider-call:1", f.materials.messages().getLast().toolCallId().orElseThrow());
        var feedbackText = f.materials.messages().getLast().textContent();
        assertTrue(feedbackText.contains("output.canonicalJson 是结果封套"));
        assertTrue(feedbackText.contains("按用户要求使用解码后的业务内容"));
        assertTrue(feedbackText.contains("ACTUAL_WORKFLOW_OUTPUT")); assertFalse(f.context.content().canonicalJson().contains("ACTUAL_WORKFLOW_OUTPUT"));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var event = f.lastAdoption.events().getFirst();
        assertEquals(AgentExecutionProgram.WORKFLOW_RESULT_EVENT, event.type());
        assertFalse(SessionExecutionWorkflows.RESULT_SCHEMA.equals(event.data().schemaId()));
        var publicOutput = f.codec.parse(event.data(), AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA);
        assertEquals("COMPLETED", AgentExecutionCodec.string(publicOutput, "status"));
        assertTrue(publicOutput.path("outputAccepted").booleanValue());
        assertEquals(new TypedPayload("application/json", "{\"answer\":\"ACTUAL_WORKFLOW_OUTPUT\"}"), AgentExecutionCodec.readPayload(publicOutput.get("output")));
        assertFalse(event.data().canonicalJson().contains("actual-workflow-business-result"));
        assertFalse(publicOutput.has("parameters")); assertFalse(publicOutput.has("context")); assertFalse(publicOutput.has("resultRef"));
    }

    /** Workflow挂起使用真实waitKey，系统信号恢复原Action新Attempt，最终只配对一次。 */
    @Test
    void workflowFailureCancellationTerminationAndUnknownDoNotPublishSuccessOrPrivateResult() {
        for (String status : List.of("FAILED", "CANCELLED", "TERMINATED", "UNKNOWN")) {
            var f = new Fixture(workflowBinding()); var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(first, f.model(new AgentAction.Dispatch("workflow:main"), 1));
            var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var request = (SessionExecutionWorkflows.Dispatch) new SessionExecutionWorkflows(Fixture.LIMITS).decode(dispatched.action().request().content());
            var privateResult = new ResultRef("PRIVATE_WORKFLOW_RECOVERY_RESULT");
            WorkflowResult outcome = switch (status) {
                case "FAILED" -> new WorkflowResult.Failed(request.executionRef(), OptionalLong.of(4), new PortFailure("workflow_failed", RetryMode.NONE, privateResult));
                case "CANCELLED" -> new WorkflowResult.Cancelled(request.executionRef(), 4, privateResult);
                case "TERMINATED" -> new WorkflowResult.Terminated(request.executionRef(), 4, com.skillforge.runtime.api.LoopTerminationReason.WORKFLOW_MAX_STEPS_REACHED, privateResult);
                default -> new WorkflowResult.OutcomeUnknown(request.executionRef(), OptionalLong.empty(), new PortFailure("workflow_outcome_unknown", RetryMode.RESULT_QUERYABLE, privateResult));
            };
            f.commit(dispatched, f.workflow(dispatched, outcome, Optional.empty()));
            var event = f.lastAdoption.events().getFirst(); var payload = f.codec.parse(event.data(), AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA);
            assertEquals(status, AgentExecutionCodec.string(payload, "status"));
            assertEquals(status, observation(f.lastAdoption.events(), ExecutionObservation.Kind.WORKFLOW).status().name());
            assertFalse(payload.path("outputAccepted").booleanValue()); assertTrue(payload.path("output").isNull());
            assertFalse(event.data().canonicalJson().contains("PRIVATE_WORKFLOW_RECOVERY_RESULT"));
            assertFalse(event.data().canonicalJson().contains("原受理输入"));
        }
    }

    /** Workflow挂起使用真实waitKey，系统信号恢复原Action新Attempt，最终只配对一次。 */
    @Test
    void resumesWorkflowFromExactSavedWaitAndInputWithoutRedispatchingInitialWorkflow() {
        var f = new Fixture(workflowBinding()); var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(new AgentAction.Dispatch("workflow:main"), 1));
        var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); var original = (SessionExecutionWorkflows.Dispatch) new SessionExecutionWorkflows(Fixture.LIMITS).decode(dispatched.action().request().content());
        var wait = new ExecutionStore.WorkflowWait(original.executionRef(), "actual-suspend", "actual-engine-wait-key", 5);
        f.commit(dispatched, f.workflow(dispatched, new WorkflowResult.Suspended(original.executionRef(), wait.suspendRef(), 5, new ResultRef("suspended-business-result"), com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_EXTERNAL), Optional.of(wait)));
        assertEquals(AgentExecutionProgram.WAITING_EVENT, f.lastAdoption.events().getFirst().type());
        assertEquals(ExecutionObservation.Status.WAITING, observation(f.lastAdoption.events(), ExecutionObservation.Kind.WORKFLOW).status());
        var pending = assertInstanceOf(AgentExecutionProgram.PendingWorkflow.class, f.materials.pendingDependency().orElseThrow());
        assertEquals(1, f.materials.messages().size()); assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var accepted = f.accept(pending.waiting(), ExecutionStore.WaitKind.WORKFLOW, Map.of(), new TypedPayload("skillforge.runtime.workflow.signal.v1", "{\"fact\":\"actual-node-result\"}"), Optional.of(wait));
        var resumed = assertInstanceOf(Step.Dispatch.class, f.program.continuation(f.run, f.materials, accepted, f.acceptedResponse));
        assertEquals(dispatched.action().action(), resumed.action().action()); assertEquals(dispatched.action().ordinal(), resumed.action().ordinal());
        assertEquals(Optional.of(pending.waiting()), resumed.continuation()); var request = (SessionExecutionWorkflows.Resume) new SessionExecutionWorkflows(Fixture.LIMITS).decode(resumed.action().request().content());
        assertTrue(resumed.events().stream().noneMatch(event -> event.type().equals(AgentExecutionProgram.MODEL_STARTED_EVENT)));
        assertEquals(wait.actionKey(), request.actionKey()); assertEquals(f.acceptedResponse.reference(), request.responseRef()); assertEquals(f.acceptedResponse.content(), request.response());
        assertEquals(accepted.acceptedResponse().orElseThrow().input(), request.inputId());
        assertResumedObservation(resumed, ExecutionObservation.Kind.WORKFLOW, request.inputId());
        f.commit(resumed, f.workflow(resumed, new WorkflowResult.Completed(original.executionRef(), new TypedPayload("application/json", "{\"answer\":\"RESUMED_ACTUAL_OUTPUT\"}"), 8, new ResultRef("completed-business-result")), Optional.empty()));
        assertEquals(2, f.materials.usage().toolCalls()); assertEquals(3, f.materials.messages().size()); assertTrue(f.materials.messages().getLast().textContent().contains("RESUMED_ACTUAL_OUTPUT"));
        assertEquals(AgentExecutionProgram.WORKFLOW_RESULT_EVENT, f.lastAdoption.events().getFirst().type());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        assertEquals(request.inputId(), f.materials.observationInput());
    }

    /** 真实HITL卡只从原结果typed字段生成，仍绑定Workflow完整wait身份，不能变成AskUser。 */
    @Test
    void preservesActualWorkflowHumanCardWithExactExecutionWaitIdentity() {
        var f = new Fixture(workflowBinding()); var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(new AgentAction.Dispatch("workflow:main"), 1));
        var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); var workflow = new SessionExecutionWorkflows(Fixture.LIMITS);
        var original = (SessionExecutionWorkflows.Dispatch) workflow.decode(dispatched.action().request().content());
        var waiting = new ExecutionStore.WorkflowWait(original.executionRef(), "human-suspend", "actual-human-gate-key", 7);
        var card = new SessionExecutionWorkflows.HumanInteraction(new TypedPayload("application/json", "{\"title\":\"确认实际参数\",\"input_schema\":{\"type\":\"object\"}}"));
        var result = new WorkflowResult.Suspended(original.executionRef(), waiting.suspendRef(), waiting.executionRevision(), new ResultRef("actual-human-suspended-result"), com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL);
        f.commit(dispatched, f.workflow(dispatched, result, Optional.of(waiting), Optional.of(card)));
        var event = f.codec.parse(f.lastAdoption.events().getFirst().data(), AgentExecutionProgram.WAITING_SCHEMA);
        assertEquals("workflow", AgentExecutionCodec.string(event, "kind")); assertTrue(event.get("question").isNull()); assertTrue(event.get("requests").isEmpty());
        assertEquals(waiting.actionKey(), AgentExecutionCodec.string(event.get("workflow"), "actionKey")); assertEquals("7", AgentExecutionCodec.string(event.get("workflow"), "executionRevision"));
        assertEquals(card.card(), AgentExecutionCodec.readPayload(event.get("workflow").get("card"))); assertTrue(f.materials.pendingHuman().isEmpty());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test void authorizedInputPreparesOneWorkflowWithoutModelDecisionAndCompletesWithoutModelLoop() {
        var f=Fixture.authorizedWorkflow();
        var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        assertEquals(ExecutionStore.Kind.WORKFLOW,first.action().kind()); assertEquals(1,first.action().ordinal());
        var protocol=new SessionExecutionWorkflows(Fixture.LIMITS);
        var request=assertInstanceOf(SessionExecutionWorkflows.Dispatch.class,protocol.decode(first.action().request().content()));
        assertEquals(new SessionExecutionWorkflows.AuthorizedInputOrigin("test-intent"),request.origin());
        assertFalse(first.action().request().content().canonicalJson().contains("callId"));
        assertFalse(first.action().request().content().canonicalJson().contains("wireName"));
        assertTrue(f.materials.decision().isEmpty()); assertEquals(0,f.materials.usage().businessModelCalls());
        var output=new TypedPayload("application/json","{\"answer\":false,\"count\":0}");
        f.commit(first,f.workflow(first,new WorkflowResult.Completed(request.executionRef(),output,4,new ResultRef("private-business-result")),Optional.empty()));
        assertEquals(f.materials,new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context));
        assertTrue(f.materials.messages().stream().noneMatch(m->m.role()==ModelRole.TOOL||!m.toolCalls().isEmpty()));
        var finish=assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials));
        assertEquals(RunStatus.COMPLETED,finish.status()); assertTrue(finish.events().getFirst().data().canonicalJson().contains("answer"));
        assertEquals(0,f.materials.usage().businessModelCalls()); assertEquals(1,f.materials.usage().toolCalls());
        // 明确调用保留既有Agent模型配置，但Workflow完整完成链不能准备任何MODEL请求。
        assertTrue(f.saved.stream().noneMatch(material->AgentExecutionCodec.MODEL_REQUEST_SCHEMA.equals(material.content().schemaId())
                || AgentExecutionCodec.MODEL_MANIFEST_SCHEMA.equals(material.content().schemaId())));
        assertEquals(0,f.measurements);assertEquals(0,f.mediaPreparations);assertEquals(0,f.timeSamples);
    }

    @Test void authorizedInputUnknownKeepsOriginalActionAndDoesNotPrepareAnotherDispatch() {
        var f=Fixture.authorizedWorkflow();var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var request=(SessionExecutionWorkflows.Dispatch)new SessionExecutionWorkflows(Fixture.LIMITS).decode(first.action().request().content());
        f.commit(first,f.workflow(first,new WorkflowResult.OutcomeUnknown(request.executionRef(),OptionalLong.empty(),
                new PortFailure("external_unknown",RetryMode.RESULT_QUERYABLE,new ResultRef("original-receipt"))),Optional.empty()));
        var restored=new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context);
        assertEquals(f.materials,restored);
        for(int observation=0;observation<3;observation++)
            assertEquals("execution_unknown",assertInstanceOf(Step.Yield.class,f.program.next(f.run,restored)).reason());
        assertEquals(1,restored.ordinal());assertEquals(1,restored.usage().toolCalls());
        assertEquals(0,restored.usage().businessModelCalls());
    }

    @Test void authorizedInputPendingReplyContinuesOriginalWorkflowActionAndOrigin() {
        var f=Fixture.authorizedWorkflow();var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var protocol=new SessionExecutionWorkflows(Fixture.LIMITS);var original=(SessionExecutionWorkflows.Dispatch)protocol.decode(first.action().request().content());
        var wait=new ExecutionStore.WorkflowWait(original.executionRef(),"test-suspend","test-engine-wait",5);
        f.commit(first,f.workflow(first,new WorkflowResult.Suspended(original.executionRef(),wait.suspendRef(),5,new ResultRef("wait-business"),
                com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_EXTERNAL),Optional.of(wait)));
        var restored=new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context);
        assertEquals(f.materials,restored);f.materials=restored;
        var pending=(AgentExecutionProgram.PendingWorkflow)restored.pendingDependency().orElseThrow();
        assertEquals(original,pending.original());assertEquals(wait,pending.dependency());
        var accepted=f.accept(pending.waiting(),ExecutionStore.WaitKind.WORKFLOW,Map.of(),new TypedPayload("application/json","{\"response\":false}"),Optional.of(wait));
        var resumed=assertInstanceOf(Step.Dispatch.class,f.program.continuation(f.run,f.materials,accepted,f.acceptedResponse));
        var request=(SessionExecutionWorkflows.Resume)protocol.decode(resumed.action().request().content());
        assertEquals(first.action().action(),resumed.action().action());assertEquals(original.origin(),request.origin());
        assertFalse(resumed.action().request().content().canonicalJson().contains("callId"));
        f.commit(resumed,f.workflow(resumed,new WorkflowResult.Completed(original.executionRef(),new TypedPayload("application/json","{\"ok\":true}"),8,new ResultRef("done-business")),Optional.empty()));
        assertEquals(f.materials,new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context));
        assertEquals(RunStatus.COMPLETED,assertInstanceOf(Step.Finish.class,f.program.next(f.run,f.materials)).status());
    }

    @Test void authorizedInputResumeUnknownRestoresOriginalReceiptWithoutRedispatchOrModelFallback() {
        var f=Fixture.authorizedWorkflow();var first=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var protocol=new SessionExecutionWorkflows(Fixture.LIMITS);
        var original=(SessionExecutionWorkflows.Dispatch)protocol.decode(first.action().request().content());
        var wait=new ExecutionStore.WorkflowWait(original.executionRef(),"resume-suspend","original-engine-wait",5);
        f.commit(first,f.workflow(first,new WorkflowResult.Suspended(original.executionRef(),wait.suspendRef(),5,new ResultRef("original-wait-receipt"),
                com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_EXTERNAL),Optional.of(wait)));
        f.materials=new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context);
        var pending=(AgentExecutionProgram.PendingWorkflow)f.materials.pendingDependency().orElseThrow();
        var accepted=f.accept(pending.waiting(),ExecutionStore.WaitKind.WORKFLOW,Map.of(),new TypedPayload("application/json","{\"response\":false}"),Optional.of(wait));
        var resumed=assertInstanceOf(Step.Dispatch.class,f.program.continuation(f.run,f.materials,accepted,f.acceptedResponse));
        assertEquals(first.action().action(),resumed.action().action());
        var unknown=new WorkflowResult.OutcomeUnknown(original.executionRef(),OptionalLong.of(6),
                new PortFailure("resume_outcome_unknown",RetryMode.RESULT_QUERYABLE,new ResultRef("original-resume-receipt")));
        f.commit(resumed,f.workflow(resumed,unknown,Optional.empty()));
        var restored=new AgentExecutionProgram(f,f.core,Fixture.LIMITS).open(f.run,f.context);
        assertEquals(f.materials,restored);
        assertTrue(restored.pendingDependency().isEmpty());
        assertEquals(2,restored.usage().toolCalls());assertEquals(0,restored.usage().businessModelCalls());
        assertTrue(restored.messages().stream().noneMatch(message->message.role()==ModelRole.TOOL||!message.toolCalls().isEmpty()));
        assertEquals("execution_unknown",assertInstanceOf(Step.Yield.class,f.program.next(f.run,restored)).reason());
        var envelope=protocol.decodeResult(f.material(f.run,restored.lastResult().orElseThrow()).content());
        var actual=f.core.portalResults(f.payloads,f.payloads,f.payloads).decodeWorkflow(f.codec.decodeOutcome(envelope.outcome()),PortalCallKind.WORKFLOW_RESUME);
        assertEquals(unknown,actual);
    }

    private static AgentExecutionProgram.WorkflowBinding workflowBinding() {
        return new AgentExecutionProgram.WorkflowBinding(new WorkflowPortal.FrozenWorkflowTarget(new WorkflowPortal.WorkflowHandle("workflow:main"), "instance", 1,
                new CapabilityRef(CapabilityType.WORKFLOW, "workflow", "v1"), Duration.ofSeconds(1), 1), new TypedPayload("application/json", "{\"user_input\":\"原受理输入\",\"extraParameters\":{}}"), List.of());
    }

    /** 受控模型重复消费原只读结果：第三轮收到事实，第五轮私有冻结并终止。 */
    @Test
    void repeatedReadWarnsThenStopsAndTerminalContextRevalidates() {
        var f = new Fixture();
        var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(call, 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"UNCHANGED\"}]}"));
        for (int lap = 1; lap < 5; lap++) {
            var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var history = requestHistory(f, next);
            boolean warning = history.stream().anyMatch(message -> message.messageId().equals("execution-tool-progress-" + next.action().ordinal()));
            assertEquals(lap >= 3, warning);
            f.commit(next, f.model(call, next.action().ordinal()));
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
        var stopped = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.TERMINATED, stopped.status()); assertEquals(1, f.materials.usage().toolCalls());
        var terminal = stopped.terminalContext().orElseThrow(); f.saved.add(terminal);
        var previous = f.run; var progress = previous.progress();
        var run = new ExecutionStore.Run(previous.identity(), previous.configuration(), RunStatus.TERMINATED, previous.revision() + 1, previous.fence(), false,
                ExecutionStore.Phase.FINISHED, new ExecutionStore.Progress(progress.decisions(), progress.toolCalls(), progress.knowledgeCalls(), terminal.reference()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), previous.actorVerificationRef(), Optional.empty(), previous.createdAt(), previous.updatedAt());
        assertEquals(terminal.reference(), new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(run, terminal).context());
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) f.codec.parse(terminal.content(), AgentExecutionProgram.CONTEXT_SCHEMA);
        var progressPayload = AgentExecutionCodec.readPayload(root.get("progress"));
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) f.codec.parse(progressPayload, AgentToolProgress.SCHEMA); changed.put("repetitions", "6");
        root.set("progress", AgentExecutionCodec.payload(f.codec.encode(AgentToolProgress.SCHEMA, changed)));
        var invalidPayload = f.codec.encode(AgentExecutionProgram.CONTEXT_SCHEMA, root);
        var invalid = new Material(AgentExecutionCodec.reference(invalidPayload), invalidPayload); f.saved.add(invalid);
        var tamperedRun = new ExecutionStore.Run(run.identity(), run.configuration(), run.status(), run.revision(), run.fence(), false, run.phase(),
                new ExecutionStore.Progress(progress.decisions(), progress.toolCalls(), progress.knowledgeCalls(), invalid.reference()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), run.actorVerificationRef(), Optional.empty(), run.createdAt(), run.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(tamperedRun, invalid));
    }

    private static void configureProgressSchema(Fixture f, String schema, ToolEffect effect) {
        var c = f.configuration; var old = f.entry.target();
        var input = new TypedPayload("json-schema", schema);
        var target = new ToolPortal.FrozenToolTarget(old.handle(), old.capability(), old.deploymentId(), old.deploymentRevision(), input, old.outputSchema(),
                effect, true, old.timeout(), old.modelInvocable(), old.confirmation(), old.sensitiveFields(), old.providerRequirements(), old.outputSchemaDeclared());
        var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), effect == ToolEffect.READ_ONLY);
        var tools = c.modelTools().stream().map(tool -> tool.toolHandle().equals(target.handle().value())
                ? new ModelTool(tool.wireName(), tool.toolHandle(), tool.description(), input, tool.strict(), tool.kind(), tool.batchEligible()) : tool).toList();
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), tools, List.of(entry), c.budget(), c.capabilitySnapshot(),
                c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired());
    }

    @Test void explicitReadPollingIsExemptAndMalformedPoliciesFailBeforeDispatch() {
        for (var invalid : List.of("{\"x-skillforge-polling\":\"true\"}", "{\"x-skillforge-polling\":true}")) {
            var f = new Fixture(); configureProgressSchema(f, invalid, invalid.contains("\"true\"") ? ToolEffect.READ_ONLY : ToolEffect.IDEMPOTENT_WRITE);
            assertThrows(IllegalArgumentException.class, () -> f.program.open(f.run, f.context));
            assertEquals(0, f.materials.usage().businessModelCalls()); assertEquals(0, f.materials.usage().toolCalls());
        }
        var f = new Fixture(); configureProgressSchema(f, "{\"x-skillforge-polling\":true}", ToolEffect.READ_ONLY);
        f.materials = f.program.open(f.run, f.context);
        var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(call, 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"UNCHANGED\"}]}"));
        for (int i = 0; i < 6; i++) {
            var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            assertTrue(requestHistory(f, next).stream().noneMatch(message -> message.messageId().equals("execution-tool-progress-" + next.action().ordinal())));
            f.commit(next, f.model(call, next.action().ordinal()));
        }
        assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test void successfulWriteOnlyWarnsAndDeterminateFailuresStop() {
        for (boolean failed : List.of(false, true)) {
            var f = new Fixture();
            configureProgressSchema(f, "{}", failed ? ToolEffect.READ_ONLY : ToolEffect.IDEMPOTENT_WRITE);
            f.materials = f.program.open(f.run, f.context);
            var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
            var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(call, 1));
            for (int lap = 1; lap <= (failed ? 5 : 1); lap++) {
                var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                if (failed) {
                    var request = f.codec.decodeTool(tool.action().request().content());
                    f.failureEvidence.put("failure-" + lap, new TypedPayload("fixture.failure.v1", "{\"message\":\"same\"}"));
                    var outcome = new ToolOutcome.Failed(new PortFailure("mcp_tool_unavailable", RetryMode.NONE, new ResultRef("failure-" + lap)));
                    var raw = f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeTool(PortalCallKind.TOOL_INVOKE, outcome);
                    f.commit(tool, new CapabilityResult(ExecutionStore.ResultKind.FAILED,
                            new Material(new ResultRef("result-" + tool.action().ordinal()), f.codec.encodeOutcome(raw)),
                            f.codec.safeToolFeedback(request, outcome).map(value -> new Material(new ResultRef("feedback-" + tool.action().ordinal()), value)),
                            Optional.empty(), ExecutionBudgetPort.Usage.unreported()));
                    if (lap == 5) break;
                    var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(next, f.model(call, next.action().ordinal()));
                } else f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"WRITTEN\"}]}"));
            }
            if (failed) {
                assertEquals(RunStatus.TERMINATED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
                assertEquals(5, f.materials.usage().toolCalls());
            } else {
                for (int i = 0; i < 6; i++) {
                    var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(next, f.model(call, next.action().ordinal()));
                }
                var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                assertTrue(requestHistory(f, next).stream().anyMatch(message -> message.messageId().equals("execution-tool-progress-" + next.action().ordinal())));
                assertEquals(1, f.materials.usage().toolCalls());
            }
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
    }

    @Test void frozenFailureComparisonFactGovernsIndependentAttemptsAndColdRecovery() {
        for (boolean changed : List.of(false, true)) {
            var f = new Fixture();
            var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
            var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(call, 1));
            for (int lap = 1; lap <= 5; lap++) {
                var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                var request = f.codec.decodeTool(tool.action().request().content());
                var fact = new TypedPayload("fixture.mcp.error.v1", changed && lap == 5 ? "{\"detail\":\"different\"}" : "{\"detail\":\"same\"}");
                var outcome = new ToolOutcome.Failed(new PortFailure("mcp_tool_rejected", RetryMode.NONE, new ResultRef("failure-" + lap)), Optional.of(fact));
                var raw = f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeTool(PortalCallKind.TOOL_INVOKE, outcome);
                f.commit(tool, new CapabilityResult(ExecutionStore.ResultKind.FAILED,
                        new Material(new ResultRef("result-" + tool.action().ordinal()), f.codec.encodeOutcome(raw)),
                        f.codec.safeToolFeedback(request, outcome).map(value -> new Material(new ResultRef("feedback-" + tool.action().ordinal()), value)),
                        Optional.empty(), ExecutionBudgetPort.Usage.unreported()));
                assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
                var next = f.program.next(f.run, f.materials);
                if (lap == 5) {
                    if (changed) assertInstanceOf(Step.Dispatch.class, next); else assertInstanceOf(Step.Finish.class, next);
                } else {
                    var model = assertInstanceOf(Step.Dispatch.class, next); f.commit(model, f.model(call, model.action().ordinal()));
                }
            }
            assertEquals(5, f.materials.usage().toolCalls());
        }
    }

    @Test void changedOrUnavailableFailureDetailsCannotTriggerAFalseStop() {
        for (boolean missing : List.of(false, true)) {
            var f = new Fixture(); f.currentRead = true;
            var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
            var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(call, 1));
            for (int lap = 1; lap <= 5; lap++) {
                var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                var request = f.codec.decodeTool(tool.action().request().content());
                var failureRef = new ResultRef("failure-" + lap);
                if (!missing) f.failureEvidence.put(failureRef.value(), new TypedPayload("fixture.failure.v1",
                        lap == 5 ? "{\"message\":\"AccessDeniedException\"}" : "{\"message\":\"NoSuchFileException\"}"));
                var outcome = new ToolOutcome.Failed(new PortFailure("FILE_IO_ERROR", RetryMode.NONE, failureRef));
                var raw = f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeTool(PortalCallKind.TOOL_INVOKE, outcome);
                f.commit(tool, new CapabilityResult(ExecutionStore.ResultKind.FAILED,
                        new Material(new ResultRef("result-" + tool.action().ordinal()), f.codec.encodeOutcome(raw)),
                        f.codec.safeToolFeedback(request, outcome).map(value -> new Material(new ResultRef("feedback-" + tool.action().ordinal()), value)),
                        Optional.empty(), ExecutionBudgetPort.Usage.unreported()));
                var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
                if (lap != 5) f.commit(next, f.model(call, next.action().ordinal()));
            }
            assertEquals(5, f.materials.usage().toolCalls());
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
    }

    @Test void changedActualCurrentReadBreaksPreviousEvidence() {
        var f = new Fixture(); f.currentRead = true;
        var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(call, 1));
        for (int lap = 1; lap <= 5; lap++) {
            var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"" + (lap == 5 ? "CHANGED" : "UNCHANGED") + "\"}]}"));
            var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            boolean warning = requestHistory(f, next).stream().anyMatch(message -> message.messageId().equals("execution-tool-progress-" + next.action().ordinal()));
            assertEquals(lap == 3 || lap == 4, warning);
            if (lap != 5) f.commit(next, f.model(call, next.action().ordinal()));
        }
        assertEquals(5, f.materials.usage().toolCalls());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void repeatedReadBatchRetainsAllReuseSlotsAndStopsOnFifthCycle() {
        var f = new Fixture();
        var calls = List.of(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}")),
                new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}")));
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(model, f.batchModel(calls, 1));
        var batch = assertInstanceOf(Step.Batch.class, f.program.next(f.run, f.materials));
        f.commitBatch(batch.calls().stream().map(call -> new BatchCall(call.action(), call.attemptId(),
                f.tool(call, "{\"content\":[{\"type\":\"text\",\"text\":\"UNCHANGED\"}]}"))).toList());
        for (int lap = 1; lap < 5; lap++) {
            var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var warning = requestHistory(f, next).stream().anyMatch(message -> message.messageId().equals("execution-tool-progress-" + next.action().ordinal()));
            assertEquals(lap >= 3, warning);
            f.commit(next, f.batchModel(calls, next.action().ordinal()));
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
        var stop = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.TERMINATED, stop.status()); assertEquals(2, f.materials.usage().toolCalls());
        var delta = f.codec.parse(stop.terminalContext().orElseThrow().content(), AgentExecutionProgram.CONTEXT_SCHEMA);
        var progress = AgentExecutionCodec.readPayload(delta.get("progress"));
        var root = f.codec.parse(progress, AgentToolProgress.SCHEMA);
        assertEquals("2", AgentExecutionCodec.string(root, "period")); assertEquals(10, root.get("sources").size());
    }

    @Test
    void userCorrectionBreaksPreviousRepeatEvidence() {
        var f = new Fixture(); var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(call, 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"UNCHANGED\"}]}"));
        for (int i = 0; i < 3; i++) { var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(next, f.model(call, next.action().ordinal())); }
        f.commitSupplement("correction", "请按新要求处理", 1);
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertTrue(requestHistory(f, next).stream().noneMatch(message -> message.messageId().equals("execution-tool-progress-" + next.action().ordinal())));
        f.commit(next, f.model(call, next.action().ordinal()));
        assertEquals(0, f.materials.progress().size(), "纠正前的旧模型决定不进入新窗口");
        for (int i = 1; i <= 4; i++) {
            var subsequent = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(subsequent, f.model(call, subsequent.action().ordinal()));
            assertEquals(i, f.materials.progress().size());
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
        assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
    }

    /** 同一确定工具结果的重复请求直接进入下一模型，复用真实安全观察且不新派发工具。 */
    @Test
    void reusesSavedExactToolFeedbackWithoutRepeatingEffectAndRebuildsThePairing() {
        var f = new Fixture();
        var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(call, 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"原已保存安全结果\"}]}"));
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(second, f.model(call, 3));
        var reuse = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, reuse.action().kind());
        assertEquals(4, reuse.action().ordinal());
        assertEquals(1, f.materials.usage().toolCalls());
        assertEquals(1, f.materials.completedTools().size());
        var messages = requestHistory(f,reuse);
        assertTrue(messages.getLast().textContent().contains("原已保存安全结果"));
        assertTrue(messages.getLast().textContent().contains("未重新执行工具"));
        assertEquals("provider-call:3", messages.getLast().toolCallId().orElseThrow());
        assertEquals("provider-call:1", messages.get(2).toolCallId().orElseThrow());
        f.commit(reuse, f.model(new AgentAction.Respond("根据原真实结果完成", List.of()), 4));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void currentReadUsesNewModelOperationAndRestoresFrozenQualification() {
        var f = new Fixture();
        f.currentRead = true;
        var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(call, 1));
        var firstTool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(firstTool, f.tool(firstTool, "{\"content\":[{\"type\":\"text\",\"text\":\"before\"}]}"));
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(second, f.model(call, 3));
        f.currentRead = false;
        f.failCurrentRead = true;
        var restored = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
        var fresh = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, restored));
        assertEquals(ExecutionStore.Kind.TOOL, fresh.action().kind());
        assertNotEquals(firstTool.action().action(), fresh.action().action());
        f.materials = restored;
        f.commit(fresh, f.tool(fresh, "{\"content\":[{\"type\":\"text\",\"text\":\"after\"}]}"));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertTrue(requestHistory(f, next).getLast().textContent().contains("after"));
        assertEquals(2, f.materials.usage().toolCalls());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void oldModelDeltaRestoresWithoutReclassifyingItsDecision() {
        var f = new Fixture();
        f.currentRead = true;
        var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(call, 1));
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) f.codec.parse(f.context.content(), AgentExecutionProgram.CONTEXT_SCHEMA);
        value.put("type", "delta"); value.remove("currentReads");
        var old = f.codec.encode(AgentExecutionProgram.CONTEXT_SCHEMA, value);
        f.context = new Material(AgentExecutionCodec.reference(old), old); f.saved.add(f.context);
        var progress = f.run.progress();
        f.run = Fixture.run(new ExecutionStore.Progress(progress.decisions(), progress.toolCalls(), progress.knowledgeCalls(), f.context.reference()), f.run.revision());
        f.failCurrentRead = true;
        f.materials = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
        assertEquals(List.of(false), f.materials.currentReads());
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.TOOL, tool.action().kind());
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"legacy\"}]}"));
        f.failCurrentRead = false;
        var nextModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(nextModel, f.model(call, 3));
        var reused = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, reused.action().kind());
        assertTrue(requestHistory(f, reused).getLast().textContent().contains("legacy"));
        f.failCurrentRead = true;
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void knownFailedReadThenFreshModelCallDispatchesASecondToolAndPersistsItsSuccess() {
        var f = new Fixture();
        var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
        var physicalCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<Step.Dispatch, CapabilityResult> provider = dispatch -> {
            if (physicalCalls.incrementAndGet() == 1) {
                var request = f.codec.decodeTool(dispatch.action().request().content());
                var failure = new ToolOutcome.Failed(new PortFailure("mcp_tool_unavailable", RetryMode.NONE, new ResultRef("known-read-failure")));
                var raw = f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeTool(PortalCallKind.TOOL_INVOKE, failure);
                return new CapabilityResult(ExecutionStore.ResultKind.FAILED,
                        new Material(new ResultRef("result-" + dispatch.action().ordinal()), f.codec.encodeOutcome(raw)),
                        f.codec.safeToolFeedback(request, failure).map(value -> new Material(new ResultRef("feedback-" + dispatch.action().ordinal()), value)),
                        Optional.empty(), ExecutionBudgetPort.Usage.unreported());
            }
            return f.tool(dispatch, "{\"content\":[{\"type\":\"text\",\"text\":\"第二次真实读取成功\"}]}");
        };
        var firstModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(firstModel, f.model(call, 1));
        var failedRead = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.TOOL, failedRead.action().kind());
        f.commit(failedRead, provider.apply(failedRead));
        assertEquals(1, physicalCalls.get());
        var nextModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, nextModel.action().kind());
        assertTrue(requestHistory(f, nextModel).getLast().textContent().contains("mcp_tool_unavailable"));
        // 新模型决策保留同一目标和参数，但使用不同原生callId，构造一个新的工具动作。
        f.commit(nextModel, f.model(call, 3));
        var freshRead = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.TOOL, freshRead.action().kind());
        assertFalse(failedRead.action().action().equals(freshRead.action().action()));
        assertFalse(failedRead.attemptId().equals(freshRead.attemptId()));
        var original = f.codec.decodeTool(failedRead.action().request().content());
        var fresh = f.codec.decodeTool(freshRead.action().request().content());
        assertEquals(original.target(), fresh.target()); assertEquals(original.arguments(), fresh.arguments());
        assertFalse(original.callId().equals(fresh.callId()));
        f.commit(freshRead, provider.apply(freshRead));
        assertEquals(2, physicalCalls.get()); assertEquals(2, f.materials.usage().toolCalls());
        var finalModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, finalModel.action().kind());
        assertTrue(requestHistory(f, finalModel).getLast().textContent().contains("第二次真实读取成功"));
        f.commit(finalModel, f.model(new AgentAction.Respond("根据第二次读取结果完成", List.of()), 5));
        assertEquals(RunStatus.COMPLETED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        assertEquals(2, physicalCalls.get());
    }

    /** 句柄政策下不同参数的永久拒绝收口，恢复同一决定也不再产生Yield或外调。 */
    @Test void finishesDeterministicallyRejectedToolDecisionInsteadOfYieldingForever() {
        var f = new Fixture(); var c = f.configuration; var original = f.entry.target();
        var target = new ToolPortal.FrozenToolTarget(original.handle(), original.capability(), original.deploymentId(), original.deploymentRevision(),
                original.inputSchema(), original.outputSchema(), original.effect(), original.idempotent(), original.timeout(), original.modelInvocable(),
                original.confirmation(), original.sensitiveFields(), original.providerRequirements(), original.outputSchemaDeclared(), ToolPortal.ToolCallIdentityPolicy.HANDLE);
        var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), f.entry.parallelSafe());
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), c.modelTools(), List.of(entry), c.budget(),
                c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(),
                c.workflows(), c.initialPlanState(), c.planRequired());
        f.materials = f.program.open(f.run, f.context);
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"原结果\"}]}"));
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(second, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":2}")), 3));
        var before = f.materials; var old = before.completedTools().getFirst();
        for (var resolution : List.of(com.skillforge.runtime.core.AgentExecutionLoop.ToolResolution.UNKNOWN,
                com.skillforge.runtime.core.AgentExecutionLoop.ToolResolution.INPUT_REQUIRED)) {
            var unresolved = new com.skillforge.runtime.core.AgentExecutionLoop.ToolObservation(old.call(), old.tool(), resolution,
                    Optional.empty(), List.of(), old.source());
            var pending = new AgentExecutionProgram.Materials(before.configuration(), before.messages(), before.usage(), before.ordinal(), before.modelOrdinal(),
                    before.model(), before.decision(), before.context(), List.of(unresolved), before.problem(), before.lastResult(), before.pendingTool(),
                    before.pendingHuman(), before.knowledge(), before.skills(), before.pendingDependency(), before.steering(), before.planState(), before.output(), before.observationInput());
            assertEquals("execution_tool_result_unresolved", assertInstanceOf(Step.Yield.class, f.program.next(f.run, pending)).reason());
        }
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.FAILED, finish.status());
        assertEquals("execution_repeated_write_arguments", AgentExecutionCodec.string(
                f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
        assertEquals(finish, f.program.next(f.run, f.program.open(f.run, f.context)));
        assertEquals(1, f.materials.usage().toolCalls());
    }

    @Test void persistsNativeProtocolStateThroughToolTurnRecoveryAndNextFrozenRequest() {
        var f = new Fixture();
        var state = new ModelPort.NativeProtocolState(ModelPort.NativeProtocol.RESPONSES, "responses-v1", "model", List.of(
                new TypedPayload("responses.output-item.v1", "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"PRIVATE-REPLAY-CIPHERTEXT\"}")));
        var initial = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var turn = new AssistantTurn(List.of(new AssistantContent.ToolCall("native-call", "sf_template", "{}")));
        f.commit(initial, f.nativeModel(turn, "tool_calls", 1, Optional.empty(), Optional.of(state)));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"已核验\"}]}"));
        var restored = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, restored));
        var history = requestHistory(f, next);
        var assistant = history.stream().filter(message -> message.role() == ModelRole.ASSISTANT).findFirst().orElseThrow();
        assertEquals(Optional.of(state), assistant.protocolState()); assertTrue(assistant.reasoningContent().isEmpty());
        assertTrue(assistant.accountingText().contains("PRIVATE-REPLAY-CIPHERTEXT"));
        assertFalse(assistant.toString().contains("PRIVATE-REPLAY-CIPHERTEXT"));
        assertFalse(assistant.textContent().contains("PRIVATE-REPLAY-CIPHERTEXT"));
        var payload = f.codec.encodeInput(history, new TypedPayload("input.v1", "{}"));
        assertEquals(history, f.codec.decodeInput(payload));
        assertEquals(1, restored.usage().toolCalls()); assertEquals(1, restored.usage().businessModelCalls());
    }

    /** 原结果未脱敏不能作为下一轮安全反馈，纯采纳失败不修改已加载材料。 */
    @Test
    void rejectsUnsafeFeedbackRatherThanInventingSavedSafeResult() {
        var f = new Fixture();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var actual = f.tool(tool, "{\"content\":[],\"structuredContent\":{\"answer\":\"safe\",\"secret\":\"private\"}}");
        var unsafe = new CapabilityResult(actual.kind(), actual.result(), Optional.of(new Material(new ResultRef("unsafe-feedback"),
                new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[],\"structuredContent\":{\"answer\":\"safe\",\"secret\":\"private\"}}"))), actual.externalOperation(), actual.usage());
        var before = f.materials;
        assertThrows(IllegalArgumentException.class, () -> f.program.adopt(f.run, before, tool.action(), unsafe));
        var missing = new CapabilityResult(actual.kind(), actual.result(), Optional.empty(), actual.externalOperation(), actual.usage());
        assertThrows(IllegalArgumentException.class, () -> f.program.adopt(f.run, before, tool.action(), missing));
        assertEquals(before, f.materials);
        assertEquals(1, f.materials.ordinal());
    }

    /** 合法工具envelope但业务输出拒绝时保留原成功结果，收口失败且恢复不重调Handler或模型。 */
    @Test
    void preservesActualToolSuccessAndStopsWhenFrozenOutputSchemaRejectsFeedback() {
        var f = new Fixture(new TypedPayload("json-schema", "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}"));
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeTool(tool.action().request().content());
        var actual = new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                "{\"content\":[{\"type\":\"text\",\"text\":\"actual effect result\"}],\"structuredContent\":{\"different\":\"value\"}}"),
                new ResultRef("tool-business-result"), List.of());
        var raw = f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeTool(PortalCallKind.TOOL_INVOKE, actual);
        var original = new Material(new ResultRef("schema-rejected-actual-result"), f.codec.encodeOutcome(raw));
        assertThrows(AgentExecutionCodec.ToolOutputRejected.class, () -> f.codec.safeToolFeedback(request, actual));
        var quotaFailure = assertThrows(IllegalArgumentException.class,
                () -> new AgentExecutionCodec(new CodecLimits(10, 10, 10)).safeToolFeedback(request, actual));
        assertEquals(IllegalArgumentException.class, quotaFailure.getClass());
        var beforeMessages = f.materials.messages();
        int measurements = f.measurements;
        f.commit(tool, new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED, original, Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported()));
        var rejectedProcess = f.codec.parse(f.lastAdoption.events().getFirst().data(), AgentExecutionProgram.TOOL_RESULT_SCHEMA);
        assertEquals("SUCCEEDED", AgentExecutionCodec.string(rejectedProcess, "status"));
        assertFalse(rejectedProcess.get("outputAccepted").booleanValue());
        assertTrue(f.saved.contains(original));
        assertEquals(original.reference(), f.materials.lastResult().orElseThrow());
        assertEquals(beforeMessages, f.materials.messages());
        assertEquals(1, f.materials.usage().toolCalls());
        assertTrue(f.materials.completedTools().isEmpty());
        var delta = f.codec.parse(f.context.content(), AgentExecutionProgram.CONTEXT_SCHEMA);
        assertEquals(original.reference().value(), AgentExecutionCodec.string(delta, "result"));
        assertTrue(delta.get("feedback").isNull());
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.FAILED, finish.status());
        assertEquals("tool_output_invalid", AgentExecutionCodec.string(f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
        var rebuilt = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        var restored = rebuilt.open(f.run, f.context);
        assertEquals(f.materials, restored);
        var restoredFinish = assertInstanceOf(Step.Finish.class, rebuilt.next(f.run, restored));
        assertEquals(RunStatus.FAILED, restoredFinish.status());
        assertEquals("tool_output_invalid", AgentExecutionCodec.string(f.codec.parse(restoredFinish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA), "reason"));
        assertEquals(measurements, f.measurements);
    }

    /** 两轮真实输入等待重建同一Action，以新Attempt续跑，最终只追加一次原assistant/tool配对。 */
    @Test
    void resumesSavedToolWaitWithOriginalStateAndPairsOnlyTheFinalResult() {
        var f = new Fixture();
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(tool, f.inputRequired("one", "opaque-private-state-one"));
        assertEquals(ExecutionStore.Phase.WAITING, f.run.phase());
        assertTrue(f.run.lease().isEmpty()); assertEquals(1, f.materials.messages().size());
        assertEquals(1, f.materials.usage().businessModelCalls()); assertEquals(1, f.materials.usage().toolCalls());
        assertEquals(AgentExecutionProgram.WAITING_EVENT, f.lastAdoption.events().getFirst().type());
        assertFalse(f.lastAdoption.events().getFirst().data().canonicalJson().contains("opaque-private-state-one"));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        assertThrows(IllegalStateException.class, () -> f.program.next(f.run, f.materials));
        int measurements = f.measurements;
        var responseOne = Map.of("browser-connection", new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload("browser-response.v1", "{\"device\":\"selected-one\"}")));
        var acceptedOne = f.accept(responseOne);
        var wrongMethod = new Material(f.acceptedResponse.reference(), f.codec.encodeToolResponses(Map.of("browser-connection",
                new ToolInputResponse(ToolInputMethod.ROOTS_LIST, new TypedPayload("browser-response.v1", "{}")))));
        assertThrows(IllegalArgumentException.class, () -> f.program.continuation(f.run, f.materials, acceptedOne, wrongMethod));
        var continuationOne = assertInstanceOf(Step.Dispatch.class, f.program.continuation(f.run, f.materials, acceptedOne, f.acceptedResponse));
        assertEquals(tool.action().action(), continuationOne.action().action());
        assertEquals(tool.action().ordinal(), continuationOne.action().ordinal());
        assertEquals(tool.action().action().actionId() + ":attempt:2", continuationOne.attemptId());
        var roundOne = f.codec.decodeTool(continuationOne.action().request().content());
        assertEquals("opaque-private-state-one", roundOne.continuation().orElseThrow().requestState().orElseThrow());
        assertEquals(responseOne, roundOne.continuation().orElseThrow().responses());
        var originalContinuation = roundOne.continuation().orElseThrow();
        assertEquals(acceptedOne.acceptedResponse().orElseThrow().input(), originalContinuation.inputId());
        assertEquals(acceptedOne.acceptedResponse().orElseThrow().acceptedRevision(), originalContinuation.acceptedRevision());
        assertResumedObservation(continuationOne, ExecutionObservation.Kind.TOOL, originalContinuation.inputId());
        var changedState = new AgentExecutionCodec.ToolRequest(roundOne.target(), roundOne.binding(), roundOne.arguments(), roundOne.callId(), roundOne.wireName(),
                Optional.of(new AgentExecutionCodec.ToolContinuation(originalContinuation.waiting(), originalContinuation.sourceResult(), originalContinuation.response(),
                        originalContinuation.responses(), Optional.of("substituted-state"), originalContinuation.inputId(), originalContinuation.acceptedRevision())));
        var changedRequest = new Material(new ResultRef("changed-state-request"), f.codec.encodeTool(changedState));
        var changedPrepared = new ExecutionStore.Prepared(continuationOne.action().action(), continuationOne.action().ordinal(), continuationOne.action().kind(),
                continuationOne.action().target(), continuationOne.action().capability(), changedRequest, "changed-state-fingerprint");
        assertThrows(IllegalArgumentException.class, () -> f.program.adopt(f.run, f.materials, changedPrepared, f.inputRequired("rejected", "new-state")));
        f.commit(continuationOne, f.inputRequired("two", "opaque-private-state-two"));
        assertEquals(originalContinuation.inputId(), f.materials.observationInput());
        assertEquals(2, f.materials.pendingTool().orElseThrow().waiting().generation());
        assertEquals(2, f.materials.usage().toolCalls()); assertEquals(1, f.materials.messages().size());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var acceptedTwo = f.accept(responseOne);
        var continuationTwo = assertInstanceOf(Step.Dispatch.class, f.program.continuation(f.run, f.materials, acceptedTwo, f.acceptedResponse));
        assertEquals(tool.action().action().actionId() + ":attempt:3", continuationTwo.attemptId());
        assertEquals("opaque-private-state-two", f.codec.decodeTool(continuationTwo.action().request().content()).continuation().orElseThrow().requestState().orElseThrow());
        f.commit(continuationTwo, f.tool(continuationTwo, "{\"content\":[{\"type\":\"text\",\"text\":\"final actual result\"}]}"));
        assertTrue(f.materials.pendingTool().isEmpty()); assertEquals(3, f.materials.usage().toolCalls());
        assertEquals(2, f.materials.ordinal()); assertEquals(3, f.materials.messages().size());
        assertEquals(1, f.materials.messages().stream().filter(message -> message.role() == ModelRole.ASSISTANT).count());
        assertEquals("provider-call:1", f.materials.messages().getLast().toolCallId().orElseThrow());
        assertEquals(measurements, f.measurements);
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var afterContinuation = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.MODEL, afterContinuation.action().kind());
        assertResumedObservation(afterContinuation, ExecutionObservation.Kind.LLM, acceptedTwo.acceptedResponse().orElseThrow().input());
    }

    /** 未知模型调用的缺报保持不完整，不回填零，不生成回答或新动作。 */
    @Test
    void keepsUnknownModelOutcomeAndUnreportedUsageUnresolved() {
        var f = new Fixture();
        var dispatch = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var actual = new ModelResult.OutcomeUnknown(new PortFailure("model_timeout", RetryMode.NONE, new ResultRef("unknown-original")));
        var result = new Material(new ResultRef("unknown-result"), f.codec.encodeOutcome(f.core.encodeOutcome(PortalCallKind.MODEL_GENERATE, actual)));
        f.commit(dispatch, new CapabilityResult(ExecutionStore.ResultKind.UNKNOWN, result, Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported()));
        assertFalse(f.materials.usage().inputTokens().complete());
        assertFalse(f.materials.usage().outputTokens().complete());
        assertEquals("execution_unknown", assertInstanceOf(Step.Yield.class, f.program.next(f.run, f.materials)).reason());
    }

    /** 入口图文与工具图片均保留引用，下一轮实际请求能完整携带图片。 */
    @Test
    void preservesImageReferencesThroughToolFeedbackAndInputCodec() {
        var f = new Fixture();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}")), 1));
        var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        String image = "{\"content\":[{\"type\":\"image\",\"detail\":\"AUTO\",\"media\":{\"owner\":{\"tenantId\":\"t\",\"sessionId\":\"s\"},\"id\":\"screenshot\",\"sha256\":\"" + "a".repeat(64)
                + "\",\"mimeType\":\"image/png\",\"byteLength\":\"100\",\"width\":\"10\",\"height\":\"10\"}}]}";
        f.commit(tool, f.tool(tool, image));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var messages = requestHistory(f,next);
        var part = assertInstanceOf(ModelContent.Image.class, messages.getLast().contents().getFirst());
        assertEquals("screenshot", part.media().id());
        var encodedInput = f.codec.encodeInput(messages, f.frozenInput);
        assertEquals(messages, f.codec.decodeInput(encodedInput));
        assertEquals(f.frozenInput, f.codec.frozenInput(encodedInput));
    }

    /** AskUser随原成功模型结果持久等待，真实回复只本地采纳一次，不重调模型或工具。 */
    @Test
    void waitsForHumanResponseWithOriginalModelSuccessAndRestoresTheAcceptedConversation() {
        var f = new Fixture();
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var actual = f.model(new AgentAction.AskUser("需要补充哪个参数？"), 1);
        assertEquals(ExecutionStore.ResultKind.SUCCEEDED, actual.kind());
        f.commit(first, actual);
        assertEquals(ExecutionStore.WaitKind.HUMAN, f.lastAdoption.waiting().orElseThrow().kind());
        assertEquals(first.action().action(), f.materials.pendingHuman().orElseThrow().waiting().action());
        assertEquals(ExecutionStore.Phase.WAITING, f.run.phase()); assertEquals(RunStatus.SUSPENDED, f.run.status());
        assertEquals(1, f.materials.usage().businessModelCalls()); assertEquals(0, f.materials.usage().toolCalls());
        assertEquals(1, f.materials.messages().size()); assertTrue(f.materials.pendingTool().isEmpty());
        var event = f.codec.parse(f.lastAdoption.events().getFirst().data(), AgentExecutionProgram.WAITING_SCHEMA);
        assertEquals("human", AgentExecutionCodec.string(event, "kind"));
        assertEquals("需要补充哪个参数？", AgentExecutionCodec.string(event, "question")); assertTrue(event.get("requests").isEmpty());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        assertThrows(IllegalStateException.class, () -> f.program.next(f.run, f.materials));
        int measurements = f.measurements;
        var accepted = f.acceptHuman("请使用真实的机构编号 A-17");
        var firstAdoption = f.program.acceptResponse(f.run, f.materials, accepted, f.acceptedResponse);
        assertEquals(firstAdoption, f.program.acceptResponse(f.run, f.materials, accepted, f.acceptedResponse));
        assertEquals(1, f.materials.messages().size());
        f.commitHuman(accepted);
        assertEquals(accepted.acceptedResponse().orElseThrow().input(), f.materials.observationInput());
        assertTrue(f.materials.pendingHuman().isEmpty()); assertTrue(f.materials.model().isEmpty()); assertTrue(f.materials.decision().isEmpty());
        assertEquals(1, f.materials.ordinal()); assertEquals(1, f.materials.usage().businessModelCalls()); assertEquals(0, f.materials.usage().toolCalls());
        assertEquals(4, f.materials.messages().size());
        assertEquals("原模型说明", f.materials.messages().get(1).textContent());
        assertEquals("需要补充哪个参数？",AgentExecutionCodec.string(SessionRuntimePayloadJson.parse(f.materials.messages().get(1).toolCalls().getFirst().arguments().canonicalJson(),Fixture.LIMITS),"question"));
        assertEquals("ask_user",f.materials.messages().get(1).toolCalls().getFirst().wireName());
        assertEquals(Optional.of("provider-call:1"),f.materials.messages().get(2).toolCallId());
        assertTrue(f.materials.messages().get(2).textContent().contains("用户已回答"));
        assertEquals(Optional.of("原推理"), f.materials.messages().get(1).reasoningContent());
        assertEquals(ModelRole.USER, f.materials.messages().getLast().role());
        assertEquals("请使用真实的机构编号 A-17", f.materials.messages().getLast().textContent());
        assertEquals(measurements, f.measurements);
        assertThrows(IllegalArgumentException.class, () -> f.program.acceptResponse(f.run, f.materials, accepted, f.acceptedResponse));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(2, next.action().ordinal()); assertEquals(ExecutionStore.Kind.MODEL, next.action().kind());
        assertResumedObservation(next, ExecutionObservation.Kind.LLM, accepted.acceptedResponse().orElseThrow().input());
        assertEquals(f.materials.messages(), requestHistory(f,next));
        f.commit(next, f.model(new AgentAction.Respond("按真实补充信息完成", List.of()), 2));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        assertEquals(RunStatus.COMPLETED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
    }

    /** 新human回复严格使用独立schema；公开受理/关闭事件使用各自真实输入身份。 */
    @Test
    void keepsHumanResponseAndPublicAcceptedIdentityExact() {
        var f = new Fixture(); var encoded = f.codec.encodeHumanResponse(" 真实回答 ");
        assertEquals(" 真实回答 ", f.codec.decodeHumanResponse(encoded));
        assertThrows(IllegalArgumentException.class, () -> f.codec.decodeHumanResponse(new TypedPayload("application/json", encoded.canonicalJson())));
        assertThrows(IllegalArgumentException.class, () -> f.codec.decodeHumanResponse(new TypedPayload(AgentExecutionCodec.HUMAN_RESPONSE_SCHEMA, "{\"response\":\"answer\",\"other\":true}")));
        var admitted = AgentExecutionProgram.admissionEvent(f.run.identity());
        assertEquals(AgentExecutionProgram.ACCEPTED_EVENT, admitted.type());
        assertEquals(f.run.identity().causationInputId().value(), AgentExecutionCodec.string(f.codec.parse(admitted.data(), AgentExecutionProgram.ACCEPTED_SCHEMA), "inputId"));
        var reply = new InputId("new-human-reply");
        var resolved = AgentExecutionProgram.resolved(f.run, reply, new ExecutionStore.WaitRef(new com.skillforge.runtime.api.port.ActionRef(SessionFixtures.TENANT, "question-action"), 1));
        assertEquals(AgentExecutionProgram.RESOLVED_EVENT, resolved.type());
        var result = f.codec.parse(resolved.data(), AgentExecutionProgram.RESOLVED_SCHEMA);
        assertEquals(reply.value(), AgentExecutionCodec.string(result, "inputId"));
        assertEquals("response_accepted", AgentExecutionCodec.string(result, "reason"));
    }

    /** 完整原bundle保存，实际选中证据经真实计量进入原生配对，引用只绑定已注入证据。 */
    @Test
    void injectsActualKnowledgeEvidenceAndPreservesTheCompleteRawBundleOnRecovery() {
        var f = new Fixture(new KnowledgeBudget(1, 3, 1_000, 50, 1_000));
        var query = new AgentAction.KnowledgeQuery("knowledge:docs", "实际查询");
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(query, 1));
        assertEquals(1, f.lastAdoption.events().stream().filter(event -> event.type().equals(AgentExecutionProgram.MODEL_RESULT_EVENT)).count());
        var knowledge = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(ExecutionStore.Kind.KNOWLEDGE, knowledge.action().kind());
        var request = f.codec.decodeKnowledgeRequest(knowledge.action().request().content());
        assertEquals("实际查询", ((com.skillforge.runtime.api.KnowledgeOperation.Search) request.request().operation()).query()); assertEquals(SessionFixtures.IDENTITY.trace(), request.request().trace());
        var bundle = f.bundle(request); var actual = f.knowledge(bundle); f.commit(knowledge, actual);
        assertEquals(bundle, assertInstanceOf(KnowledgePortal.KnowledgeOutcome.Retrieved.class, f.codec.decodeKnowledgeOutcome(actual.result().content())).bundle());
        assertEquals(List.of("actual-selected"), f.materials.knowledge().getFirst().injected());
        assertEquals(1, f.materials.usage().knowledgeCalls()); assertEquals(0, f.materials.usage().toolCalls());
        int measurements = f.measurements;
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        assertEquals(measurements, f.measurements);
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var messages = requestHistory(f,second);
        assertEquals(List.of(new com.skillforge.runtime.api.KnowledgeSourceVersion("source-1", "version-1")),
                f.codec.decodeModelRequest(second.action()).knowledgeSources());
        var assistant = messages.get(messages.size() - 2); var feedback = messages.getLast();
        assertEquals("knowledge_query", assistant.toolCalls().getFirst().wireName());
        assertEquals(assistant.toolCalls().getFirst().callId(), feedback.toolCallId().orElseThrow());
        assertTrue(feedback.textContent().contains("ACTUAL_KNOWLEDGE_TEXT")); assertFalse(feedback.textContent().contains("unselected-private-text"));
        f.commit(second, f.model(new AgentAction.Respond("有依据的实际回答", List.of("actual-selected")), 3));
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.COMPLETED, finish.status());
        var evidence = f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA).get("evidence").get(0);
        assertEquals("actual-selected", AgentExecutionCodec.string(evidence, "itemId")); assertEquals("version-1", AgentExecutionCodec.string(evidence, "sourceVersion"));
        assertEquals(actual.result().reference().value(), AgentExecutionCodec.string(evidence, "resultRef"));
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void withdrawnInjectedSourceStopsBeforeModelPreparationOrContextSummary() {
        var f = new Fixture(new KnowledgeBudget(1, 3, 1_000, 50, 1_000));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(first, f.model(new AgentAction.KnowledgeQuery("knowledge:docs", "actual query"), 1));
        var access = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(access, f.knowledge(f.bundle(f.codec.decodeKnowledgeRequest(access.action().request().content()))));
        var original = f.materials;
        int saved = f.saved.size(), measured = f.measurements, sampled = f.timeSamples;
        f.knowledgeAllowed = false;
        var stopped = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.FAILED, stopped.status());
        assertTrue(stopped.events().stream().anyMatch(event -> event.data().canonicalJson().contains("knowledge_source_unavailable")));
        assertEquals(saved, f.saved.size());
        assertEquals(measured, f.measurements);
        assertEquals(sampled, f.timeSamples);
        assertEquals(original, f.materials);
        assertEquals(List.of(new com.skillforge.runtime.api.KnowledgeSourceVersion("source-1", "version-1")), f.checkedKnowledge);
    }

    /** 同一知识查询复用原证据；未实际注入的候选ID不能作为最终引用。 */
    @Test
    void reusesExactKnowledgeWithoutRetrievalAndRejectsUninjectedCitation() {
        var f = new Fixture(new KnowledgeBudget(1, 3, 1_000, 50, 1_000)); var query = new AgentAction.KnowledgeQuery("knowledge:docs", "实际查询");
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(query, 1));
        var knowledge = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(knowledge, f.knowledge(f.bundle(f.codec.decodeKnowledgeRequest(knowledge.action().request().content()))));
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(second, f.model(query, 3));
        var reuse = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); assertEquals(ExecutionStore.Kind.MODEL, reuse.action().kind());
        assertEquals(1, f.materials.usage().knowledgeCalls());
        f.commit(reuse, f.model(new AgentAction.Respond("不能引用未注入候选", List.of("unselected")), 4));
        assertEquals(RunStatus.FAILED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
    }

    @Test
    void documentCatalogCompletesWithoutBodyEvidenceOrCitations() throws Exception {
        var corpus = documentCorpus(); var f = documentFixture(corpus, 65_536);
        assertEquals(Set.of("knowledge_list_documents", "knowledge_read_document"), f.configuration.modelTools().stream()
                .map(ModelTool::wireName).filter(name -> name.startsWith("knowledge_")).collect(java.util.stream.Collectors.toSet()));
        var action = new AgentAction.KnowledgeListDocuments("knowledge:docs", new KnowledgeOperation.ListDocuments("", Optional.empty(), 20));
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(model, f.model(action, model.action().ordinal()));
        var access = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeKnowledgeRequest(access.action().request().content());
        var documents = corpus.stream().limit(20).map(AgentExecutionProgramTest::documentMetadata).toList();
        f.commit(access, f.knowledge(documentBundle(request, new KnowledgeDocumentResult.CatalogPage(documents, Optional.of("frozen-next-page")), List.of())));
        var metadata = SessionRuntimePayloadJson.parse(f.materials.messages().getLast().textContent(), Fixture.LIMITS);
        assertEquals(20, metadata.path("document_access").path("documents").size()); assertTrue(metadata.path("items").isEmpty());
        assertEquals("frozen-next-page", metadata.path("document_access").path("next_cursor").asText());
        assertFalse(metadata.toString().contains(corpus.getFirst().path("text").asText()));
        assertTrue(f.materials.knowledge().getFirst().injected().isEmpty());
        var finalModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        assertEquals(documents.stream().map(document -> new com.skillforge.runtime.api.KnowledgeSourceVersion(document.sourceId(), document.sourceVersion()))
                .distinct().sorted(java.util.Comparator.comparing(com.skillforge.runtime.api.KnowledgeSourceVersion::sourceId)
                        .thenComparing(com.skillforge.runtime.api.KnowledgeSourceVersion::sourceVersion)).toList(),
                f.codec.decodeModelRequest(finalModel.action()).knowledgeSources());
        f.commit(finalModel, f.model(new AgentAction.Respond("本页目录包含：" + documents.getFirst().title(), List.of()), finalModel.action().ordinal()));
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials));
        assertEquals(RunStatus.COMPLETED, finish.status());
        assertTrue(f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA).path("evidence").isEmpty());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void documentReadContinuesExactFrozenSlicesAndCitesActualEvidence() throws Exception {
        var corpus = documentCorpus(); var f = documentFixture(corpus, 65_536);
        var original = corpus.stream().filter(document -> document.path("document_id").asText().equals("91062004")).findFirst().orElseThrow();
        var document = documentMetadata(original); String text = original.path("text").asText();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.KnowledgeListDocuments("knowledge:docs", new KnowledgeOperation.ListDocuments(document.title(), Optional.empty(), 20)), model.action().ordinal()));
        var catalog = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(catalog, f.knowledge(documentBundle(f.codec.decodeKnowledgeRequest(catalog.action().request().content()),
                new KnowledgeDocumentResult.CatalogPage(List.of(document), Optional.empty()), List.of())));
        var discovered = SessionRuntimePayloadJson.parse(f.materials.messages().getLast().textContent(), Fixture.LIMITS).path("document_access").path("documents").get(0);
        assertEquals(document.sourceVersion(), discovered.path("source_version").asText());
        var collected = new StringBuilder(); int offset = 0; String lastItem = "";
        while (offset < document.totalCharacters()) {
            var read = new KnowledgeOperation.ReadDocument(discovered.path("source_id").asText(), discovered.path("document_id").asText(), discovered.path("source_version").asText(), offset, 4000);
            var currentModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(currentModel, f.model(new AgentAction.KnowledgeReadDocument("knowledge:docs", read), currentModel.action().ordinal()));
            var access = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var request = f.codec.decodeKnowledgeRequest(access.action().request().content()); assertEquals(read, request.request().operation());
            var bundle = documentSlice(request, document, text, offset); lastItem = bundle.items().getFirst().itemId();
            f.commit(access, f.knowledge(bundle));
            var feedback = SessionRuntimePayloadJson.parse(f.materials.messages().getLast().textContent(), Fixture.LIMITS);
            var range = (KnowledgeDocumentResult.DocumentSlice) bundle.documentResult().orElseThrow();
            assertEquals(offset, feedback.path("document_access").path("start_offset").asInt());
            assertEquals(range.endOffset(), feedback.path("document_access").path("end_offset").asInt());
            collected.append(feedback.path("items").get(0).path("text").asText()); offset = range.nextOffset().orElse(document.totalCharacters());
        }
        assertEquals(text, collected.toString()); assertEquals(25600, collected.codePointCount(0, collected.length()));
        var finalModel = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(finalModel, f.model(new AgentAction.Respond("已读取全文，文末验收尾码SF-Q06-END-2841，最终签核角色设备管理员。", List.of(lastItem)), finalModel.action().ordinal()));
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)); assertEquals(RunStatus.COMPLETED, finish.status());
        var evidence = f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA).path("evidence").get(0);
        assertEquals(lastItem, evidence.path("itemId").asText()); assertEquals(document.sourceVersion(), evidence.path("sourceVersion").asText());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void repeatedDocumentSliceReauthorizesAndKeepsOnlyLatestCitationBinding() throws Exception {
        var corpus = documentCorpus(); var f = documentFixture(corpus, 65_536); var original = corpus.getFirst(); var document = documentMetadata(original);
        for (int repeat = 0; repeat < 2; repeat++) {
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.model(new AgentAction.KnowledgeReadDocument("knowledge:docs", new KnowledgeOperation.ReadDocument(document.sourceId(), document.documentId(), document.sourceVersion(), 0, 4000)), model.action().ordinal()));
            var access = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); assertEquals(ExecutionStore.Kind.KNOWLEDGE, access.action().kind());
            f.commit(access, f.knowledge(documentSlice(f.codec.decodeKnowledgeRequest(access.action().request().content()), document, original.path("text").asText(), 0)));
        }
        assertEquals(2, f.materials.usage().knowledgeCalls()); assertEquals(2, f.materials.knowledge().size());
        assertTrue(f.materials.knowledge().getFirst().injected().isEmpty()); assertEquals(1, f.materials.knowledge().getLast().injected().size());
        assertFalse(f.materials.knowledge().getFirst().disclosedSources().isEmpty());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var latest = f.materials.knowledge().getLast(); var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.Respond("根据同一冻结文档重新核对。", latest.injected()), model.action().ordinal()));
        var finish = assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)); assertEquals(RunStatus.COMPLETED, finish.status());
        assertEquals(latest.result().value(), f.codec.parse(finish.events().getFirst().data(), AgentExecutionProgram.COMPLETED_SCHEMA).path("evidence").get(0).path("resultRef").asText());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test
    void documentMetadataTokenBudgetRefusalIsSavedAndRecoveredWithoutRecounting() throws Exception {
        var corpus = documentCorpus(); var f = documentFixture(corpus, 1); var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.KnowledgeListDocuments("knowledge:docs", new KnowledgeOperation.ListDocuments("", Optional.empty(), 20)), model.action().ordinal()));
        var access = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(access, f.knowledge(documentBundle(f.codec.decodeKnowledgeRequest(access.action().request().content()), new KnowledgeDocumentResult.CatalogPage(List.of(documentMetadata(corpus.getFirst())), Optional.empty()), List.of())));
        assertTrue(f.materials.knowledge().isEmpty()); assertTrue(f.materials.messages().getLast().textContent().contains("knowledge_document_budget_exceeded"));
        int measured = f.measurements; assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context)); assertEquals(measured, f.measurements);
    }

    @Test
    void unavailableDocumentSelectorReturnsTypedFeedbackAndCanFinishNormally() throws Exception {
        var f = documentFixture(documentCorpus(), 65_536);
        var selector = new KnowledgeOperation.ReadDocument("not-mounted-source", "unknown-document", "not-authorized-version", 0, 4000);
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.model(new AgentAction.KnowledgeReadDocument("knowledge:docs", selector), model.action().ordinal()));
        var access = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeKnowledgeRequest(access.action().request().content());
        assertEquals(selector, request.request().operation());
        assertFalse(request.request().allowedSourceIds().contains(selector.sourceId()));
        var rejected = new KnowledgePortal.KnowledgeOutcome.Failed(new PortFailure("knowledge_document_unavailable", RetryMode.NONE, new ResultRef("document-refusal-audit")));
        var actual = new CapabilityResult(ExecutionStore.ResultKind.FAILED, new Material(new ResultRef("document-unavailable-result"),
                f.codec.encodeOutcome(f.core.portalResults(f.payloads, f.payloads, f.payloads).encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, rejected))), Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported());
        f.commit(access, actual);
        assertTrue(f.materials.problem().isEmpty()); assertTrue(f.materials.knowledge().isEmpty());
        assertTrue(f.materials.messages().getLast().textContent().contains("knowledge_document_unavailable"));
        assertFalse(f.materials.messages().getLast().textContent().contains("unknown-document"));
        var reply = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(reply, f.model(new AgentAction.Respond("无法读取指定文档，请从当前授权目录选择文档。", List.of()), reply.action().ordinal()));
        assertEquals(RunStatus.COMPLETED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> documentCorpus() throws Exception {
        var source = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(java.nio.file.Path.of("..", "..", "tools", "knowledge_evaluation", "suite.json")));
        return java.util.stream.StreamSupport.stream(source.path("documents").spliterator(), false)
                .filter(document -> document.path("tenant_id").asText().equals("910601") && document.path("mounted").asBoolean()).toList();
    }
    private static KnowledgeDocumentResult.Document documentMetadata(com.fasterxml.jackson.databind.JsonNode source) {
        String text = source.path("text").asText();
        return new KnowledgeDocumentResult.Document(source.path("source_id").asText(), source.path("document_id").asText(), source.path("source_version").asText(), source.path("title").asText(), text.codePointCount(0, text.length()));
    }
    private static Fixture documentFixture(List<com.fasterxml.jackson.databind.JsonNode> corpus, int tokens) {
        var f = new Fixture(); var c = f.configuration;
        var versions = corpus.stream().map(document -> new KnowledgeSourceVersion(document.path("source_id").asText(), document.path("source_version").asText())).collect(java.util.stream.Collectors.toSet());
        var binding = new AgentExecutionProgram.KnowledgeBinding(new KnowledgePortal.FrozenKnowledgeTarget(new KnowledgePortal.KnowledgeHandle("knowledge:docs"), new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "documents", "published-v1"), "documents", 1,
                versions, Set.of(KnowledgeOperatorType.DOCUMENT_LIST, KnowledgeOperatorType.DOCUMENT_READ), Duration.ofSeconds(1)), KnowledgeMode.GROUNDING, "read frozen documents", new KnowledgeBudget(1, 50, tokens, 262144, 1000), SessionFixtures.NOW, Optional.empty(), true);
        var tools = AgentExecutionProgram.modelTools(c.profile(), List.of(), Map.of(), List.of(binding), List.of(), List.of(), List.of());
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), tools, List.of(), c.budget(), c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), List.of(binding), List.of(), Set.of(), List.of(), c.initialPlanState(), false);
        f.materials = f.program.open(f.run, f.context); return f;
    }
    private static EvidenceBundle documentBundle(AgentExecutionCodec.KnowledgeCallRequest call, KnowledgeDocumentResult result, List<EvidenceItem> items) {
        var operator = result instanceof KnowledgeDocumentResult.CatalogPage ? KnowledgeOperatorType.DOCUMENT_LIST : KnowledgeOperatorType.DOCUMENT_READ;
        var step = new KnowledgeStep("document-step", "frozen document access", call.request().allowedSourceIds(), operator, List.of(), call.request().budget());
        var ids = items.stream().map(EvidenceItem::itemId).toList();
        return new EvidenceBundle("document-bundle:" + call.request().knowledgeRequestId(), call.request().knowledgeRequestId(), new KnowledgePlan("plan:" + call.request().knowledgeRequestId(), call.request().knowledgeRequestId(), List.of(step), "page completed", "document-v1"),
                items, List.of(), EvidenceSufficiency.INSUFFICIENT, List.of(new KnowledgeTraceStep(step.stepId(), "document access", ids, ids, 0, 1)), 1, SessionFixtures.NOW, Optional.of(result));
    }
    private static EvidenceBundle documentSlice(AgentExecutionCodec.KnowledgeCallRequest call, KnowledgeDocumentResult.Document document, String original, int offset) {
        int end = Math.min(document.totalCharacters(), offset + 4000); String text = original.substring(original.offsetByCodePoints(0, offset), original.offsetByCodePoints(0, end));
        var item = new EvidenceItem("document-range:" + document.documentId() + ":" + offset + ":" + end, document.sourceId(), document.sourceVersion(), KnowledgeOperatorType.DOCUMENT_READ,
                new TypedPayload("skillforge.runtime.knowledge.document-slice.v1", SessionRuntimePayloadJson.write(Map.of("text", text))), List.of("knowledge-document:" + document.documentId(), "knowledge-range:" + offset + ":" + end), List.of(), 1, "published-source");
        return documentBundle(call, new KnowledgeDocumentResult.DocumentSlice(document, offset, end, end == document.totalCharacters() ? OptionalInt.empty() : OptionalInt.of(end)), List.of(item));
    }

    @Test void resourceMixedWithToolKeepsPreviouslyAdoptedReuseAndResourceOnlyBreaksIt() {
        for (boolean onlyResource : List.of(false, true)) {
            var f = skillBatchFixture(10); var call = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{}"));
            var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(call, 1));
            var tool = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"UNCHANGED\"}]}"));
            var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(second, f.model(call, second.action().ordinal()));
            var next = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            var resource = skillResourceCall("progress-resource", "references/guide.md");
            var parts = new ArrayList<AssistantContent>(); parts.add(resource);
            if (!onlyResource) parts.add(new AssistantContent.ToolCall("progress-tool", "sf_template", "{}"));
            f.commit(next, f.nativeModel(new AssistantTurn(parts), "tool_calls", next.action().ordinal(), Optional.empty()));
            assertEquals(onlyResource ? 0 : 2, f.materials.progress().size());
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
    }

    @Test void nativeSkillResourceAndSingleReadOnlyToolUseOnePersistentBusinessActionAndRestore() {
        for (boolean resourceFirst : List.of(true, false)) {
            var f = skillBatchFixture(1); var originalHistory = f.materials.messages();
            var resource = skillResourceCall("mixed-resource", "references/guide.md");
            String arguments = "{ \"value\": 1.0 }";
            var business = new AssistantContent.ToolCall("mixed-business:1", "sf_template", arguments);
            var ordered = resourceFirst ? List.of(resource, business) : List.of(business, resource);
            var parts = new ArrayList<AssistantContent>(); parts.add(new AssistantContent.Text("读取冻结参考与业务事实")); parts.addAll(ordered);
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.nativeModel(new AssistantTurn(parts), "tool_calls", model.action().ordinal(), Optional.of("原混批推理")));
            assertEquals(2, f.materials.skills().loaded().size());
            assertEquals(originalHistory, f.materials.messages(), "业务反馈未提交前不能追加半个原生调用组");
            assertEquals(0, f.materials.usage().toolCalls());
            var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
            var restored = cold.open(f.run, f.context); assertEquals(f.materials, restored);
            var tool = assertInstanceOf(Step.Dispatch.class, cold.next(f.run, restored));
            assertEquals(ExecutionStore.Kind.TOOL, tool.action().kind());
            assertEquals(business.callId(), f.codec.decodeTool(tool.action().request().content()).callId());
            f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"ACTUAL_SINGLE_BUSINESS\"}]}"));
            assertEquals(1, f.materials.usage().toolCalls());
            assertEquals(resourceFirst ? 2 : 1, f.materials.progress().getLast().slot());
            var messages = f.materials.messages(); var assistant = messages.get(messages.size() - 3);
            assertEquals(ordered.stream().map(AssistantContent.ToolCall::callId).toList(), assistant.toolCalls().stream().map(ModelToolCall::callId).toList());
            assertEquals("读取冻结参考与业务事实", assistant.textContent()); assertEquals(Optional.of("原混批推理"), assistant.reasoningContent());
            assertEquals(arguments, assistant.toolCalls().stream().filter(call -> call.callId().equals(business.callId())).findFirst().orElseThrow().arguments().canonicalJson());
            assertEquals(ordered.stream().map(AssistantContent.ToolCall::callId).toList(), messages.subList(messages.size() - 2, messages.size()).stream().map(message -> message.toolCallId().orElseThrow()).toList());
            assertTrue(messages.stream().filter(message -> message.toolCallId().filter(resource.callId()::equals).isPresent()).findFirst().orElseThrow().textContent().endsWith("FROZEN_GUIDE_REFERENCE"));
            assertEquals("ACTUAL_SINGLE_BUSINESS", messages.stream().filter(message -> message.toolCallId().filter(business.callId()::equals).isPresent()).findFirst().orElseThrow().textContent());
            ModelPort.validateConversation(messages);
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
            assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)).action().kind());
        }
    }

    @Test void nativeAliasResolvesToLeadingUnderscoreMcpBinding() {
        var f = skillBatchFixture(10); var c = f.configuration;
        var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.MCP, "external", "_search");
        var entry = new SessionToolDirectory.Entry(f.entry.target(), binding,
                new SessionToolDirectory.Discovery(binding.serverIdentity(), binding.serverIdentity() + "/_search", "_search", Optional.empty(), "查询"), true);
        var tools = SessionNativeConversation.modelTools(c.profile(), List.of(entry),
                Map.of(entry.target().handle().value(), "definition-1"), List.of());
        var declared = tools.stream().filter(tool -> tool.kind() == ModelToolKind.TOOL_CALL).findFirst().orElseThrow();
        assertNotEquals("_search", declared.wireName());
        assertEquals(entry.target().handle().value(), declared.toolHandle());
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), tools, List.of(entry), c.budget(),
                c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(),
                c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired());
        f.materials = f.program.open(f.run, f.context);
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var frozen = f.codec.decodeModelRequest(model.action()).request().tools().stream()
                .filter(tool -> tool.toolHandle().equals(declared.toolHandle())).findFirst().orElseThrow();
        var call = new AssistantContent.ToolCall("provider-call", frozen.wireName(), "{}");
        f.commit(model, f.nativeModel(new AssistantTurn(List.of(call)), "tool_calls", model.action().ordinal(), Optional.empty()));
        var dispatched = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var request = f.codec.decodeTool(dispatched.action().request().content());
        assertEquals(frozen.wireName(), request.wireName());
        assertEquals(entry.target(), request.target());
        assertEquals(binding, request.binding());
    }

    @Test void nativeExternalToolNamedReadSkillResourceKeepsPhysicalInvocationAndDoesNotLoadFrozenSkill() {
        var f = skillBatchFixture(10); var c = f.configuration;
        var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.MCP, "external-files", "read_skill_resource");
        var entry = new SessionToolDirectory.Entry(f.entry.target(), binding,
                new SessionToolDirectory.Discovery(binding.serverIdentity(), binding.serverIdentity() + "/" + binding.toolName(),
                        binding.toolName(), Optional.empty(), "同名外部业务工具"), true);
        var tools = c.modelTools().stream()
                .filter(tool -> !(tool.kind() == ModelToolKind.AGENT_ACTION && tool.wireName().equals("read_skill_resource")))
                .map(tool -> tool.kind() == ModelToolKind.TOOL_CALL
                        ? new ModelTool("read_skill_resource", tool.toolHandle(), tool.description(), tool.inputSchema(), tool.strict(), tool.kind(), tool.batchEligible())
                        : tool).toList();
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), tools, List.of(entry), c.budget(),
                c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(),
                c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired());
        f.materials = f.program.open(f.run, f.context);
        var initial = f.materials.skills(); var originalHistory = f.materials.messages();
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var frozenTools = f.codec.decodeModelRequest(model.action()).request().tools();
        assertEquals(1, frozenTools.stream().filter(tool -> tool.wireName().equals("read_skill_resource")).count());
        assertEquals(ModelToolKind.TOOL_CALL, frozenTools.stream().filter(tool -> tool.wireName().equals("read_skill_resource")).findFirst().orElseThrow().kind());
        var call = skillResourceCall("external-resource-call", "references/guide.md");
        f.commit(model, f.nativeModel(new AssistantTurn(List.of(call)), "tool_calls", model.action().ordinal(), Optional.empty()));
        assertEquals(initial, f.materials.skills()); assertEquals(originalHistory, f.materials.messages());
        var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        var restored = cold.open(f.run, f.context); assertEquals(f.materials, restored);
        var dispatched = assertInstanceOf(Step.Dispatch.class, cold.next(f.run, restored));
        assertEquals(ExecutionStore.Kind.TOOL, dispatched.action().kind());
        var request = f.codec.decodeTool(dispatched.action().request().content());
        assertEquals(call.callId(), request.callId()); assertEquals(call.wireName(), request.wireName());
        assertEquals(binding, request.binding());
        assertEquals(SessionRuntimePayloadJson.parse(call.argumentsJson(),Fixture.LIMITS),SessionRuntimePayloadJson.parse(request.arguments().canonicalJson(),Fixture.LIMITS));
        f.commit(dispatched, f.tool(dispatched, "{\"content\":[{\"type\":\"text\",\"text\":\"ACTUAL_EXTERNAL_RESOURCE_FEEDBACK\"}]}"));
        assertEquals(1, f.materials.usage().toolCalls()); assertEquals(initial, f.materials.skills());
        var messages = f.materials.messages();
        assertEquals(call.callId(), messages.get(messages.size() - 2).toolCalls().getFirst().callId());
        assertEquals("read_skill_resource", messages.get(messages.size() - 2).toolCalls().getFirst().wireName());
        assertEquals(Optional.of(call.callId()), messages.getLast().toolCallId());
        assertEquals("ACTUAL_EXTERNAL_RESOURCE_FEEDBACK", messages.getLast().textContent());
        ModelPort.validateConversation(messages);
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    @Test void nativeSkillResourceAndTwoBusinessToolsKeepFullOriginalCallOrderAcrossColdRecovery() {
        for (boolean parallel : List.of(false, true)) {
            var f = skillBatchFixture(10); var c = f.configuration;
            var entry = new SessionToolDirectory.Entry(f.entry.target(), f.entry.binding(), f.entry.discovery(), parallel);
            f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), c.modelTools(), List.of(entry), c.budget(),
                    c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(),
                    c.workflows(), c.initialPlanState(), c.planRequired());
            f.materials = f.program.open(f.run, f.context); var history = f.materials.messages();
            assertTrue(c.modelTools().stream().filter(tool -> tool.kind() == ModelToolKind.TOOL_CALL || tool.wireName().equals("read_skill_resource")).allMatch(ModelTool::batchEligible));
            var first = new AssistantContent.ToolCall("mixed-batch-business:1", "sf_template", "{\"value\":1}");
            var resource = skillResourceCall("mixed-batch-resource", "references/guide.md");
            var second = new AssistantContent.ToolCall("mixed-batch-business:2", "sf_template", "{\"value\":2}");
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.nativeModel(new AssistantTurn(List.of(new AssistantContent.Text("同轮读取"), first, resource, second)), "tool_calls", model.action().ordinal(), Optional.of("混批推理")));
            assertEquals(2, f.materials.skills().loaded().size()); assertEquals(history, f.materials.messages());
            var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS); var restored = cold.open(f.run, f.context);
            assertEquals(f.materials, restored); assertEquals(parallel, cold.batchParallel(restored));
            var batch = assertInstanceOf(Step.Batch.class, cold.next(f.run, restored));
            assertEquals(2, batch.calls().size());
            assertEquals(List.of(first.callId(), second.callId()), batch.calls().stream().map(call -> f.codec.decodeTool(call.action().request().content()).callId()).toList());
            var firstActual = new BatchCall(batch.calls().getFirst().action(), batch.calls().getFirst().attemptId(), f.tool(batch.calls().getFirst(), "{\"content\":[{\"type\":\"text\",\"text\":\"MIXED_FIRST_ACTUAL\"}]}"));
            var secondActual = new BatchCall(batch.calls().getLast().action(), batch.calls().getLast().attemptId(), f.tool(batch.calls().getLast(), "{\"content\":[{\"type\":\"text\",\"text\":\"MIXED_SECOND_ACTUAL\"}]}"));
            f.commitBatchOutcomes(List.of(new BatchOutcome.Actual(2, secondActual), new BatchOutcome.Actual(1, firstActual)));
            assertEquals(List.of(1, 3), f.materials.progress().stream().map(AgentToolProgress.Sample::slot).toList());
            assertEquals(2, f.materials.usage().toolCalls()); assertEquals(1, f.materials.usage().businessModelCalls());
            var messages = f.materials.messages(); var assistant = messages.get(messages.size() - 4);
            assertEquals(List.of(first.callId(), resource.callId(), second.callId()), assistant.toolCalls().stream().map(ModelToolCall::callId).toList());
            assertEquals(Optional.of("混批推理"), assistant.reasoningContent());
            assertEquals(List.of(first.callId(), resource.callId(), second.callId()), messages.subList(messages.size() - 3, messages.size()).stream().map(message -> message.toolCallId().orElseThrow()).toList());
            assertEquals("MIXED_FIRST_ACTUAL", messages.get(messages.size() - 3).textContent());
            assertTrue(messages.get(messages.size() - 2).textContent().endsWith("FROZEN_GUIDE_REFERENCE"));
            assertEquals("MIXED_SECOND_ACTUAL", messages.getLast().textContent());
            ModelPort.validateConversation(messages);
            var completed = new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context);
            assertEquals(f.materials, completed); assertEquals(2, completed.usage().toolCalls());
            assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, cold.next(f.run, completed)).action().kind(),
                    "资源和业务结果均已按原call ID闭合，冷恢复不能重新派发工具");
        }
    }

    @Test void mixedResourceChangesBreakToolCycleWhileIdenticalResourcesStillStopAcrossRecovery() {
        for (String change : List.of("same", "resource", "order")) {
            var f = skillBatchFixture(1);
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            for (int lap = 1; lap <= 5; lap++) {
                var path = change.equals("resource") && lap == 5 ? "references/detail.md" : "references/guide.md";
                var business = new AssistantContent.ToolCall("progress-business:" + lap, "sf_template", "{}");
                var resource = skillResourceCall("progress-resource:" + lap, path);
                var calls = change.equals("order") && lap == 5 ? List.of(resource, business) : List.of(business, resource);
                f.commit(model, f.nativeModel(new AssistantTurn(new ArrayList<AssistantContent>(calls)), "tool_calls", model.action().ordinal(), Optional.empty()));
                var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
                assertEquals(f.materials, cold.open(f.run, f.context));
                if (lap == 1) {
                    var tool = assertInstanceOf(Step.Dispatch.class, cold.next(f.run, cold.open(f.run, f.context)));
                    assertEquals(ExecutionStore.Kind.TOOL, tool.action().kind());
                    f.commit(tool, f.tool(tool, "{\"content\":[{\"type\":\"text\",\"text\":\"UNCHANGED\"}]}"));
                }
                var restored = cold.open(f.run, f.context); assertEquals(f.materials, restored);
                assertEquals(1, restored.usage().toolCalls()); assertEquals(lap, restored.usage().businessModelCalls());
                var next = cold.next(f.run, restored);
                if (lap == 5 && change.equals("same")) {
                    var terminal = assertInstanceOf(Step.Finish.class, next).terminalContext().orElseThrow(); f.saved.add(terminal);
                    var previous = f.run; var progress = previous.progress();
                    var ended = new ExecutionStore.Run(previous.identity(), previous.configuration(), RunStatus.TERMINATED, previous.revision() + 1, previous.fence(), false,
                            ExecutionStore.Phase.FINISHED, new ExecutionStore.Progress(progress.decisions(), progress.toolCalls(), progress.knowledgeCalls(), terminal.reference()),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), previous.actorVerificationRef(), Optional.empty(), previous.createdAt(), previous.updatedAt());
                    assertEquals(terminal.reference(), cold.open(ended, terminal).context());
                } else model = assertInstanceOf(Step.Dispatch.class, next);
            }
        }
    }

    @Test void resourceBetweenReusedAndFreshBatchSlotsPreservesRealSourcesAndBudget() {
        var f = skillBatchFixture(2);
        var oldCall = new AgentAction.ToolCall("tool:template", new TypedPayload("application/json", "{\"value\":1}"));
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(oldCall, first.action().ordinal()));
        var old = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(old, f.tool(old, "{\"content\":[{\"type\":\"text\",\"text\":\"ORIGINAL\"}]}"));
        var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var calls = List.of(new AssistantContent.ToolCall("partial-old", "sf_template", "{\"value\":1}"),
                skillResourceCall("partial-resource", "references/guide.md"),
                new AssistantContent.ToolCall("partial-new", "sf_template", "{\"value\":2}"));
        f.commit(model, f.nativeModel(new AssistantTurn(new ArrayList<AssistantContent>(calls)), "tool_calls", model.action().ordinal(), Optional.empty()));
        var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS);
        var batch = assertInstanceOf(Step.Batch.class, cold.next(f.run, cold.open(f.run, f.context)));
        var reused = assertInstanceOf(ExecutionStore.BatchMember.Reused.class, batch.members().getFirst());
        var fresh = batch.calls().getFirst(); assertEquals(1, batch.calls().size());
        f.commitBatchOutcomes(List.of(new BatchOutcome.Reused(1, reused), new BatchOutcome.Actual(2,
                new BatchCall(fresh.action(), fresh.attemptId(), f.tool(fresh, "{\"content\":[{\"type\":\"text\",\"text\":\"FRESH\"}]}")))));
        var samples = f.materials.progress();
        assertEquals(List.of(1, 1, 3), samples.stream().map(AgentToolProgress.Sample::slot).toList());
        assertEquals(old.action().action(), samples.get(1).source().action());
        assertEquals(fresh.action().action(), samples.get(2).source().action());
        assertEquals(samples.get(1).signature().resources(), samples.get(2).signature().resources());
        assertEquals(2, samples.get(1).signature().resources().getFirst().slot());
        assertEquals(2, f.materials.usage().toolCalls()); assertEquals(2, f.materials.usage().businessModelCalls());
        assertEquals(f.materials, cold.open(f.run, f.context));
    }

    @Test void nativeMultipleSkillResourcesAndRepeatedReadsArePureAndIdempotent() {
        var f = skillBatchFixture(0);
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var calls = List.of(skillResourceCall("pure-guide:1", "references/guide.md"), skillResourceCall("pure-detail:2", "references/detail.md"), skillResourceCall("pure-repeat:3", "references/guide.md"));
        f.commit(first, f.nativeModel(new AssistantTurn(new ArrayList<AssistantContent>(calls)), "tool_calls", first.action().ordinal(), Optional.empty()));
        assertEquals(3, f.materials.skills().loaded().size()); assertEquals(0, f.materials.usage().toolCalls());
        assertEquals(calls.stream().map(AssistantContent.ToolCall::callId).toList(), f.materials.messages().get(f.materials.messages().size() - 4).toolCalls().stream().map(ModelToolCall::callId).toList());
        var loaded = f.materials.skills();
        var cold = new AgentExecutionProgram(f, f.core, Fixture.LIMITS); assertEquals(f.materials, cold.open(f.run, f.context));
        var next = assertInstanceOf(Step.Dispatch.class, cold.next(f.run, cold.open(f.run, f.context))); assertEquals(ExecutionStore.Kind.MODEL, next.action().kind());
        f.commit(next, f.nativeModel(new AssistantTurn(List.of(skillResourceCall("repeat-again:1", "references/guide.md"), skillResourceCall("repeat-again:2", "references/detail.md"))), "tool_calls", next.action().ordinal(), Optional.empty()));
        assertEquals(loaded, f.materials.skills()); assertEquals(0, f.materials.usage().toolCalls());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        ModelPort.validateConversation(f.materials.messages());
    }

    @Test void nativeSkillResourceAndReadOnlyExternalCodeNameKeepCompleteGroupWhenPlanAdmissionRejectsBusiness() {
        var f=skillBatchFixture(10);var c=f.configuration;var original=c.tools().getFirst();
        // 原计划语义按逻辑工具名识别代码能力，即使合法外部冻结声明标为只读也不能绕过。
        var binding=new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.MCP,"external-code","run_code");
        var entry=new SessionToolDirectory.Entry(original.target(),binding,new SessionToolDirectory.Discovery(binding.serverIdentity(),
                binding.serverIdentity()+"/"+binding.toolName(),binding.toolName(),Optional.empty(),"外部只读代码分析"),true);
        f.configuration=new AgentExecutionProgram.Configuration(c.profile(),c.target(),c.modelTools(),List.of(entry),c.budget(),c.capabilitySnapshot(),
                c.capabilityRef(),c.initialMessages(),c.actor(),c.knowledge(),c.skills(),c.availableCapabilities(),c.workflows(),c.initialPlanState(),true);
        f.materials=f.program.open(f.run,f.context);
        var model=assertInstanceOf(Step.Dispatch.class,f.program.next(f.run,f.materials));
        var calls=List.of(skillResourceCall("plan-resource","references/guide.md"),new AssistantContent.ToolCall("plan-business","sf_template","{}"));
        f.commit(model,f.nativeModel(new AssistantTurn(new ArrayList<AssistantContent>(calls)),"tool_calls",model.action().ordinal(),Optional.empty()));
        assertEquals(0,f.materials.usage().toolCalls());assertTrue(f.materials.decision().isEmpty());assertTrue(f.materials.problem().isEmpty());
        var messages=f.materials.messages();ModelPort.validateConversation(messages);
        assertEquals(List.of("plan-resource","plan-business"),messages.get(messages.size()-4).toolCalls().stream().map(ModelToolCall::callId).toList());
        assertEquals(Optional.of("plan-resource"),messages.get(messages.size()-3).toolCallId());
        assertTrue(messages.get(messages.size()-3).textContent().contains("plan_required_before_execution"));
        assertEquals(Optional.of("plan-business"),messages.get(messages.size()-2).toolCallId());
        assertTrue(messages.getLast().textContent().contains("plan_required_before_execution"));
        var cold=new AgentExecutionProgram(f,f.core,Fixture.LIMITS);assertEquals(f.materials,cold.open(f.run,f.context));
        assertEquals(ExecutionStore.Kind.MODEL,assertInstanceOf(Step.Dispatch.class,cold.next(f.run,cold.open(f.run,f.context))).action().kind());
    }

    @Test void nativeSkillResourceBatchRejectsInvalidArgumentsAndMissingResourcesBeforeLoading() {
        var valid = skillResourceCall("valid-resource", "references/guide.md");
        var business = new AssistantContent.ToolCall("valid-business", "sf_template", "{}");
        var cases = List.of(List.of(valid, new AssistantContent.ToolCall("bad-business", "sf_template", "{broken")),
                List.of(valid, new AssistantContent.ToolCall("array-business", "sf_template", "[]")),
                List.of(business, new AssistantContent.ToolCall("bad-resource", "read_skill_resource", "{\"skill_handle\":\"skill:batch\",\"path\":7}")),
                List.of(valid, skillResourceCall("missing-resource", "references/missing.md"), business));
        for (var calls : cases) {
            var f = skillBatchFixture(10); var initial = f.materials.skills(); var history = f.materials.messages();
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.nativeModel(new AssistantTurn(new ArrayList<AssistantContent>(calls)), "tool_calls", model.action().ordinal(), Optional.empty()));
            boolean missing = calls.stream().anyMatch(call -> call.argumentsJson().contains("missing.md"));
            if (missing) {
                assertEquals(RunStatus.FAILED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
                assertEquals(history, f.materials.messages());
            } else {
                assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)).action().kind());
                ModelPort.validateConversation(f.materials.messages());
                assertEquals(calls.size(), f.materials.messages().stream().filter(message -> message.role() == ModelRole.TOOL).count());
                assertTrue(f.materials.messages().getLast().textContent().contains("整组调用均未执行"));
            }
            assertEquals(initial, f.materials.skills(), "任何后续成员非法时不能提前采用前面的资源加载");
            assertEquals(0, f.materials.usage().toolCalls());
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
    }

    @Test void nativeSkillResourceBatchRejectsActivationPlanAskWriteAndConfirmationAsWholeTurn() {
        for (var control : List.of(new AssistantContent.ToolCall("activate", "use_skill", "{\"skill_handle\":\"skill:batch\"}"),
                new AssistantContent.ToolCall("plan", "plan_get", "{\"work_id\":\"work\"}"),
                new AssistantContent.ToolCall("ask", "ask_user", "{\"question\":\"选择范围\"}"))) {
            var f = skillBatchFixture(10); var initial = f.materials.skills();
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.nativeModel(new AssistantTurn(List.of(skillResourceCall("read", "references/guide.md"), control)), "tool_calls", model.action().ordinal(), Optional.empty()));
            var retry=assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            assertEquals(ExecutionStore.Kind.MODEL,retry.action().kind());
            assertEquals(2,f.materials.messages().stream().filter(message -> message.role()==ModelRole.TOOL).count());
            ModelPort.validateConversation(f.materials.messages());
            assertEquals(initial, f.materials.skills()); assertEquals(0, f.materials.usage().toolCalls());
        }
        for (var reason : List.of("schema", "write", "confirmation")) {
            var f = skillBatchFixture(10); var c = f.configuration; var old = f.entry.target();
            var target = new ToolPortal.FrozenToolTarget(old.handle(), old.capability(), old.deploymentId(), old.deploymentRevision(),
                    reason.equals("schema") ? new TypedPayload("json-schema", "{\"type\":\"object\",\"required\":[\"required_value\"]}") : old.inputSchema(), old.outputSchema(),
                    reason.equals("write") ? ToolEffect.IDEMPOTENT_WRITE : old.effect(), old.idempotent(), old.timeout(), old.modelInvocable(),
                    reason.equals("confirmation") ? ToolPortal.ToolConfirmationPolicy.POLICY : old.confirmation(), old.sensitiveFields(), old.providerRequirements(), old.outputSchemaDeclared(), old.callIdentityPolicy());
            var entry = new SessionToolDirectory.Entry(target, f.entry.binding(), f.entry.discovery(), false);
            var declarations = c.modelTools().stream().map(tool -> tool.kind() == ModelToolKind.TOOL_CALL
                    ? new ModelTool(tool.wireName(), tool.toolHandle(), tool.description(), target.inputSchema(), tool.strict(), tool.kind(), false) : tool).toList();
            f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), declarations, List.of(entry), c.budget(), c.capabilitySnapshot(), c.capabilityRef(),
                    c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired());
            f.materials = f.program.open(f.run, f.context); var initial = f.materials.skills();
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.nativeModel(new AssistantTurn(List.of(skillResourceCall("read", "references/guide.md"), new AssistantContent.ToolCall("business", "sf_template", "{}"))), "tool_calls", model.action().ordinal(), Optional.empty()));
            if (reason.equals("schema")) {
                assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)).action().kind());
                ModelPort.validateConversation(f.materials.messages());
                assertEquals(2, f.materials.messages().stream().filter(message -> message.role() == ModelRole.TOOL).count());
            } else assertEquals(RunStatus.FAILED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
            assertEquals(initial, f.materials.skills()); assertEquals(0, f.materials.usage().toolCalls());
            assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        }
    }

    @Test void nativeSkillResourceBatchesRespectEightCallsAndPhysicalToolBudget() {
        for (int size : List.of(8, 9)) {
            var f = skillBatchFixture(0); var initial = f.materials.skills(); var calls = new ArrayList<AssistantContent>();
            for (int index = 0; index < size; index++) calls.add(skillResourceCall("limit-resource:" + index, "references/guide.md"));
            var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
            f.commit(model, f.nativeModel(new AssistantTurn(calls), "tool_calls", model.action().ordinal(), Optional.empty()));
            if (size == 8) {
                assertEquals(2, f.materials.skills().loaded().size());
                assertEquals(ExecutionStore.Kind.MODEL, assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)).action().kind());
            } else {
                assertEquals(RunStatus.FAILED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
                assertEquals(initial, f.materials.skills());
            }
            assertEquals(0, f.materials.usage().toolCalls());
        }
        var f = skillBatchFixture(1); var model = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        f.commit(model, f.nativeModel(new AssistantTurn(List.of(skillResourceCall("budget-read", "references/guide.md"),
                new AssistantContent.ToolCall("budget-business:1", "sf_template", "{\"value\":1}"),
                new AssistantContent.ToolCall("budget-business:2", "sf_template", "{\"value\":2}"))), "tool_calls", model.action().ordinal(), Optional.empty()));
        assertEquals(RunStatus.TERMINATED, assertInstanceOf(Step.Finish.class, f.program.next(f.run, f.materials)).status());
        assertEquals(0, f.materials.usage().toolCalls());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
    }

    private static AssistantContent.ToolCall skillResourceCall(String id, String path) {
        return new AssistantContent.ToolCall(id, "read_skill_resource", "{\"skill_handle\":\"skill:batch\",\"path\":\"" + path + "\"}");
    }

    private static Fixture skillBatchFixture(int toolCalls) {
        var resources = new ArrayList<SkillActivation.Resource>();
        for (var resource : Map.of("SKILL.md", "FROZEN_BATCH_ENTRY", "references/guide.md", "FROZEN_GUIDE_REFERENCE", "references/detail.md", "FROZEN_DETAIL_REFERENCE").entrySet()) {
            var content = resource.getValue(); resources.add(new SkillActivation.Resource(resource.getKey(), "text/markdown", "UTF8", content,
                    AgentExecutionCodec.digest(content), content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        }
        var descriptor = new SkillActivation.Descriptor(true, false, SkillActivation.Mode.ALWAYS, new SkillActivation.Metadata("冻结混批包", "只读资源", List.of()), resources, Optional.empty(), List.of(), 5);
        var mount = new SkillActivation.Mount("skill:batch", new CapabilityRef(CapabilityType.SKILL, "batch", "v1"), SkillActivation.Mode.ALWAYS, 1_000, descriptor);
        return new Fixture(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(mount), false, List.of(), PlanPort.State.absent(), false, Optional.empty(), toolCalls);
    }

    /** Skill纯动作与原MODEL结果同提交；资源正文进入下一真实请求，不创建工具Attempt。 */
    @Test
    void appliesFrozenSkillsInsideModelCompletionAndRebuildsTheSameResourceConversation() {
        String entry = "FROZEN_SKILL_ENTRY"; String reference = "FROZEN_SKILL_REFERENCE";
        var descriptor = new SkillActivation.Descriptor(true, false, SkillActivation.Mode.ON_DEMAND,
                new SkillActivation.Metadata("真实包", "冻结说明", List.of()), List.of(
                        new SkillActivation.Resource("SKILL.md", "text/markdown", "UTF8", entry, AgentExecutionCodec.digest(entry), entry.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                        new SkillActivation.Resource("references/guide.md", "text/markdown", "UTF8", reference, AgentExecutionCodec.digest(reference), reference.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)),
                Optional.empty(), List.of(), 5);
        var mount = new SkillActivation.Mount("skill:guide", new CapabilityRef(CapabilityType.SKILL, "guide", "v1"), SkillActivation.Mode.ON_DEMAND, 1_000, descriptor);
        var f = new Fixture(List.of(mount));
        var c = f.configuration;
        var modelTools = AgentExecutionProgram.modelTools(c.profile(), c.tools(), Map.of(c.tools().getFirst().target().handle().value(), "template"),
                c.knowledge(), c.skills(), c.workflows(), List.of());
        f.configuration = new AgentExecutionProgram.Configuration(c.profile(), c.target(), modelTools, c.tools(), c.budget(), c.capabilitySnapshot(),
                c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired());
        f.materials = f.program.open(f.run, f.context);
        var first = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); f.commit(first, f.model(new AgentAction.ActivateSkill("skill:guide"), 1));
        assertEquals(List.of("skill:guide"), f.materials.skills().activation().activeHandles());
        assertEquals(0, f.materials.usage().toolCalls()); assertEquals(1, f.materials.usage().businessModelCalls());
        var second = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials)); assertEquals(ExecutionStore.Kind.MODEL, second.action().kind());
        assertTrue(requestHistory(f,second).getLast().textContent().contains(entry));
        f.commit(second, f.model(new AgentAction.ReadSkillResource("skill:guide", "references/guide.md"), 2));
        assertEquals(2, f.materials.skills().loaded().size()); assertEquals(0, f.materials.usage().toolCalls());
        assertEquals(f.materials, new AgentExecutionProgram(f, f.core, Fixture.LIMITS).open(f.run, f.context));
        var third = assertInstanceOf(Step.Dispatch.class, f.program.next(f.run, f.materials));
        var messages = requestHistory(f,third);
        assertEquals("read_skill_resource", messages.get(messages.size() - 2).toolCalls().getFirst().wireName());
        assertTrue(messages.getLast().textContent().contains(reference));
    }

    /** 控制结果、工具实际输出和Store材料保存都是本组明确的单测事实，不证明外部依赖已通过。 */
    private static ExecutionObservation.Fact observation(List<ExecutionStore.Event> events, ExecutionObservation.Kind kind) {
        return events.stream().filter(event -> ExecutionObservation.EVENT_TYPE.equals(event.type()))
                .map(event -> ExecutionObservation.decode(event.data(), Fixture.LIMITS))
                .filter(fact -> fact.kind() == kind).findFirst().orElseThrow();
    }

    private static void assertResumedObservation(Step.Dispatch dispatch, ExecutionObservation.Kind kind, InputId input) {
        var fact = observation(dispatch.events(), kind);
        assertEquals(input.value(), fact.inputId());
        assertEquals(null, fact.traceId(), "新输入未记录技术Trace时不能复用首次Run的Trace");
        assertEquals(null, fact.spanId());
        assertEquals(null, fact.elapsedNanos());
    }

    private static final class Fixture implements AgentExecutionProgram.Sources {
        private boolean knowledgeAllowed = true;
        private List<com.skillforge.runtime.api.KnowledgeSourceVersion> checkedKnowledge = List.of();
        @Override public boolean knowledgeSourcesAllowed(ExecutionStore.Run run, List<com.skillforge.runtime.api.KnowledgeSourceVersion> sources) {
            checkedKnowledge = List.copyOf(sources);
            return knowledgeAllowed;
        }
        private static final CodecLimits LIMITS = new CodecLimits(1_000_000, 100_000, 128);
        private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();
        private final CoreRuntime core = new CoreRuntime(LIMITS, payloads);
        private final AgentExecutionCodec codec = new AgentExecutionCodec(LIMITS);
        private final TypedPayload frozenInput = new TypedPayload("fixture.frozen-input.v1", "{\"text\":\"真实输入\",\"extraParameters\":{\"business\":\"original\"}}");
        private final SessionToolDirectory.Entry entry;
        private AgentExecutionProgram.Configuration configuration;
        private final AgentExecutionProgram program = new AgentExecutionProgram(this, core, LIMITS);
        private final List<Material> saved = new ArrayList<>();
        private final Map<String, TypedPayload> failureEvidence = new java.util.HashMap<>();
        private Material context;
        private ExecutionStore.Run run;
        private AgentExecutionProgram.Materials materials;
        private int configurations;
        private int actorChanges;
        private VerifiedIdentity activeActor = SessionFixtures.ADMISSION;
        private int recoveryReads;
        private int measurements;
        private boolean contentMeasurements;
        private boolean toolSensitiveMeasurements;
        private long admittedClosingInput, admittedBusinessInput;
        private int mediaPreparations;
        private int timeSamples;
        private Duration elapsed = Duration.ofSeconds(1);
        private java.time.Clock clock=java.time.Clock.fixed(SessionFixtures.NOW,java.time.ZoneOffset.UTC);
        private List<ModelMessage> lastMeasuredMessages=List.of();
        private List<ModelTool> lastMeasuredTools=List.of();
        private Adoption<AgentExecutionProgram.Materials> lastAdoption;
        private Material acceptedResponse;
        private Optional<MediaRef> preparedVariant = Optional.empty();
        private Optional<AgentExecutionOutputs.Policy> output=Optional.empty();
        private Optional<ExecutionBudgetPort.Snapshot> budgetObservation=Optional.empty();
        private int budgetReads;
        private boolean currentRead;
        private boolean failCurrentRead;
        @Override public boolean currentRead(SessionToolDirectory.Entry frozen, TypedPayload arguments) {
            if (failCurrentRead) throw new AssertionError("restoration must use frozen qualification");
            assertEquals(SessionToolDirectory.requirePrepared(configuration.tools(), frozen.target().handle().value()), frozen);
            return currentRead;
        }
        @Override public Optional<ExecutionBudgetPort.Snapshot> budgetSnapshot(ExecutionStore.Run run) { budgetReads++;return budgetObservation; }
        private int outputReads,outputAdmissions;
        private boolean outputBudgetInsufficient;
        private boolean outputSwitchesToClosing;
        private RuntimeException outputPreparationFailure;

        @Override public Optional<AgentExecutionOutputs.Policy> prepareOutput(ExecutionStore.Run run,AgentExecutionProgram.Configuration configuration) {
            outputReads++;
            if(outputPreparationFailure!=null)throw outputPreparationFailure;
            return output;
        }
        @Override public boolean admitOutput(ExecutionStore.Run run,AgentExecutionProgram.Configuration configuration,AgentExecutionOutputs.Policy policy,long input,long outputTokens,long closingInputTokens) {
            admittedClosingInput=closingInputTokens;admittedBusinessInput=input;
            outputAdmissions++;if(outputBudgetInsufficient)throw new AgentExecutionProgram.OutputBudgetInsufficient(new ExecutionBudgetPort.ClosingFailure(ExecutionBudgetPort.Phase.BUSINESS,
                    List.of(new ExecutionBudgetPort.ClosingShortfall(ExecutionBudgetPort.BalanceScope.ROOT,"ACTIVE_MILLIS",240_000,180_000,60_000,0,0,180_000)),
                    new ExecutionBudgetPort.ClosingTimeCalculation(2,120_000,120_000,120_000,240_000)));return outputSwitchesToClosing || policy.closing();
        }

        /** 装配真实schema值和规范codec；token量是本纯程序用例的受控计量，不宣称是真实tokenizer性能。 */
        private Fixture() {
            this(new TypedPayload("json-schema", "{}"));
        }
        private Fixture(int toolCalls) { this(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), false, List.of(), PlanPort.State.absent(), false, Optional.empty(), toolCalls); }
        private Fixture(ResponseFormat format, ReasoningPolicy reasoning, boolean withTools) {
            this(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), false, List.of(), PlanPort.State.absent(), false, Optional.empty(), 10, format, reasoning, withTools);
        }

        /** 使用实际冻结业务输出schema覆盖同一生产安全投影边界。 */
        private Fixture(TypedPayload outputSchema) {
            this(outputSchema, Optional.empty());
        }

        private Fixture(KnowledgeBudget budget) { this(new TypedPayload("json-schema", "{}"), Optional.of(budget)); }

        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget) { this(outputSchema, knowledgeBudget, List.of()); }

        private Fixture(List<SkillActivation.Mount> skills) { this(new TypedPayload("json-schema", "{}"), Optional.empty(), skills); }

        private Fixture(boolean delegationEnabled) { this(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), delegationEnabled); }
        private Fixture(MediaRef source) { this(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), false, List.of(), PlanPort.State.absent(), false, Optional.of(source)); }
        private Fixture(PlanPort.State state, boolean required) { this(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), true, List.of(), state, required, Optional.empty()); }
        private Fixture(AgentExecutionProgram.WorkflowBinding workflow) { this(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), false, List.of(workflow), PlanPort.State.absent(), false, Optional.empty()); }

        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget, List<SkillActivation.Mount> skills) {
            this(outputSchema, knowledgeBudget, skills, false);
        }

        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget, List<SkillActivation.Mount> skills, boolean delegationEnabled) {
            this(outputSchema, knowledgeBudget, skills, delegationEnabled, List.of(), PlanPort.State.absent(), false, Optional.empty());
        }
        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget, List<SkillActivation.Mount> skills, boolean delegationEnabled,
                List<AgentExecutionProgram.WorkflowBinding> workflows, PlanPort.State initialPlanState, boolean planRequired, Optional<MediaRef> image) {
            this(outputSchema, knowledgeBudget, skills, delegationEnabled, workflows, initialPlanState, planRequired, image, 10);
        }
        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget, List<SkillActivation.Mount> skills, boolean delegationEnabled,
                List<AgentExecutionProgram.WorkflowBinding> workflows, PlanPort.State initialPlanState, boolean planRequired, Optional<MediaRef> image, int maxToolCalls) {
            this(outputSchema, knowledgeBudget, skills, delegationEnabled, workflows, initialPlanState, planRequired, image, maxToolCalls,
                    new ResponseFormat.NativeConversation(), ReasoningPolicy.DEFAULT, true);
        }
        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget, List<SkillActivation.Mount> skills, boolean delegationEnabled,
                List<AgentExecutionProgram.WorkflowBinding> workflows, PlanPort.State initialPlanState, boolean planRequired, Optional<MediaRef> image, int maxToolCalls,
                ResponseFormat format, ReasoningPolicy reasoning, boolean withTools) {
            this(outputSchema, knowledgeBudget, skills, delegationEnabled, workflows, initialPlanState, planRequired, image,
                    maxToolCalls, format, reasoning, withTools, 10);
        }
        private static Fixture withBusinessModelCalls(int maxBusinessModelCalls) {
            return new Fixture(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), false, List.of(),
                    PlanPort.State.absent(), false, Optional.empty(), 10, new ResponseFormat.NativeConversation(),
                    ReasoningPolicy.DEFAULT, true, maxBusinessModelCalls);
        }
        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget, List<SkillActivation.Mount> skills, boolean delegationEnabled,
                List<AgentExecutionProgram.WorkflowBinding> workflows, PlanPort.State initialPlanState, boolean planRequired, Optional<MediaRef> image, int maxToolCalls,
                ResponseFormat format, ReasoningPolicy reasoning, boolean withTools, int maxBusinessModelCalls) {
            this(outputSchema, knowledgeBudget, skills, delegationEnabled, workflows, initialPlanState, planRequired, image,
                    format, reasoning, withTools, new LoopBudget.Enforced(maxBusinessModelCalls, maxToolCalls, 10,
                    100_000, 100_000, 200_000, Duration.ofMinutes(5)), OptionalInt.of(100));
        }
        private static Fixture observeOnly(OptionalInt outputTokens) {
            return new Fixture(new TypedPayload("json-schema", "{}"), Optional.empty(), List.of(), false, List.of(),
                    PlanPort.State.absent(), false, Optional.empty(), new ResponseFormat.NativeConversation(),
                    ReasoningPolicy.DEFAULT, true, new LoopBudget.ObserveOnly(), outputTokens);
        }
        private Fixture(TypedPayload outputSchema, Optional<KnowledgeBudget> knowledgeBudget, List<SkillActivation.Mount> skills, boolean delegationEnabled,
                List<AgentExecutionProgram.WorkflowBinding> workflows, PlanPort.State initialPlanState, boolean planRequired, Optional<MediaRef> image,
                ResponseFormat format, ReasoningPolicy reasoning, boolean withTools, LoopBudget cumulativeBudget, OptionalInt outputTokens) {
            var formats=format instanceof ResponseFormat.JsonObject?Set.of(ResponseFormatKind.TEXT,ResponseFormatKind.JSON_OBJECT):Set.of(ResponseFormatKind.TEXT);
            var target = new FrozenModelTarget(new ModelBindingRef(SessionFixtures.TENANT, "binding", 1), "model",
                    new ModelCapabilitySnapshot(formats, false, true, new ResultRef("model-capabilities"),
                            new ModelImagePolicy(true, true, 10, 1_000, 10_000, 1_000, 512, 0, 0, "image.v1")), Duration.ofSeconds(30), new ResultRef("model-resolution"));
            var profile = new SessionPreparations.Profile(new ResultRef("profile"), SessionPreparations.PROFILE_VERSION, SessionPreparations.AGENT_INPUT_RENDERER_VERSION,
                    SessionAdmissionFacts.ContextKind.AGENT_INPUT, new SessionPrompt(List.of(
                            new SessionPrompt.Module("system", SessionPrompt.Type.HOST, "1", "系统指令"),
                            new SessionPrompt.Module("plan-policy", SessionPrompt.Type.PLAN_POLICY, "1", "冻结规划政策：先只读调查，再提交精确计划审批"))), target.binding(),
                    new RequestedModelStrategy("strategy", "1"), new ResponseContract(format, EnforcementLevel.STRICT,
                            format instanceof ResponseFormat.NativeConversation ? "native" : "fixture-response"),
                    new GenerationOptions(outputTokens, OptionalDouble.empty()), reasoning, PortalCallKind.MODEL_GENERATE,
                    new ResultRef("tokenizer"), SessionContextPolicy.defaults(), 100_000, 100_000, 100, delegationEnabled, Set.of(InboundKind.MESSAGE));
            var tool = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:template"), new CapabilityRef(CapabilityType.TOOL, "template", "1"), "deployment", 1,
                    new TypedPayload("json-schema", "{}"), outputSchema, ToolEffect.READ_ONLY, true, Duration.ofSeconds(1), true,
                    ToolPortal.ToolConfirmationPolicy.NEVER, Set.of("secret"), Set.of(), !"{}".equals(outputSchema.canonicalJson()));
            var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.TEST_MOCK, "fixture", "template_render");
            entry = new SessionToolDirectory.Entry(tool, binding,
                    new SessionToolDirectory.Discovery(binding.serverIdentity(), binding.serverIdentity() + "/" + binding.toolName(),
                            binding.toolName(), Optional.empty(), "模板"), true);
            var knowledgePolicies = knowledgeBudget.map(budget -> List.of(new AgentExecutionProgram.KnowledgeBinding(
                    new KnowledgePortal.FrozenKnowledgeTarget(new KnowledgePortal.KnowledgeHandle("knowledge:docs"), new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "docs", "version-1"),
                            "docs", 1, Set.of(new KnowledgeSourceVersion("source-1", "version-1")), Set.of(KnowledgeOperatorType.KEYWORD), Duration.ofSeconds(1)),
                    KnowledgeMode.GROUNDING, "answer", budget, SessionFixtures.NOW, Optional.empty(), true))).orElse(List.of());
            var modelTools = new ArrayList<>(List.of(new ModelTool("sf_template", "tool:template", "真实模板", tool.inputSchema(), false, ModelToolKind.TOOL_CALL, true)));
            if (!knowledgePolicies.isEmpty()) modelTools.addAll(SessionNativeConversation.actionTools(false, Set.of("knowledge_query")).stream().filter(value -> value.wireName().equals("knowledge_query")).toList());
            if (!skills.isEmpty()) modelTools.addAll(SessionNativeConversation.actionTools(false, Set.of("activate_skill", "read_skill_resource")).stream().filter(value -> Set.of("use_skill", "read_skill_resource").contains(value.wireName())).toList());
            modelTools.addAll(SessionNativeConversation.actionTools(delegationEnabled, Set.of("ask_user")));
            if (!workflows.isEmpty()) modelTools.addAll(SessionNativeConversation.actionTools(false, Set.of("dispatch")).stream().filter(value -> value.wireName().equals("run_workflow")).toList());
            if (!withTools) modelTools.clear();
            var initialContents = new ArrayList<ModelContent>(); initialContents.add(new ModelContent.Text("请渲染真实结果")); image.ifPresent(value -> initialContents.add(new ModelContent.Image(value, ImageDetail.HIGH)));
            configuration = new AgentExecutionProgram.Configuration(profile, target,
                    modelTools, withTools ? List.of(entry) : List.of(),
                    cumulativeBudget, SessionFixtures.CAPABILITY,
                    new ResultRef("original-capability-snapshot"), List.of(new ModelMessage("input", ModelRole.USER, initialContents)), SessionFixtures.ADMISSION, knowledgePolicies, skills,
                    skills.stream().map(SkillActivation.Mount::capability).collect(java.util.stream.Collectors.toUnmodifiableSet()), workflows, initialPlanState, planRequired);
            var input = new Material(new ResultRef("durable-input"), codec.encodeInput(configuration.initialMessages(), frozenInput)); saved.add(input);
            context = program.initialContext(input.reference()); saved.add(context);
            run = run(new ExecutionStore.Progress(0, 0, 0, context.reference()), 1);
            materials = program.open(run, context);
        }

        /** 一次读取原输入，和上下文base中真实inputRef严格绑定。 */
        private static Fixture authorizedWorkflow() {
            var f=new Fixture(workflowBinding());var c=f.configuration;
            f.configuration=new AgentExecutionProgram.Configuration(c.profile(),c.target(),List.of(),List.of(),c.budget(),c.capabilitySnapshot(),
                    c.capabilityRef(),c.initialMessages(),c.actor(),List.of(),List.of(),c.availableCapabilities(),c.workflows(),c.initialPlanState(),false,
                    Optional.of(new AgentExecutionProgram.AuthorizedWorkflowInvocation("test-intent",c.workflows().getFirst().target())));
            f.materials=f.program.open(f.run,f.context);return f;
        }
        @Override public AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run, ResultRef input) {
            configurations++; assertEquals(new ResultRef("durable-input"), input);
            assertEquals(configuration.initialMessages(), codec.decodeInput(saved.stream().filter(value -> value.reference().equals(input)).findFirst().orElseThrow().content()));
            return configuration;
        }

        private void usePrefix(ModelPort.PromptPrefixCapabilities prefix) {
            var c = configuration; var original = c.target(); var caps = original.capabilities();
            var target = new FrozenModelTarget(original.binding(), original.effectiveModelId(), new ModelCapabilitySnapshot(
                    caps.responseFormats(), caps.streaming(), caps.reasoning(), caps.capabilityFactRef(), caps.imagePolicy(), prefix),
                    original.timeout(), original.resolutionRef());
            configuration = new AgentExecutionProgram.Configuration(c.profile(), target, c.modelTools(), c.tools(), c.budget(), c.capabilitySnapshot(),
                    c.capabilityRef(), c.initialMessages(), c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(), c.workflows(), c.initialPlanState(), c.planRequired(), c.initialIntent());
            materials = program.open(run, context);
        }

        private void usePrompt(SessionPrompt prompt) {
            var c = configuration; var p = c.profile();
            var profile = new SessionPreparations.Profile(p.reference(), p.version(), p.rendererVersion(), p.contextKind(), prompt,
                    p.binding(), p.strategy(), p.response(), p.generation(), p.reasoning(), p.callKind(), p.tokenizerProfileRef(),
                    p.contextPolicy(), p.maxTokens(), p.maxCharacters(), p.maxHistoryEvents(), p.delegationEnabled(), p.allowedInputs());
            var messages = new ArrayList<ModelMessage>(); messages.add(new ModelMessage("system", ModelRole.SYSTEM, prompt.render()));
            messages.addAll(c.initialMessages());
            configuration = new AgentExecutionProgram.Configuration(profile, c.target(), c.modelTools(), c.tools(), c.budget(),
                    c.capabilitySnapshot(), c.capabilityRef(), messages, c.actor(), c.knowledge(), c.skills(), c.availableCapabilities(),
                    c.workflows(), c.initialPlanState(), c.planRequired(), c.initialIntent());
            var input = new Material(new ResultRef("durable-input"), codec.encodeInput(messages, frozenInput));
            saved.removeIf(material -> material.reference().equals(input.reference())); saved.add(input);
            materials = program.open(run, context);
        }

        /** 对应生产宿主帧的本地身份同步，不查询材料或重新打开配置。 */
        @Override public void actorChanged(ExecutionStore.Run run, VerifiedIdentity actor) {
            assertEquals(run.actorVerificationRef(), actor.verificationRef()); actorChanges++; activeActor = actor;
        }

        @Override public Optional<TypedPayload> toolFailureEvidence(ExecutionStore.Run run, com.skillforge.runtime.core.AgentExecutionLoop.ToolSource source, PortFailure failure) {
            return Optional.ofNullable(failureEvidence.get(failure.resultRef().value()));
        }

        /** 仅用于精确引用恢复，正常连续推进应为零次读取。 */
        @Override public Material material(ExecutionStore.Run run, ResultRef reference) {
            recoveryReads++; return saved.stream().filter(value -> value.reference().equals(reference)).findFirst().orElseThrow();
        }

        @Override public List<Optional<Material>> materials(ExecutionStore.Run run, List<ResultRef> references) {
            recoveryReads++;
            return references.stream().map(reference -> saved.stream()
                    .filter(value -> value.reference().equals(reference)).findFirst()).toList();
        }

        /** 明确的纯计量夹具；真实装配复用已有tokenizer。 */
        @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages, List<com.skillforge.runtime.api.port.ModelPort.ModelTool> tools) {
            measurements++;
            lastMeasuredMessages=List.copyOf(messages);
            lastMeasuredTools=List.copyOf(tools);
            var profile = new SessionTokenCounter.Profile(configuration.profile().tokenizerProfileRef(), "1", "fixture", "fixture", configuration.target(),
                    new SessionTokenCounter.RoleCosts(0, 0, 0, 0), 0, 0, 100, 1_000_000);
            var counts=contentMeasurements?messages.stream().map(message->(long)message.accountingText().codePointCount(0,message.accountingText().length())).toList():java.util.Collections.nCopies(messages.size(),1L);
            return new SessionTokenCounter.Measurement(profile, messages, counts, counts.stream().mapToLong(Long::longValue).sum(), 0, 0,
                    tools, toolSensitiveMeasurements ? tools.size()*1000L : tools.isEmpty() ? 0 : 1);
        }

        /** 本纯程序用例提供显式已确认活动时间，不读取墙钟或数据库。 */
        @Override public Duration elapsed(ExecutionStore.Run run) { return elapsed; }
        @Override public AgentExecutionTime.Sample sampleModelTime(ExecutionStore.Run run) {timeSamples++;return AgentExecutionTime.sample(clock);}

        /** 纯程序夹具显式登记每个图片的恒等映射；不声称真实媒体准备或外部存储验收。 */
        @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(ExecutionStore.Run run, AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages) {
            mediaPreparations++;
            var mappings = new ArrayList<AgentExecutionModelMedia.MediaTransformation>(); var prepared = new ArrayList<ModelMessage>();
            for (int message = 0; message < messages.size(); message++) {
                var original = messages.get(message); var contents = new ArrayList<>(original.contents());
                for (int part = 0; part < contents.size(); part++) if (contents.get(part) instanceof ModelContent.Image image) {
                    var variant = preparedVariant.orElse(image.media()); mappings.add(new AgentExecutionModelMedia.MediaTransformation(message, part, image.media(), variant));
                    contents.set(part, new ModelContent.Image(variant, image.detail()));
                }
                prepared.add(new ModelMessage(original.messageId(), original.role(), contents, original.toolCalls(), original.toolCallId(), original.reasoningContent(), original.protocolState()));
            }
            return new AgentExecutionModelMedia.Prepared(prepared, mappings);
        }

        /** Provider原生回合持有真实callId、正文和原始参数；Session负责解释动作。 */
        private CapabilityResult batchModel(List<AgentAction.ToolCall> calls, long ordinal) {
            var parts=new ArrayList<AssistantContent>();parts.add(new AssistantContent.Text("原批次说明"));
            for(int index=0;index<calls.size();index++) {
                var call=calls.get(index);var wire=configuration.modelTools().stream().filter(tool->tool.toolHandle().equals(call.toolHandle())).findFirst().orElseThrow().wireName();
                parts.add(new AssistantContent.ToolCall("provider-call:"+ordinal+":"+(index+1),wire,call.arguments().canonicalJson()));
            }
            return nativeModel(new AssistantTurn(parts),"tool_calls",ordinal,Optional.of("原批次推理"));
        }

        private CapabilityResult nativeModel(AssistantTurn turn,String finish,long ordinal,Optional<String> reasoning) {
            return nativeModel(turn, finish, ordinal, reasoning, Optional.empty());
        }

        private CapabilityResult nativeModel(AssistantTurn turn,String finish,long ordinal,Optional<String> reasoning,Optional<ModelPort.NativeProtocolState> protocolState) {
            var actual=new ModelResult.Completed(new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA,SessionRuntimePayloadJson.write(turn.content())),"model",
                    new ModelUsage(OptionalLong.of(3),OptionalLong.of(2)),finish,new ResultRef("model-business-result-"+ordinal),reasoning,Optional.of(turn),protocolState);
            return new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,new Material(new ResultRef("result-"+ordinal),codec.encodeOutcome(core.encodeOutcome(PortalCallKind.MODEL_GENERATE,actual))),
                    Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.reported(actual.usage().inputTokens(),actual.usage().outputTokens()));
        }

        /** 每种控制参数按公开原生函数schema生成，内部Respond只由普通正文产生。 */
        private CapabilityResult model(AgentAction action, long ordinal) {
            if(action instanceof AgentAction.Respond response) {
                String text=response.output();
                for(var id:response.evidenceItemIds()) {
                    var item=materials.knowledge().stream().flatMap(value->value.bundle().items().stream()).filter(value->value.itemId().equals(id)).findFirst().orElseThrow();
                    text+="[[cite:"+NativeCitations.citationId(run.identity().runRef(),item)+"]]";
                }
                return nativeModel(new AssistantTurn(List.of(new AssistantContent.Text(text))),"stop",ordinal,Optional.of("原推理"));
            }
            var document=(com.fasterxml.jackson.databind.node.ObjectNode)SessionRuntimePayloadJson.parse(new AgentDecisionDecoder(LIMITS).encode(action).canonicalJson(),LIMITS);
            String type=document.remove("type").textValue();String wire;
            if(action instanceof AgentAction.ToolCall call) {
                wire=configuration.modelTools().stream().filter(tool->tool.toolHandle().equals(call.toolHandle())).findFirst().orElseThrow().wireName();
                document=(com.fasterxml.jackson.databind.node.ObjectNode)document.get("arguments");
            } else if(action instanceof AgentAction.Delegate delegate) {
                var command=delegate.command();document.remove("operation");
                wire=com.skillforge.runtime.api.port.NativeAgentTools.wireName("delegate",Optional.of(command.operation().name().toLowerCase(java.util.Locale.ROOT)));
                switch(command.operation()) {
                    case START -> {document.remove("task");document.put("instruction",command.task().orElseThrow().objective());}
                    case WAIT -> document.set("ids",document.remove("delegation_ids"));
                    case GET,SEND,CANCEL -> {document.remove("delegation_ids");if(!command.delegationIds().isEmpty())document.put("id",command.delegationIds().getFirst());}
                    default -> { }
                }
            } else if(action instanceof AgentAction.Plan plan) {
                document.remove("operation");wire=com.skillforge.runtime.api.port.NativeAgentTools.wireName("plan",Optional.of(plan.command().operation().name().toLowerCase(java.util.Locale.ROOT)));
            } else wire=com.skillforge.runtime.api.port.NativeAgentTools.wireName(type,Optional.empty());
            return nativeModel(new AssistantTurn(List.of(new AssistantContent.Text("原模型说明"),new AssistantContent.ToolCall("provider-call:"+ordinal,wire,SessionRuntimePayloadJson.write(document)))),"tool_calls",ordinal,Optional.of("原推理"));
        }

        /** 私有ToolOutcome和安全投影分别编码，使用同一生产safeToolOutput算法。 */
        private CapabilityResult tool(Step.Dispatch dispatch, String observation) {
            var request = codec.decodeTool(dispatch.action().request().content());
            var actual = new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, observation), new ResultRef("tool-business-result"), List.of());
            var raw = core.portalResults(payloads, payloads, payloads).encodeTool(PortalCallKind.TOOL_INVOKE, actual);
            return new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED, new Material(new ResultRef("result-" + dispatch.action().ordinal()), codec.encodeOutcome(raw)),
                    codec.safeToolFeedback(request, actual).map(value -> new Material(new ResultRef("feedback-" + dispatch.action().ordinal()), value)), Optional.empty(), ExecutionBudgetPort.Usage.unreported());
        }

        private CapabilityResult inputRequired(String suffix, String state) {
            var actual = new ToolOutcome.InputRequired(Map.of("browser-connection", new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE,
                    new TypedPayload("browser-request.v1", "{\"title\":\"选择浏览器\"}"))), Optional.of(state));
            return new CapabilityResult(ExecutionStore.ResultKind.INPUT_REQUIRED,
                    new Material(new ResultRef("wait-result-" + suffix), codec.encodeOutcome(core.portalResults(payloads, payloads, payloads).encodeTool(PortalCallKind.TOOL_INVOKE, actual))),
                    Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported());
        }

        /** Host所属领域真实回执与安全反馈各保存一份，不能伪装PortalOutcomeSnapshot。 */
        private CapabilityResult delegation(Step.Dispatch dispatch, TypedPayload value, Optional<SessionExecutionDelegations.Dependency> dependency) {
            var content = new SessionExecutionDelegations(LIMITS).encodeResult(new SessionExecutionDelegations.Result(value, dependency));
            return new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED, new Material(new ResultRef("delegation-result-" + dispatch.action().ordinal()), content),
                    Optional.of(new Material(new ResultRef("delegation-feedback-" + dispatch.action().ordinal()), value)), Optional.empty(), ExecutionBudgetPort.Usage.unreported());
        }
        private CapabilityResult workflow(Step.Dispatch dispatch, WorkflowResult result, Optional<ExecutionStore.WorkflowWait> waiting) {
            return workflow(dispatch, result, waiting, Optional.empty());
        }
        private CapabilityResult workflow(Step.Dispatch dispatch, WorkflowResult result, Optional<ExecutionStore.WorkflowWait> waiting, Optional<SessionExecutionWorkflows.HumanInteraction> human) {
            var workflows = new SessionExecutionWorkflows(LIMITS); var request = workflows.decode(dispatch.action().request().content());
            var raw = codec.encodeOutcome(core.portalResults(payloads, payloads, payloads).encodeWorkflow(request.callKind(), result)); var content = workflows.encodeResult(new SessionExecutionWorkflows.Result(raw, waiting, human));
            var kind = result instanceof WorkflowResult.Failed ? ExecutionStore.ResultKind.FAILED : result instanceof WorkflowResult.OutcomeUnknown ? ExecutionStore.ResultKind.UNKNOWN : ExecutionStore.ResultKind.SUCCEEDED;
            return new CapabilityResult(kind, new Material(new ResultRef("workflow-result:" + dispatch.attemptId()), content), workflows.feedback(result).map(value -> new Material(new ResultRef("workflow-feedback:" + dispatch.attemptId()), value)), Optional.empty(), ExecutionBudgetPort.Usage.unreported());
        }

        private EvidenceBundle bundle(AgentExecutionCodec.KnowledgeCallRequest call) {
            var budget = call.request().budget(); var step = new KnowledgeStep("step-1", ((com.skillforge.runtime.api.KnowledgeOperation.Search) call.request().operation()).query(), Set.of("source-1"), KnowledgeOperatorType.KEYWORD, List.of(), budget);
            var selected = new EvidenceItem("actual-selected", "source-1", "version-1", KnowledgeOperatorType.KEYWORD,
                    new TypedPayload("skillforge.runtime.knowledge.chunk.v1", "{\"text\":\"ACTUAL_KNOWLEDGE_TEXT\"}"), List.of("private:original-doc"), List.of("origin", "chunk"), 0.8, "tenant");
            var excluded = new EvidenceItem("unselected", "source-1", "version-1", KnowledgeOperatorType.KEYWORD,
                    new TypedPayload("skillforge.runtime.knowledge.chunk.v1", "{\"text\":\"unselected-private-text\"}"), List.of(), List.of(), 0.2, "tenant");
            return new EvidenceBundle("actual-bundle", call.request().knowledgeRequestId(), new KnowledgePlan("plan-1", call.request().knowledgeRequestId(), List.of(step), "budget", "actual-planner-1"),
                    List.of(selected, excluded), List.of(List.of("actual-selected", "unselected")), EvidenceSufficiency.PARTIAL,
                    List.of(new KnowledgeTraceStep("step-1", "selection", List.of("actual-selected", "unselected"), List.of("actual-selected"), 2, 1)), 7, SessionFixtures.NOW, java.util.Optional.empty());
        }

        private CapabilityResult knowledge(EvidenceBundle bundle) {
            var outcome = new KnowledgePortal.KnowledgeOutcome.Retrieved(bundle, new ResultRef("knowledge-business-result"));
            return new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED, new Material(new ResultRef("knowledge-result:" + bundle.bundleId()), codec.encodeOutcome(core.portalResults(payloads, payloads, payloads).encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, outcome))),
                    Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported());
        }

        /** 只模拟已经由Store受理的响应事实；身份与CAS真实校验由PostgreSQL测试拥有。 */
        private ExecutionStore.Waiting accept(Map<String, ToolInputResponse> responses) {
            var pending = materials.pendingTool().orElseThrow();
            var requested = new java.util.TreeMap<String, ToolInputMethod>(); pending.input().inputRequests().forEach((key, request) -> requested.put(key, request.method()));
            return accept(pending.waiting(), ExecutionStore.WaitKind.TOOL, requested, codec.encodeToolResponses(responses));
        }

        private ExecutionStore.Waiting acceptHuman(String response) {
            return accept(materials.pendingHuman().orElseThrow().waiting(), ExecutionStore.WaitKind.HUMAN, Map.of(), codec.encodeHumanResponse(response));
        }

        private ExecutionStore.Waiting accept(ExecutionStore.WaitRef reference, ExecutionStore.WaitKind kind, Map<String, ToolInputMethod> requested, TypedPayload response) {
            return accept(reference, kind, requested, response, Optional.empty());
        }

        private ExecutionStore.Waiting accept(ExecutionStore.WaitRef reference, ExecutionStore.WaitKind kind, Map<String, ToolInputMethod> requested, TypedPayload response, Optional<ExecutionStore.InternalWait> internal) {
            String suffix = reference.action().actionId() + ":" + reference.generation();
            acceptedResponse = new Material(new ResultRef("accepted-response-" + suffix), response); saved.add(acceptedResponse);
            var accepted = new ExecutionStore.AcceptedResponse(new InputId("response-" + suffix), acceptedResponse.reference(), "accepted-fingerprint", run.actorVerificationRef(), run.revision() + 1);
            var waiting = new ExecutionStore.Waiting(reference, kind, run.identity().runRef(), "source-attempt-" + suffix, requested, internal, Optional.of(accepted), Optional.empty(), OptionalLong.empty());
            run = new ExecutionStore.Run(run.identity(), run.configuration(), RunStatus.RUNNING, run.revision() + 1, run.fence(), false, ExecutionStore.Phase.READY,
                    run.progress(), Optional.of(new LeaseFence(SessionFixtures.TENANT, "resumed", "worker", run.fence(), SessionFixtures.NOW.plusSeconds(60))),
                    Optional.of(reference.action()), Optional.empty(), Optional.of(reference), run.actorVerificationRef(), Optional.empty(), run.createdAt(), SessionFixtures.NOW.plusNanos(run.revision() + 1));
            return waiting;
        }

        private void commitHuman(ExecutionStore.Waiting waiting) {
            var adopted = program.acceptResponse(run, materials, waiting, acceptedResponse); saved.add(adopted.context());
            context = adopted.context(); run = run(adopted.next(), run.revision() + 1); materials = adopted.materials();
        }
        private void commitSupplement(String id, String text, long ordinal) {
            var input = new InputId("supplement-" + id); var value = new AgentExecutionSteering(LIMITS).encode(input, text); var content = new Material(new ResultRef("supplement-content-" + id), value); saved.add(content);
            var fact = new ExecutionStore.SupplementalInput(run.identity().runRef(), input, content.reference(), AgentExecutionCodec.digest(value.schemaId() + "\0" + value.canonicalJson()), run.actorVerificationRef(), ordinal, OptionalLong.empty());
            var adopted = program.acceptInput(run, materials, fact, content); saved.add(adopted.context()); context = adopted.context(); run = run(adopted.next(), run.revision() + 1); materials = adopted.materials();
        }
        private void commitPlan(Step.PlanLocal command, SessionExecutionPlans.Result result) {
            var content = new SessionExecutionPlans(LIMITS).encodeResult(result); var raw = new Material(new ResultRef("plan-result:" + command.sourceModelAction().actionId()), content);
            var adopted = program.planAdoption(run, materials, command, raw); lastAdoption = adopted; saved.add(command.request()); saved.add(raw); saved.add(adopted.context());
            context = adopted.context(); run = run(adopted.next(), run.revision() + 1); materials = adopted.materials();
            if (adopted.waiting().isPresent()) {
                var wait = adopted.waiting().orElseThrow().reference();
                run = new ExecutionStore.Run(run.identity(), run.configuration(), RunStatus.SUSPENDED, run.revision(), run.fence(), false, ExecutionStore.Phase.WAITING, run.progress(), Optional.empty(),
                        Optional.of(wait.action()), Optional.empty(), Optional.of(wait), run.actorVerificationRef(), Optional.empty(), run.createdAt(), run.updatedAt());
            }
        }

        /** 纯夹具保存整组真实材料后才采用完整回合，数据库原子性由Store集成测试证明。 */
        private void commitBatch(List<BatchCall> calls) {
            commitBatchOutcomes(calls.stream().map(call -> (BatchOutcome) new BatchOutcome.Actual(Integer.parseInt(codec.decodeTool(call.action().request().content()).callId().substring(codec.decodeTool(call.action().request().content()).callId().lastIndexOf(':') + 1)), call)).toList());
        }
        private void commitBatchOutcomes(List<BatchOutcome> calls) {
            var adopted = program.adoptBatch(run, materials, calls); lastAdoption = adopted;
            for (var outcome : calls) if (outcome instanceof BatchOutcome.Actual actual) { var call = actual.call(); saved.add(call.action().request()); saved.addAll(call.action().requestParts()); saved.add(call.result().result()); call.result().feedback().ifPresent(saved::add); }
            saved.add(adopted.context()); context = adopted.context(); run = run(adopted.next(), run.revision() + 1); materials = adopted.materials();
        }

        private BatchResponse batchResponse(BatchCall source, String actor) {
            saved.add(source.action().request()); saved.addAll(source.action().requestParts()); saved.add(source.result().result());
            var response = new Material(new ResultRef("batch-response-" + actor), codec.encodeToolResponses(Map.of("browser-connection", new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE, new TypedPayload("browser-response.v1", "{\"ok\":true}"))))); saved.add(response);
            long generation = codec.decodeTool(source.action().request().content()).continuation().map(value -> value.waiting().generation() + 1).orElse(1L);
            var waiting = new ExecutionStore.Waiting(new ExecutionStore.WaitRef(source.action().action(), generation), ExecutionStore.WaitKind.TOOL, run.identity().runRef(), source.attemptId(),
                    Map.of("browser-connection", ToolInputMethod.ELICITATION_CREATE), Optional.empty(), Optional.of(new ExecutionStore.AcceptedResponse(new InputId("batch-input-" + actor), response.reference(), "fingerprint", new ResultRef(actor), run.revision())), Optional.empty(), OptionalLong.empty());
            return new BatchResponse(waiting, response);
        }
        private void readyBatch() {
            run = new ExecutionStore.Run(run.identity(), run.configuration(), RunStatus.RUNNING, run.revision() + 1, run.fence(), false, ExecutionStore.Phase.READY, run.progress(), run.lease(), Optional.empty(),
                    Optional.of(new ExecutionStore.BatchRef(run.identity().runRef(), "source-model-attempt", 2)), Optional.empty(), run.actorVerificationRef(), Optional.empty(), run.createdAt(), run.updatedAt());
        }

        /** 模拟唯一Store.complete原子保存后才换Materials；不实现或冒充真实数据库事务。 */
        private void commit(Step.Dispatch dispatch, CapabilityResult result) {
            Adoption<AgentExecutionProgram.Materials> adopted = program.adopt(run, materials, dispatch.action(), result);
            lastAdoption = adopted;
            saved.add(dispatch.action().request()); saved.addAll(dispatch.action().requestParts()); saved.add(result.result()); result.feedback().ifPresent(saved::add); saved.add(adopted.context());
            context = adopted.context(); run = run(adopted.next(), run.revision() + 1); materials = adopted.materials();
            if (adopted.waiting().isPresent()) {
                var wait = adopted.waiting().orElseThrow().reference();
                run = new ExecutionStore.Run(run.identity(), run.configuration(), RunStatus.SUSPENDED, run.revision(), run.fence(), false, ExecutionStore.Phase.WAITING,
                        run.progress(), Optional.empty(), Optional.of(wait.action()), Optional.empty(), Optional.of(wait), run.actorVerificationRef(), Optional.empty(), run.createdAt(), run.updatedAt());
            }
        }

        /** 合法持久状态夹具，真正租约和并发由PostgreSQL集成另行验证。 */
        private static ExecutionStore.Run run(ExecutionStore.Progress progress, long revision) {
            return new ExecutionStore.Run(SessionFixtures.IDENTITY, new ResultRef("profile"), RunStatus.RUNNING, revision, 1, false, ExecutionStore.Phase.READY,
                    progress, Optional.of(new LeaseFence(SessionFixtures.TENANT, "claim", "worker", 1, SessionFixtures.NOW.plusSeconds(60))), Optional.empty(),
                    Optional.empty(), Optional.empty(), SessionFixtures.ADMISSION.verificationRef(), Optional.empty(), SessionFixtures.NOW, SessionFixtures.NOW.plusNanos(revision));
        }
    }
}
