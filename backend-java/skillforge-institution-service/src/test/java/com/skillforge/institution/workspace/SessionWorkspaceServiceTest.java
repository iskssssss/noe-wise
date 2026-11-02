package com.skillforge.institution.workspace;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.institution.artifact.StudioArtifactProjection;
import com.skillforge.institution.execution.CodeExecutionToolHandler;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimePublishedCapabilities;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Skill 声明不能授予工作区命令权限，来源读取与 Runner 必须排在现行授权之后。 */
class SessionWorkspaceServiceTest {
    @Test
    void skillHandleCannotBypassMissingWorkspaceToolOrRevokedRunAuthorization() throws Exception {
        var context=context();
        var bindings=mock(AgentRunBindings.class);
        var capabilities=mock(AgentRuntimePublishedCapabilities.class);
        var registry=mock(SessionWorkspaceRegistry.class);
        var sandbox=mock(WorkspaceSandboxClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeExecutionToolHandler> execution=mock(ObjectProvider.class);
        var frozen=mock(AgentRunBindings.Binding.class);
        when(bindings.find(context.run().runRef())).thenReturn(Optional.of(frozen));
        when(frozen.owner()).thenReturn(context.run().owner());
        when(frozen.actor()).thenReturn(context.run().agentId());
        when(capabilities.list(context.run())).thenReturn(List.of());
        var settings=mock(RunSettingsService.class);
        var service=new SessionWorkspaceService(bindings,capabilities,settings,registry,sandbox,
                mock(ArtifactAccess.class),mock(ArtifactStorage.class),mock(StudioArtifactProjection.class),execution,new ObjectMapper());
        var args=Map.<String,Object>of("command","bash \"$SKILL_DIR/scripts/check.sh\"","skill_handle","skill:already-active");

        assertThrows(SecurityException.class,()->service.exec(args,context));
        verifyNoInteractions(settings,registry,sandbox,execution);

        org.mockito.Mockito.doThrow(new SecurityException("revoked")).when(bindings).authorize(frozen);
        assertThrows(SecurityException.class,()->service.exec(args,context));
        verifyNoInteractions(settings,registry,sandbox,execution);
    }

    /** 构造与正式工具入口相同的可信 Run 身份，不向参数填入租户或工作区身份。 */
    private static PlatformToolExecutionContext context() {
        var tenant=new TenantId("1");
        var input=new InputId("input");
        var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("7"),new EndUserId(9));
        var run=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant,owner.requireUser()),owner,
                Set.of(),1,new ResultRef("verification"));
        return new PlatformToolExecutionContext(new PortCallContext(run,identity,
                new InvocationControl(Instant.now().plusSeconds(120),()->false)),"invocation","attempt");
    }
}
