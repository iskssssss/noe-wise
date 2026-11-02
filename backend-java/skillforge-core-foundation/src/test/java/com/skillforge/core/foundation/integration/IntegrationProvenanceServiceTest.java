package com.skillforge.core.foundation.integration;

import com.skillforge.common.context.TenantContext;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原生和外部来源共同冻结，子任务沿原根及原授权，不重选当前部署。 */
class IntegrationProvenanceServiceTest {
    private final IntegrationProvenanceMapper mapper=mock(IntegrationProvenanceMapper.class);
    private final IntegrationProvenanceService service=new IntegrationProvenanceService(mapper,mock(InstitutionIntegrationService.class));
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void freezesNativeRunWithNoExternalIntegration() {
        var session=source(11L,null);var run=source(11L,"input");
        when(mapper.session("7","s")).thenReturn(session);when(mapper.run("7","r")).thenReturn(run);
        service.freezeRun("7","r","s",11L,"input",null,null,null,null);
        verify(mapper).insert("7","r",null,run);assertNull(TenantContext.getTenantId());
    }
    @Test void childRetainsRootAndResponsibilityWhileUsingFrozenTargetAgent() {
        var parent=source(11L,"input");var child=source(12L,"input");
        when(mapper.run("7","parent")).thenReturn(parent);when(mapper.run("7","child")).thenReturn(child);
        service.inheritRun("7","child","parent",12L,null,null,null,null);
        verify(mapper).insert("7","child","parent",child);
    }
    @Test void missingParentOrCrossTenantFailsBeforeSourceCanBeFabricated() {
        assertThrows(IllegalStateException.class,()->service.inheritRun("7","child","missing",12L,null,null,null,null));
        TenantContext.setTenantId("8");assertThrows(SecurityException.class,()->service.resolveRun("7","r"));
        assertEquals("8",TenantContext.getTenantId());
    }
    private IntegrationProvenanceService.Source source(long agent,String root) {
        return new IntegrationProvenanceService.Source("native","member:5","tenant:7",null,null,agent,"s",root,null,null,null,null);
    }
}
