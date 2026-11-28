package com.skillforge.institution.workflowtest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTestProvisioningService;
import com.skillforge.core.capability.*;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.WorkflowInstallationValidator;
import com.skillforge.core.workflow.WorkflowInstallationTargetAccess;
import com.skillforge.core.workflow.WorkflowTestDefinitionService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.WorkflowManagementService;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

class WorkflowTestInputTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WorkflowTestRegistry tests=mock(WorkflowTestRegistry.class);
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final WorkflowManagementService management=mock(WorkflowManagementService.class);
    private final WorkflowTestDefinitionService definitions=mock(WorkflowTestDefinitionService.class);
    private final WorkflowInstallationValidator bindings=mock(WorkflowInstallationValidator.class);
    private final AgentTestProvisioningService agents=mock(AgentTestProvisioningService.class);
    private final EndUserService users=mock(EndUserService.class);
    private final SessionService sessions=mock(SessionService.class);
    private final CapabilityCatalogService catalog=mock(CapabilityCatalogService.class);
    private final CapabilityMountResolver.Registry registry=mock(CapabilityMountResolver.Registry.class);
    private final WorkflowInstallationTargetAccess targets=mock(WorkflowInstallationTargetAccess.class);
    private final WorkflowTestSessionService service=new WorkflowTestSessionService(
            tests,null,access,management,definitions,bindings,targets,catalog,registry,agents,users,sessions,null,null,null,
            json,new AgentRuntimeProperties(),mock(PlatformTransactionManager.class),null);

    @AfterEach void clear() {TenantContext.clear();}

    @ParameterizedTest
    @ValueSource(strings={"get_current_time","calculate_expression","convert_datetime","json_query","json_schema_validate","template_render"})
    void platformUtilityOptionsAndCreationAcceptTheSameExactTestDeployment(String name) {
        var selected=toolBinding(name,ToolExecutionPolicy.Effect.READ_ONLY,ToolExecutionPolicy.ConfirmationPolicy.NEVER,true,
                ToolDeployment.Environment.TEST,ToolDeployment.ProviderType.PLATFORM,true,3,"221");
        var options=service.options("flow",1L,null);
        assertEquals(1,options.capabilitySlots().getFirst().candidates().size());
        var candidate=options.capabilitySlots().getFirst().candidates().getFirst();
        assertEquals("test-tool",candidate.toolDeploymentId());assertEquals(3L,candidate.toolDeploymentRevision());
        assertEquals("221",candidate.capability().versionId());
        assertDoesNotThrow(()->service.requireBindings(17,selected));
        verify(targets).requireProvider(eq(17L),any());
    }

    @ParameterizedTest
    @ValueSource(strings={"search_memory","search_tasks","read_task","read_image","list_artifacts","browser_tasks","workspace_files","http_request","unknown_tool"})
    void unrelatedPlatformToolsAreNotCandidatesAndCannotBeSubmittedDirectly(String name) {
        var selected=toolBinding(name,ToolExecutionPolicy.Effect.READ_ONLY,ToolExecutionPolicy.ConfirmationPolicy.NEVER,true,
                ToolDeployment.Environment.TEST,ToolDeployment.ProviderType.PLATFORM,true,3,"221");
        assertTrue(service.options("flow",1L,null).capabilitySlots().getFirst().candidates().isEmpty());
        assertEquals("workflow_test_binding_rejected",assertThrows(ApiException.class,()->service.requireBindings(17,selected)).getCode());
        verifyNoInteractions(targets);
    }

    @Test void platformUtilityNameDoesNotBypassEffectConfirmationInvocationOrDeploymentChecks() {
        for(var effect:List.of(ToolExecutionPolicy.Effect.IDEMPOTENT_WRITE,ToolExecutionPolicy.Effect.NON_IDEMPOTENT_WRITE,ToolExecutionPolicy.Effect.DESTRUCTIVE)) {
            var selected=toolBinding("calculate_expression",effect,ToolExecutionPolicy.ConfirmationPolicy.NEVER,true,
                    ToolDeployment.Environment.TEST,ToolDeployment.ProviderType.PLATFORM,true,3,"221");
            assertTrue(service.options("flow",1L,null).capabilitySlots().getFirst().candidates().isEmpty());
            assertEquals("workflow_test_binding_rejected",assertThrows(ApiException.class,()->service.requireBindings(17,selected)).getCode());
        }
        for(var scenario:List.of("confirmation","not-invocable","production","development","unsupported","inactive","revision","version")) {
            var selected=toolBinding("calculate_expression",ToolExecutionPolicy.Effect.READ_ONLY,
                    scenario.equals("confirmation")?ToolExecutionPolicy.ConfirmationPolicy.POLICY:ToolExecutionPolicy.ConfirmationPolicy.NEVER,
                    !scenario.equals("not-invocable"),scenario.equals("production")?ToolDeployment.Environment.PRODUCTION:
                            scenario.equals("development")?ToolDeployment.Environment.DEVELOPMENT:ToolDeployment.Environment.TEST,
                    scenario.equals("unsupported")?ToolDeployment.ProviderType.CONNECTOR:ToolDeployment.ProviderType.PLATFORM,
                    !scenario.equals("inactive"),scenario.equals("revision")?4:3,scenario.equals("version")?"222":"221");
            if(!scenario.equals("revision"))assertTrue(service.options("flow",1L,null).capabilitySlots().getFirst().candidates().isEmpty(),scenario);
            assertEquals("workflow_test_binding_rejected",assertThrows(ApiException.class,()->service.requireBindings(17,selected)).getCode(),scenario);
        }
    }

    @Test void existingReadOnlyTestMcpRemainsAvailable() {
        var selected=toolBinding("lookup",ToolExecutionPolicy.Effect.READ_ONLY,ToolExecutionPolicy.ConfirmationPolicy.NEVER,true,
                ToolDeployment.Environment.TEST,ToolDeployment.ProviderType.MCP,true,3,"221");
        assertEquals(1,service.options("flow",1L,null).capabilitySlots().getFirst().candidates().size());
        assertDoesNotThrow(()->service.requireBindings(17,selected));
    }

    private WorkflowInstallationBindings toolBinding(String name,ToolExecutionPolicy.Effect effect,
            ToolExecutionPolicy.ConfirmationPolicy confirmation,boolean invocable,ToolDeployment.Environment environment,
            ToolDeployment.ProviderType provider,boolean active,long revision,String deploymentVersion) {
        TenantContext.setTenantId("17");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"17","29"));
        var ref=new CapabilityRef(CapabilityType.TOOL,"211","221");
        var tool=new ToolVersion(new CapabilityMetadata(ref,null,name,"测试",CapabilityMetadata.Trust.PLATFORM,CapabilityMetadata.Status.PUBLISHED,"hash",new InvocationPolicy(invocable,true)),
                new ToolDefinition(name,Optional.empty(),"测试",Map.of(),Optional.empty(),List.of(),Optional.empty()),
                new ToolExecutionPolicy(effect,true,confirmation,5000,Set.of(),provider==ToolDeployment.ProviderType.PLATFORM?Set.of("platform_handler:"+name):Set.of()));
        when(registry.find(17L,ref)).thenReturn(Optional.of(tool));
        var deployment=new ToolDeployment("test-tool",17L,new CapabilityRef(CapabilityType.TOOL,"211",deploymentVersion),provider,name,revision,environment,active);
        when(registry.findToolDeployment(17L,"test-tool")).thenReturn(Optional.of(deployment));
        when(catalog.listDefinitions(CapabilityCatalogScope.tenant(17L),CapabilityType.TOOL,null)).thenReturn(List.of(
                new CapabilityDefinitionView(211L,null,CapabilityType.TOOL,name,name,"测试","PLATFORM","ACTIVE",221L,1L,null,null)));
        when(catalog.getCurrentPublishedVersion(CapabilityCatalogScope.tenant(17L),211L)).thenReturn(
                new CapabilityVersionView(221L,211L,null,CapabilityType.TOOL,"builtin:1",name,"测试","PLATFORM",Map.of(),"hash","PUBLISHED",null,null));
        when(catalog.listDeployments(CapabilityCatalogScope.tenant(17L),"ACTIVE")).thenReturn(active?List.of(
                new CapabilityDeploymentView(301L,17L,"test-tool",Long.valueOf(deploymentVersion),name,"builtin:1",provider,name,environment,"ACTIVE",revision,null,null,211L)):List.of());
        var definition=Map.<String,Object>of("meta",Map.of("capability_requirements",List.of(Map.of("slot","utility","type","tool","capability_id",name,"version_constraint","*","required",true)),
                "capability",Map.of("input_schema",Map.of())));
        when(definitions.readSource(eq(17L),eq("flow"),any())).thenReturn(new WorkflowTestDefinitionService.SourceSnapshot(31,definition,"draft","flow",1L,null,"hash"));
        return new WorkflowInstallationBindings(List.of(new WorkflowInstallationBindings.CapabilityBinding("utility",ref,"test-tool",3L,null)),List.of(),List.of());
    }

    @ParameterizedTest
    @CsvSource({"type,/amount,类型不符合定义", "extra,/extra,未在输入定义中声明", "nested,/items/0,类型不符合定义", "required,/amount,缺少必填字段"})
    void invalidInputReturnsSafeFieldLocationBeforeProvisioning(String scenario,String path,String problem) throws Exception {
        TenantContext.setTenantId("17");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"17","29"));
        var schema=Map.<String,Object>of("type","object","properties",Map.of(
                "amount",Map.of("type","number"),"items",Map.of("type","array","items",Map.of("type","integer"))),
                "required",List.of("amount"),"additionalProperties",false);
        var definition=Map.<String,Object>of("meta",Map.of("capability",Map.of("input_schema",schema)));
        when(definitions.lockSource(eq(17L),eq("flow"),any())).thenReturn(
                new WorkflowTestDefinitionService.SourceSnapshot(31,definition,"draft","flow",1L,null,"hash"));
        when(bindings.currentBindings(17,WorkflowInstallationBindings.empty())).thenReturn(WorkflowInstallationBindings.empty());
        Map<String,Object> parameters=switch(scenario) {
            case "type" -> Map.of("amount","private-value");
            case "extra" -> Map.of("amount",250,"extra","private-value");
            case "nested" -> Map.of("amount",250,"items",List.of("private-value"));
            default -> Map.of();
        };
        var request=new WorkflowTestContracts.Create("input-check","flow",new WorkflowTestContracts.Source("draft",1L,null),
                Map.of(),null,"测试消息",parameters,WorkflowTestSessionService.RECOMMENDED);
        var rejected=assertThrows(WorkflowTestSessionService.InputRejected.class,()->service.create(request));
        assertTrue(rejected.getMessage().contains(path));
        assertTrue(rejected.getMessage().contains(problem));
        assertFalse(rejected.getMessage().contains("private-value"));
        verify(definitions,never()).createCopy(anyLong(),anyString(),any(),any(),any());
        verify(tests,never()).register(any(),any());
        verifyNoInteractions(agents,users,sessions);

        var controllerService=mock(WorkflowTestSessionService.class);
        when(controllerService.create(any())).thenThrow(rejected);
        MockMvcBuilders.standaloneSetup(new WorkflowTestSessionController(controllerService)).build()
                .perform(post("/api/v1/builder/workflows/test-sessions").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("workflow_test_input_invalid"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.field_errors.parameters").value(rejected.getMessage()));
    }
}
