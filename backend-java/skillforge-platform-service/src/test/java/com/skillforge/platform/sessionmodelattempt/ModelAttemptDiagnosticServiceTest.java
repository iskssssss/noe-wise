package com.skillforge.platform.sessionmodelattempt;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.trajectory.TrajectoryProjectionService;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.core.*;
import com.skillforge.runtime.session.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;

/** typed实际材料读取、独立平台scope、审计失败与字段脱敏的有界应用验证。 */
class ModelAttemptDiagnosticServiceTest {
    static final CodecLimits LIMITS=new CodecLimits(1_000_000,100_000,128);
    static final AgentExecutionCodec CODEC=new AgentExecutionCodec(LIMITS);
    private final ModelAttemptDiagnosticScope scope=new ModelAttemptDiagnosticScope("platform-reader");
    private ModelAttemptDiagnosticMapper mapper;private ObservationReadAudit audit;private ModelAttemptDiagnosticService service;private Map<String,Object> row;
    private SessionObservationAuthorizer observationAuthorizer;private TrajectoryProjectionService trajectories;
    @BeforeEach void prepare() {
        mapper=mock(ModelAttemptDiagnosticMapper.class);audit=mock(ObservationReadAudit.class);
        observationAuthorizer=mock(SessionObservationAuthorizer.class);trajectories=mock(TrajectoryProjectionService.class);
        var reads=mock(PlatformObservationAccess.class);
        when(reads.read(anyLong(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get());
        service=new ModelAttemptDiagnosticService(mapper,new ExecutionModelAttemptInspector(LIMITS),audit,observationAuthorizer,trajectories,reads);
        row=new HashMap<>();row.put("tenant_id","101");row.put("session_id","session");row.put("root_input_id","root");row.put("run_id","run");
        row.put("attempt_id","attempt");row.put("action_id","model-action");row.put("kind","MODEL");row.put("ordinal",1L);row.put("model_ordinal",1L);
        row.put("selected_attempt","attempt");row.put("adopted",true);row.put("status","SUCCEEDED");row.put("usage_source","PROVIDER_REPORTED");
        row.put("prompt_tokens",9_007_199_254_740_993L);row.put("completion_tokens",7L);
        material(row,"request",request());material(row,"result",outcome());
        when(mapper.attempt(101,"session","attempt",null)).thenReturn(List.of(row));
        when(mapper.capabilityFacts("101","run","attempt",1)).thenReturn(List.of());
    }
    @AfterEach void clear(){TenantContext.clear();}
    @Test void manifestBatchReadsStayInsideTheExistingObservationTenantWindow() {
        var diagnostic = new ModelAttemptObservationAuthorizedSqlRule();
        var trajectory = new com.skillforge.core.chat.observation.ChatObservationAuthorizedSqlRule();
        for (var statement : List.of("com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticMapper.materials",
                "com.skillforge.core.chat.trajectory.TrajectoryMapper.modelMaterials")) {
            com.skillforge.core.foundation.access.AuthorizedSqlRule rule = statement.contains("ModelAttemptDiagnosticMapper") ? diagnostic : trajectory;
            var permitted = new com.skillforge.core.foundation.access.AuthorizedSqlRule.Access(
                    com.skillforge.core.foundation.access.AuthorizedSqlRule.Window.PLATFORM_OBSERVATION,
                    statement, true, false, Map.of("tenant", "101"), "101", 0, Set.of("101"), "", "", "foundation.sessions.read");
            assertTrue(rule.allows(permitted));
            var otherTenant = new com.skillforge.core.foundation.access.AuthorizedSqlRule.Access(
                    permitted.window(), statement, true, false, Map.of("tenant", "202"), "101", 0, Set.of("101"), "", "", permitted.action());
            assertFalse(rule.allows(otherTenant));
            var write = new com.skillforge.core.foundation.access.AuthorizedSqlRule.Access(
                    permitted.window(), statement, false, false, permitted.parameters(), "101", 0, Set.of("101"), "", "", permitted.action());
            assertFalse(rule.allows(write));
        }
    }
    @Test void manifestDiagnosticLoadsOriginalLeafMaterialsInOneTenantBatch() {
        var encoded = CODEC.encodeModelRequestMaterials(CODEC.decodeModelRequest(request()));
        material(row, "request", encoded.root().content());
        when(mapper.materials(eq("101"), anyList())).thenAnswer(call -> encoded.parts().stream()
                .map(part -> Map.<String,Object>of("reference", part.reference().value(), "schema_id", part.content().schemaId(), "canonical_json", part.content().canonicalJson())).toList());
        var detail = service.detail(scope,101,"session",null,"attempt",null,null);
        assertEquals("FULL", detail.input().availability());
        assertTrue(detail.input().value().toString().contains("系统原指令"));
        assertTrue(detail.input().value().toString().contains("用户原问题"));
        verify(mapper).materials("101", CODEC.modelRequestReferences(encoded.root().content()).stream().map(ResultRef::value).toList());
    }
    @Test void missingManifestLeafRejectsPrivateDiagnosticBeforeAudit() {
        var encoded = CODEC.encodeModelRequestMaterials(CODEC.decodeModelRequest(request()));
        material(row, "request", encoded.root().content());
        when(mapper.materials(eq("101"), anyList())).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.detail(scope,101,"session",null,"attempt",null,null));
        verifyNoInteractions(audit);
    }
    @Test void readsExactActualInputReasoningAndOutputWithoutFrozenBindingOrCredentialReferences() throws Exception {
        TenantContext.setTenantId("previous");var detail=service.detail(scope,101,"session",null,"attempt",null,null);
        assertEquals("previous",TenantContext.getTenantId());assertEquals("FULL",detail.input().availability());assertEquals("FULL",detail.reasoning().availability());
        assertEquals("供应商原推理",detail.reasoning().value());assertEquals("9007199254740993",detail.usage().totals().promptTokens());assertTrue(detail.usage().diagnosticAvailable());
        assertEquals("session",detail.usage().sourceSessionId());assertEquals("root",detail.usage().sourceRootInputId());
        assertEquals("model-action",detail.loopId());assertEquals(1,detail.iteration());assertEquals("FULL",detail.capabilityCallsAvailability());
        var json=new ObjectMapper().writeValueAsString(detail.input());assertTrue(json.contains("用户原问题"));assertFalse(json.contains("private-binding"));assertFalse(json.contains("private-resolution"));
        verify(audit).record(eq("101"),eq("platform-reader"),eq("platform.session.model-attempt.detail.read"),eq("model_attempt"),eq("attempt"),anyString(),argThat(values->!values.containsKey("input")&&!values.containsKey("reasoning")));
    }
    @Test void dispatchedAttemptWithoutResultOrProviderReportRemainsUnresolvedAndNeverInventsUsage() {
        row.remove("result_ref");row.remove("result_schema");row.remove("result_json");row.put("usage_source","UNKNOWN");row.put("status","UNRESOLVED");row.put("adopted",false);row.remove("selected_attempt");
        var detail=service.detail(scope,101,"session",null,"attempt",null,null);assertEquals("FULL",detail.input().availability());assertEquals("NOT_RECORDED",detail.output().availability());
        assertEquals("UNKNOWN",detail.usage().reportStatus());assertNull(detail.usage().totals().promptTokens());assertNull(detail.startedAt());assertEquals("NOT_RECORDED",detail.capabilityCallsAvailability());
        verify(mapper,never()).capabilityFacts(anyString(),anyString(),anyString(),anyLong());
    }
    @Test void missingOrWrongPrivateScopeNeverReadsMaterials() {
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->service.detail(null,101,"session",null,"attempt",null,null)).getStatus());verifyNoInteractions(mapper,audit);
    }
    @Test void wrongSessionOrRootUsesSameNotFoundBoundary() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->service.detail(scope,101,"other",null,"attempt",null,null)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->service.detail(scope,101,"session","other-root","attempt",null,null)).getStatus());verifyNoInteractions(audit);
    }
    @Test void malformedActualRequestFailsClosedWithoutRawBodyInPublicError() {
        row.put("request_json","{\"private-credential\":\"must-not-leak\"}");
        var failure=assertThrows(ApiException.class,()->service.detail(scope,101,"session",null,"attempt",null,null));assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,failure.getStatus());
        assertFalse(failure.getMessage().contains("must-not-leak"));verifyNoInteractions(audit);assertNull(TenantContext.getTenantId());
    }
    @Test void auditFailurePreventsAnyPrivateResponseAndRestoresTenant() {
        TenantContext.setTenantId("previous");doThrow(new IllegalStateException("audit-unavailable")).when(audit).record(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyMap());
        assertThrows(IllegalStateException.class,()->service.detail(scope,101,"session",null,"attempt",null,null));assertEquals("previous",TenantContext.getTenantId());
    }
    @Test void actualToolArgumentsUseFrozenSensitiveFieldsAndNeverSerializeTargetOrOpaqueState() {
        var target=new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:fixture"),new CapabilityRef(CapabilityType.TOOL,"fixture","1"),"deployment",1,new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),ToolEffect.READ_ONLY,true,Duration.ofSeconds(1),true,ToolPortal.ToolConfirmationPolicy.NEVER,Set.of("secret"),Set.of(),false);
        var request=CODEC.encodeTool(new AgentExecutionCodec.ToolRequest(target,new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.TEST_MOCK,"server","read"),new TypedPayload("application/json","{\"city\":\"上海\",\"secret\":\"credential-content\"}"),"call-1","sf_read"));
        var actual=new HashMap<String,Object>();actual.put("tenant_id","101");actual.put("run_id","run");actual.put("action_id","tool-action");actual.put("kind","TOOL");actual.put("attempt_id","tool-attempt");actual.put("status","UNRESOLVED");material(actual,"request",request);
        when(mapper.capabilityFacts("101","run","attempt",1)).thenReturn(List.of(actual));var detail=service.detail(scope,101,"session",null,"attempt",null,null);
        var tool=detail.capabilityCalls().getFirst();assertEquals("call-1",tool.callId());assertEquals("PARTIAL",tool.input().availability());
        assertTrue(tool.input().value().toString().contains("上海"));assertFalse(tool.input().value().toString().contains("credential-content"));assertEquals("NOT_RECORDED",tool.output().availability());
    }
    @Test void unadoptedModelNeverBorrowsSelectedAttemptsCapabilityCalls() {
        row.put("selected_attempt","another-attempt");var detail=service.detail(scope,101,"session",null,"attempt",null,null);assertTrue(detail.capabilityCalls().isEmpty());assertEquals("NOT_RECORDED",detail.capabilityCallsAvailability());
        verify(mapper,never()).capabilityFacts(anyString(),anyString(),anyString(),anyLong());
    }
    @Test void childAttemptRequiresExactAuthorizedParentPathBeforePrivateRead() {
        var parentScope=mock(SessionObservationScope.class);when(parentScope.actorId()).thenReturn(scope.actorId());
        when(observationAuthorizer.platformAdmin()).thenReturn(parentScope);
        var parent=new TrajectoryQuery.Resource(101,"parent-session","parent-root");
        when(trajectories.requireDescendantAttempt(parentScope,parent,"delegation:child","child-root","attempt")).thenReturn("child-run");
        row.put("session_id","delegation:child");row.put("root_input_id","child-root");row.put("run_id","child-run");
        when(mapper.descendantAttempt(101,"delegation:child","child-root","attempt","child-run")).thenReturn(List.of(row));
        var detail=service.detail(scope,101,"delegation:child","child-root","attempt","parent-session","parent-root");
        assertEquals("delegation:child",detail.sessionId());assertEquals("child-run",detail.runId());assertEquals("FULL",detail.input().availability());
        verify(mapper,never()).attempt(anyLong(),anyString(),anyString(),any());
        verify(audit).record(eq("101"),eq(scope.actorId()),anyString(),anyString(),eq("attempt"),anyString(),
                argThat(fields->"parent-session".equals(fields.get("parent_session_id"))&&"parent-root".equals(fields.get("parent_root_input_id"))));
    }
    @Test void malformedOrForgedParentNeverReachesPrivateChildMaterials() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException.class,()->service.detail(scope,101,"delegation:child","root","attempt","parent",null)).getStatus());
        var parentScope=mock(SessionObservationScope.class);when(parentScope.actorId()).thenReturn(scope.actorId());
        when(observationAuthorizer.platformAdmin()).thenReturn(parentScope);
        when(trajectories.requireDescendantAttempt(any(),any(),anyString(),anyString(),anyString()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND,"模型调用不存在或不属于已授权父轨迹"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->service.detail(scope,101,"delegation:child","root","attempt","forged-parent","forged-root")).getStatus());
        verify(mapper,never()).descendantAttempt(anyLong(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(audit);
    }
    static TypedPayload request() {
        var target=new FrozenModelTarget(new ModelBindingRef(new TenantId("101"),"private-binding",1),"actual-model",new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),false,true,new ResultRef("capabilities")),Duration.ofSeconds(30),new ResultRef("private-resolution"));
        var actual=new FrozenModelRequest(new RequestedModelStrategy("strategy","1"),target,List.of(new ModelMessage("system",ModelRole.SYSTEM,"系统原指令"),new ModelMessage("user",ModelRole.USER,"用户原问题")),new ResponseContract(new ResponseFormat.Text(),EnforcementLevel.STRICT,"supported"),new GenerationOptions(OptionalInt.of(100),OptionalDouble.empty()),ReasoningPolicy.DEFAULT);
        return CODEC.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(actual,List.of(),0,Optional.empty()));
    }
    static TypedPayload outcome() {
        var payloads=new SessionRuntimePayloads();var core=new CoreRuntime(LIMITS,payloads);
        var completed=new ModelResult.Completed(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA,"{\"answer\":\"模型原回答\"}"),"actual-model",new ModelUsage(OptionalLong.of(9_007_199_254_740_993L),OptionalLong.of(7)),"stop",new ResultRef("provider-result"),Optional.of("供应商原推理"));
        return CODEC.encodeOutcome(core.portalResults(payloads,payloads,payloads).encodeModel(PortalCallKind.MODEL_GENERATE,completed));
    }
    static void material(Map<String,Object> row,String prefix,TypedPayload payload){row.put(prefix+"_ref",prefix+"-reference");row.put(prefix+"_schema",payload.schemaId());row.put(prefix+"_json",payload.canonicalJson());}
}
