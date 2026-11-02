package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import java.time.Instant;
import java.util.List;
import com.skillforge.institution.artifact.DeliverableContract.Format;
import com.skillforge.institution.artifact.DeliverableContract.Planned;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 直接验证宿主公开门禁，拒绝证据来自已提交能力结果和当前已授权 Workflow 卡。 */
class InstitutionOutputContractPublicTest {
    private static final String PRIVATE_VALUE = "private-platform-identity-684291";
    private static final String PRIVATE_PATH = "D:\\private-host\\artifact-root";
    private static final String BUSINESS_ID = "c67c9adf-f502-4f48-a028-ebf52aef900a";
    private static final TenantId TENANT = new TenantId("7");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT,"public-session"),new AgentId("101"),new EndUserId(8));
    private static final InputId INPUT = new InputId("public-input");
    private static final RunIdentity RUN = new RunIdentity(OWNER, new AgentId((OWNER).target().id()), INPUT, new RunRef(TENANT,"public-run"), new TraceEnvelope("public-trace","public-span",INPUT,Optional.empty()), new ResultRef("public-identity"), new RunRequester.EndUser((OWNER).requireUser()), java.util.Optional.empty(), new RunOriginRef((OWNER).sessionRef().tenantId(), "input:" + (INPUT).value()));
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT,"workflow-execution");
    private final ObjectMapper json = new ObjectMapper();
    private final PublicOutputGuard guard = mock(PublicOutputGuard.class);
    private final SessionLogPort log = mock(SessionLogPort.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final WorkflowInteractionView workflows = mock(WorkflowInteractionView.class);
    private final VerifiedIdentity actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT,OWNER.requireUser()),
                OWNER,Set.of(),1,new ResultRef("current-actor"));
    private final DeliverableService deliverables = mock(DeliverableService.class);
    private final InstitutionOutputContract contract = new InstitutionOutputContract(deliverables,
            mock(AgentRuntimeGoalDriver.class),guard,log,json,workflows,
            mock(com.skillforge.institution.artifact.ArtifactClosingBudget.class),
            new com.skillforge.institution.browser.BrowserClosingBudget((owner, agent) -> List.of()));

    @BeforeEach void scopeAndActor() {
        when(guard.capture(RUN)).thenReturn(new PublicOutputGuard.Scope(List.of(PRIVATE_VALUE,PRIVATE_PATH)));
        when(log.readAfter(any())).thenReturn(List.of());
    }

    /** 跨 Run 保留完整固定规则，仅后置可信事实随本轮输入变化。 */
    @Test void preparationKeepsOutputRulesStableAcrossCurrentInputs() {
        var nextInput = new InputId("next-public-input");
        var nextRun = new RunIdentity(OWNER, new AgentId((OWNER).target().id()), nextInput, new RunRef(TENANT, "next-public-run"), new TraceEnvelope("next-public-trace", "next-public-span", nextInput, Optional.empty()), new ResultRef("next-public-identity"), new RunRequester.EndUser((OWNER).requireUser()), java.util.Optional.empty(), new RunOriginRef((OWNER).sessionRef().tenantId(), "input:" + (nextInput).value()));
        when(deliverables.currentOutputPlanState(RUN)).thenReturn(Optional.of("finalizing"));
        when(deliverables.currentOutputPlanState(nextRun)).thenReturn(Optional.of("finalizing"));
        var first = contract.preparationHint(RUN).orElseThrow();
        var second = contract.preparationHint(nextRun).orElseThrow();
        assertEquals(first.stableInstructions(), second.stableInstructions());
        assertEquals(first.finalAnswerInstructions(), second.finalAnswerInstructions());
        assertTrue(first.finalAnswerInstructions().contains("宿主成果卡负责展示"));
        assertTrue(first.finalAnswerInstructions().contains("收到当前版本的正式发布回执后"));
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),
                contract.publicContentRejection(RUN, List.of(PRIVATE_VALUE)),
                "公开安全边界由真实宿主检查执行，不依赖最终正文重复内部原因码");
        assertFalse(first.finalAnswerInstructions().contains("plan_task_outputs"));
        assertFalse(first.finalAnswerInstructions().contains("finalize_task_outputs"));
        assertFalse(first.finalAnswerInstructions().contains(INPUT.value()));
        assertFalse(first.stableInstructions().contains(INPUT.value()));
        assertFalse(second.stableInstructions().contains(nextInput.value()));
        assertFalse(first.stableInstructions().contains("source_message_id"));
        assertFalse(first.stableInstructions().contains("decision_source"));
        assertTrue(first.runContext().contains(INPUT.value()));
        assertTrue(second.runContext().contains(nextInput.value()));
        assertNotEquals(first.runContext(), second.runContext());
    }

    /** 后置上下文只使用当前 Run 授权会话的正式基准和格式，固定规则不受标题影响。 */
    @Test void preparationIncludesCurrentRevisionAndReusableFormatFacts() {
        var current = new Planned("landing", "页面 {{literal}}", "website", "published-revision",
                List.of(new Format("html")));
        when(deliverables.currentOutputPlans(RUN)).thenReturn(List.of(current));
        var hint = contract.preparationHint(RUN).orElseThrow();
        assertTrue(hint.runContext().contains("\"base_revision_id\":\"published-revision\""));
        assertTrue(hint.runContext().contains("\"formats\":[\"html\"]"));
        assertFalse(hint.runContext().contains("decision_source"));
        assertTrue(hint.runContext().contains("页面 {{literal}}"));
        assertFalse(hint.stableInstructions().contains("published-revision"));
        org.mockito.Mockito.verify(deliverables).currentOutputPlans(RUN);
        assertTrue(hint.runContext().contains("本轮文件交付状态：absent"), "历史正式版本不代表本轮已冻结");
        for (String state : List.of("planned", "finalizing", "finalized", "abandoned")) {
            when(deliverables.currentOutputPlanState(RUN)).thenReturn(Optional.of(state));
            var updated = contract.preparationHint(RUN).orElseThrow();
            assertEquals(hint.stableInstructions(), updated.stableInstructions());
            assertEquals(hint.finalAnswerInstructions(), updated.finalAnswerInstructions());
            assertTrue(updated.runContext().contains("本轮文件交付状态：" + state));
            assertTrue(updated.runContext().contains("published-revision"));
            assertNotEquals(hint.runContext(), updated.runContext());
        }
    }

    @Test void committedToolOutputCanonicalJsonMarkersRejectCurrentRun() throws Exception {
        for(String field:List.of("error","delivery_error")) {
            var result=json.createObjectNode().put("success",false).put(field,PublicOutputGuard.REJECTION_CODE);
            when(log.readAfter(any())).thenReturn(List.of(event(1,RUN.runRef(),"agent.tool-result",toolOutput(result.toString()))));
            assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.terminalRejection(RUN));
        }
    }

    @Test void markersInOtherRunsOtherEventsAndNestedBusinessDataAreNotTerminalEvidence() throws Exception {
        String marker=json.createObjectNode().put("error",PublicOutputGuard.REJECTION_CODE).toString();
        var business=json.createObjectNode();business.set("business_response",json.readTree(marker));
        when(log.readAfter(any())).thenReturn(List.of(
                event(1,new RunRef(TENANT,"other-run"),"agent.tool-result",toolOutput(marker)),
                event(2,RUN.runRef(),"agent.observation",toolOutput(marker)),
                event(3,RUN.runRef(),"agent.tool-result",toolOutput(business.toString()))));
        assertTrue(contract.terminalRejection(RUN).isEmpty());
    }

    @Test void typedPresentationRejectsPrivateTextAndKeepsStructuredSelectorOutOfUserText() throws Exception {
        var safe=new ProcessPresentation("订单结果","已计算订单金额",List.of(new ProcessPresentation.Text("plain",
                "const response={artifact_id:'"+BUSINESS_ID+"',run_id:'order-20260930'};")),
                List.of(new ChatReferenceView("订单文件",new ChatInputReference.ArtifactFiles(
                        List.of(new ChatInputReference.ArtifactFile(PRIVATE_VALUE,"report.md"))))));
        assertTrue(contract.capabilityContentRejection(RUN,List.of(presentation(safe))).isEmpty());
        var unsafe=new ProcessPresentation("订单结果","已计算订单金额",List.of(new ProcessPresentation.Text("plain",PRIVATE_VALUE)),List.of());
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.capabilityContentRejection(RUN,List.of(presentation(unsafe))));
        var unsafeLabel=new ProcessPresentation("订单结果","已计算订单金额",List.of(),
                List.of(new ChatReferenceView(PRIVATE_VALUE,new ChatInputReference.ArtifactFiles(
                        List.of(new ChatInputReference.ArtifactFile("internal-selector","report.md"))))));
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.capabilityContentRejection(RUN,List.of(presentation(unsafeLabel))));
        var unsafePath=new ProcessPresentation("订单结果","已计算订单金额",List.of(new ProcessPresentation.Text("plain",PRIVATE_PATH)),List.of());
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.capabilityContentRejection(RUN,List.of(presentation(unsafePath))));
        when(log.readAfter(any())).thenReturn(List.of(event(1,RUN.runRef(),"agent.tool-result",toolOutput(presentation(unsafe).canonicalJson()))));
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.terminalRejection(RUN));
    }

    @Test void inputCardChecksReadableSchemaTextAndAllowsBusinessIdAndFieldNames() {
        String safe="{\"message\":\"填写订单\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"artifact_id\":{\"type\":\"string\",\"description\":\"业务订单编号\",\"enum\":[\""+BUSINESS_ID+"\"]}}}}";
        assertTrue(contract.capabilityContentRejection(RUN,List.of(new TypedPayload("application/json",safe))).isEmpty());
        String unsafe=safe.replace("业务订单编号",PRIVATE_VALUE);
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.capabilityContentRejection(RUN,List.of(new TypedPayload("application/json",unsafe))));
        assertTrue(contract.publicContentRejection(RUN,List.of("业务 API 字段 run_id、artifact_id，订单 "+BUSINESS_ID)).isEmpty());
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.publicContentRejection(RUN,List.of(PRIVATE_VALUE)));
    }

    @Test void terminalCheckOnlyReadsNewResultsAndDoesNotCacheAnEmptyFuture() throws Exception {
        var first = event(1, RUN.runRef(), "agent.tool-result", toolOutput("{\"value\":42}"));
        var failure = event(3, RUN.runRef(), "agent.tool-result", toolOutput("{\"error\":\"PUBLIC_OUTPUT_REJECTED\"}"));
        var source = new java.util.ArrayList<CommittedSessionEvent>();
        when(log.readAfter(any())).thenAnswer(call -> {
            SessionLogPort.ReadSessionLog query = call.getArgument(0);
            return source.stream().filter(value -> value.sessionSequence() > query.afterSequence()).limit(query.limit()).toList();
        });
        assertTrue(contract.terminalRejection(RUN).isEmpty());
        source.add(first);
        assertTrue(contract.terminalRejection(RUN).isEmpty());
        org.mockito.Mockito.clearInvocations(log);
        assertTrue(contract.terminalRejection(RUN).isEmpty());
        org.mockito.Mockito.verify(log).readAfter(new SessionLogPort.ReadSessionLog(OWNER, 1, 256), RUN.runRef(), Set.of("agent.tool-result"));
        source.add(failure);
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE), contract.terminalRejection(RUN));
        org.mockito.Mockito.clearInvocations(log);
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE), contract.terminalRejection(RUN));
        org.mockito.Mockito.verifyNoInteractions(log);
    }

    @Test void platformStructuredContentUsesTheSameDecodedPublicBoundary() throws Exception {
        var unsafe=new ProcessPresentation("报告","已核对",List.of(new ProcessPresentation.Text("plain",PRIVATE_PATH)),List.of());
        var outer=json.createObjectNode();outer.set("structuredContent",json.readTree(presentation(unsafe).canonicalJson()));
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.capabilityContentRejection(RUN,
                List.of(new TypedPayload("application/json",outer.toString()))));
        outer.putObject("structuredContent").put("delivery_error",PublicOutputGuard.REJECTION_CODE);
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.capabilityContentRejection(RUN,
                List.of(new TypedPayload("application/json",outer.toString()))));
        outer.putObject("structuredContent").put("summary","业务 UUID "+BUSINESS_ID);
        assertTrue(contract.capabilityContentRejection(RUN,List.of(new TypedPayload("application/json",outer.toString()))).isEmpty());
    }

    @Test void renderedCardBodiesAndFieldValuesUseTheDecodedBoundary() throws Exception {
        for (String field : List.of("body", "value", "name", "statusLabel")) {
            var card = json.createObjectNode().put("type", "form"); card.put(field, PRIVATE_PATH);
            assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE), contract.capabilityContentRejection(RUN,
                    List.of(new TypedPayload("application/json", card.toString()))));
        }
        var form = json.createObjectNode();
        form.putObject("requestedSchema").putObject("properties").putObject(PRIVATE_VALUE)
                .put("title", "业务订单编号").put("type", "string");
        assertTrue(contract.capabilityContentRejection(RUN,
                List.of(new TypedPayload("application/json", form.toString()))).isEmpty(),
                "有公开标题的属性选择器不作为界面文字");
    }

    @Test void workflowCardsMustMatchOwnerRunAndExecutionBeforeAdoption() {
        var foreign=SessionOwner.user(new SessionRef(TENANT,"other-session"),new AgentId("101"),OWNER.requireUser());
        for(var card:List.of(
                pending(foreign,RUN.runRef(),EXECUTION,"业务确认"),
                pending(OWNER,new RunRef(TENANT,"other-run"),EXECUTION,"业务确认"),
                pending(OWNER,RUN.runRef(),new ExecutionRef(TENANT,"other-execution"),"业务确认"))) {
            when(workflows.find(any())).thenReturn(Optional.of(card));
            assertThrows(IllegalStateException.class,()->contract.workflowContentRejection(RUN,actor,EXECUTION));
        }
        when(workflows.find(any())).thenAnswer(call->{
            var query=(com.skillforge.runtime.api.port.WorkflowPortal.WorkflowQuery)call.getArgument(0);
            assertEquals(OWNER,query.identity().owner());assertEquals(EXECUTION,query.executionRef());
            return Optional.of(pending(OWNER,RUN.runRef(),EXECUTION,"订单 "+BUSINESS_ID));
        });
        assertTrue(contract.workflowContentRejection(RUN,actor,EXECUTION).isEmpty());
        org.mockito.Mockito.doReturn(Optional.of(pending(OWNER,RUN.runRef(),EXECUTION,PRIVATE_VALUE))).when(workflows).find(any());
        assertEquals(Optional.of(PublicOutputGuard.REJECTION_CODE),contract.workflowContentRejection(RUN,actor,EXECUTION));
    }

    private TypedPayload presentation(ProcessPresentation value) throws Exception {
        var result=json.createObjectNode();result.set("public_presentation",json.valueToTree(value));
        return new TypedPayload("application/json",result.toString());
    }
    private String toolOutput(String value) {
        var result=json.createObjectNode();result.putObject("output").put("schemaId","application/json").put("canonicalJson",value);
        return result.toString();
    }
    private static WorkflowInteractionView.Pending pending(SessionOwner owner,RunRef run,ExecutionRef execution,String text) {
        var json=new ObjectMapper();var node=json.createObjectNode().put("type","confirmation").put("title",text);
        return new WorkflowInteractionView.Pending(owner,run,execution,2,"private-suspend","private-action",new TypedPayload("application/json",node.toString()));
    }
    private static CommittedSessionEvent event(long sequence,RunRef run,String type,String payload) {
        return new CommittedSessionEvent(OWNER,INPUT,Optional.of(run),Optional.empty(),Optional.empty(),Optional.empty(),
                new EventId("public-event-"+sequence),sequence,type,"1",RUN.trace(),new TypedPayload("application/json",payload),Instant.EPOCH);
    }
}
