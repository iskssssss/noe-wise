package com.skillforge.core.channel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingMapper;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingDO;
import com.skillforge.core.capability.application.OpenPlatformResourceLifecycleService;
import com.skillforge.core.capability.application.ResourceRevisionConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenPlatformChannelBindingServiceTest {
    @Test
    void bindsOnlyValidatedDeploymentAndPreservesBindingCas() {
        var deployments = mock(OpenPlatformResourceLifecycleService.class);
        var bindings = mock(OpenPlatformChannelBindingMapper.class);
        var service = new OpenPlatformChannelBindingService(bindings, deployments);
        when(deployments.requireActiveAgentDeployment(7L, "deployment-1"))
                .thenThrow(new IllegalStateException("部署不可用"));
        assertThrows(IllegalStateException.class, () -> service.bindChannel("7", 10L, "deployment-1", null, null));
        verifyNoInteractions(bindings);

        doReturn(new OpenPlatformResourceLifecycleService.ActiveAgentDeployment("agent-1", 3L))
                .when(deployments).requireActiveAgentDeployment(7L, "deployment-1");
        var created = service.bindChannel("7", 10L, "deployment-1", null, null);
        assertEquals(1L, created.bindingRevision());
        assertEquals(3L, created.resourceRevision());
        var row = ArgumentCaptor.forClass(OpenPlatformChannelBindingDO.class);
        verify(bindings).insert(row.capture());
        when(bindings.selectOne(any())).thenReturn(row.getValue());
        when(bindings.update(any(), any())).thenReturn(1);
        var updated = service.bindChannel("7", 10L, "deployment-1", 1L, created.etag());
        assertEquals(2L, updated.bindingRevision());
        assertThrows(ResourceRevisionConflictException.class,
                () -> service.bindChannel("7", 10L, "deployment-1", 1L, created.etag()));
    }

    @Test
    void publicationFailureIsPropagatedToOwningTransaction() {
        var bindings = mock(OpenPlatformChannelBindingMapper.class);
        var row = new OpenPlatformChannelBindingDO();
        row.setTenantId(7L); row.setChannelId(10L); row.setDeploymentId("deployment-1");
        row.setResourceRevision(1L); row.setBindingRevision(2L);
        when(bindings.selectList(any())).thenReturn(java.util.List.of(row));
        when(bindings.advancePublication(any())).thenReturn(0);
        var listener = new ChannelPublicationListener(bindings);
        assertThrows(IllegalStateException.class,
                () -> listener.advanceBindings(7L, "agent-1", 3L, java.util.List.of("deployment-1")));
        assertEquals(3L, row.getResourceRevision());
        assertEquals(3L, row.getBindingRevision());
    }
}
