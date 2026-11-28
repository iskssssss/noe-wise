package com.skillforge.institution.workflowtest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTestProvisioningService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.WorkflowInstallationValidator;
import com.skillforge.core.workflow.WorkflowTestDefinitionService;
import com.skillforge.institution.service.WorkflowManagementService;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private final WorkflowTestSessionService service=new WorkflowTestSessionService(
            tests,null,access,management,definitions,bindings,null,null,null,agents,users,sessions,null,null,null,
            json,new AgentRuntimeProperties(),mock(PlatformTransactionManager.class),null);

    @AfterEach void clear() {TenantContext.clear();}

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
