package com.skillforge.institution.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 当前资格和控制范围的聚焦行为；完整来源、队列及RLS由组合数据库用例覆盖。 */
class GroupMemoryExtractionPolicyTest {
    @AfterEach void clearContexts() { TenantContext.clear(); ServicePrincipalContext.clear(); }

    @Test void explicitEnabledCannotBeCoercedFromTextOrNumber() {
        var json=new ObjectMapper();
        assertThrows(JsonProcessingException.class,()->json.readValue("""
            {"group_binding_id":"11","operator_end_user_id":"3","enabled":"true","extractor_agent_id":"7","expected_revision":0}
            """,GroupMemoryExtractionPolicy.Command.class));
        assertThrows(JsonProcessingException.class,()->json.readValue("""
            {"group_binding_id":"11","operator_end_user_id":"3","enabled":1,"extractor_agent_id":"7","expected_revision":0}
            """,GroupMemoryExtractionPolicy.Command.class));
    }

    @Test void explicitExtractionPauseBlocksButSendPauseLeavesExtractionEnabled() {
        TenantContext.setTenantId("1");
        var mapper=mock(GroupMemoryExtractionMapper.class);
        var controls=mock(GroupControlMapper.class);
        var input=new GroupMemoryExtractionInput(mock(GroupMemorySources.class),mapper,controls);
        when(controls.lock(1,"it",11)).thenReturn(new GroupControlMapper.ControlGroup(1,"it",11,1,0));
        when(mapper.eligible("1","it","11","7")).thenReturn(true);
        when(controls.states(1,"it",11)).thenReturn(List.of(new GroupControlMapper.State(1,"it",11,"*","send",true,1)));
        assertTrue(input.allowed("1","it","11","7","run"));
        when(controls.states(1,"it",11)).thenReturn(List.of(new GroupControlMapper.State(1,"it",11,"*","memory_extraction",true,2)));
        assertFalse(input.allowed("1","it","11","7","run"));
        when(controls.states(1,"it",11)).thenReturn(List.of(new GroupControlMapper.State(1,"it",11,"other","memory_extraction",true,2)));
        assertTrue(input.allowed("1","it","11","7","run"));
        assertFalse(input.allowed("2","it","11","7","run"));
        verify(mapper,never()).eligible("2","it","11","7");
    }

    @Test void absentPolicyIsDisabledAndManagerCanDisableAfterExtractorRemoval() {
        TenantContext.setTenantId("1");
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp","1","it",1,Set.of("groups.memory.read","groups.memory.write")));
        var mapper=mock(GroupMemoryExtractionMapper.class);
        var controls=mock(GroupControlMapper.class);
        var groups=mock(GroupBindingService.class);
        var service=new GroupMemoryExtractionPolicyService(mapper,controls,groups,mock(InstitutionIntegrationService.class));
        var absent=service.get("11",3);
        assertFalse(absent.enabled());
        assertEquals(0,absent.revision());
        assertNull(absent.extractorAgentId());
        when(mapper.policy("1","it","11")).thenReturn(new GroupMemoryExtractionMapper.Policy("1","it","11",true,"7",1,"sp"));
        when(controls.lock(1,"it",11)).thenReturn(new GroupControlMapper.ControlGroup(1,"it",11,1,0));
        when(mapper.savePolicy(any(),eq(1L))).thenReturn(1);
        var disabled=service.put(new GroupMemoryExtractionPolicy.Command("11",3L,false,7L,1L));
        assertFalse(disabled.enabled());
        assertEquals(2,disabled.revision());
        verify(groups,never()).requireServiceAgent(anyString(),anyString(),anyString(),anyLong());
        verify(mapper,never()).agentEnabled(anyString(),anyString());
        verify(groups,times(2)).requireMember("1","it","11",3,true);
    }

    @Test void stalePolicyRevisionDoesNotWriteOrSelectANewExtractor() {
        TenantContext.setTenantId("1");
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp","1","it",1,Set.of("groups.memory.write")));
        var mapper=mock(GroupMemoryExtractionMapper.class);
        var controls=mock(GroupControlMapper.class);
        var service=new GroupMemoryExtractionPolicyService(mapper,controls,mock(GroupBindingService.class),mock(InstitutionIntegrationService.class));
        when(controls.lock(1,"it",11)).thenReturn(new GroupControlMapper.ControlGroup(1,"it",11,1,0));
        when(mapper.policy("1","it","11")).thenReturn(new GroupMemoryExtractionMapper.Policy("1","it","11",true,"7",3,"sp"));
        assertEquals(409,assertThrows(ApiException.class,()->service.put(new GroupMemoryExtractionPolicy.Command("11",3L,true,9L,2L))).getStatus().value());
        verify(mapper,never()).savePolicy(any(),anyLong());
        verify(mapper,never()).agentEnabled(anyString(),anyString());
    }
}
