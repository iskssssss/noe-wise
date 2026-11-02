package com.skillforge.institution.group;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 群资格在原生与受控部署间共用发布校验，撤下名单不受当前可执行性阻断。 */
class GroupAgentEligibilityTest {
    private final AgentService agents = mock(AgentService.class);
    private final AgentPublicationService publications = mock(AgentPublicationService.class);
    private final AgentDeploymentMapper deployments = mock(AgentDeploymentMapper.class);
    private final GroupAgentEligibility eligibility = new GroupAgentEligibility(agents, publications, deployments);

    @BeforeEach void currentTenant() {
        TenantContext.setTenantId("7");
        var agent = new AgentDO(); agent.setId(9L); agent.setTenantId(7L);
        when(agents.getOne(any())).thenReturn(agent);
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void nativeAgentUsesCurrentPublishedResolverWithoutRequiringDeployment() {
        eligibility.requireAvailable("7", "clinic", 9);
        verify(publications).requirePublished(7, 9);
        verifyNoMoreInteractions(publications);
    }

    @Test void explicitGroupGrantAllowsNativeControlledInstanceWithoutIntegration() {
        when(deployments.findAgent(7, 9)).thenReturn(deployment(null));
        eligibility.requireAvailable("7", "clinic", 9);
        verify(publications).requirePublished(7, 9);
    }

    @Test void fixedDifferentIntegrationFailsBeforePrivatePublicationIsRead() {
        when(deployments.findAgent(7, 9)).thenReturn(deployment("other"));
        assertThatThrownBy(() -> eligibility.requireAvailable("7", "clinic", 9))
                .isInstanceOfSatisfying(ApiException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(publications);
    }

    @Test void currentControlledSourceRejectionPropagatesButRemovalStillWorks() {
        when(deployments.findAgent(7, 9)).thenReturn(deployment("clinic"));
        when(publications.requirePublished(7, 9)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "source revoked"));
        assertThatThrownBy(() -> eligibility.requireAvailable("7", "clinic", 9)).isInstanceOf(ApiException.class);
        clearInvocations(publications);
        eligibility.requireIdentity("7", "clinic", 9);
        verifyNoInteractions(publications);
    }

    @Test void crossTenantAndMissingIdentityNeverReachPublication() {
        assertThatThrownBy(() -> eligibility.requireAvailable("8", "clinic", 9)).isInstanceOf(ApiException.class);
        verifyNoInteractions(agents, deployments, publications);
        when(agents.getOne(any())).thenReturn(null);
        assertThatThrownBy(() -> eligibility.requireIdentity("7", "clinic", 9)).isInstanceOf(ApiException.class);
        verifyNoInteractions(publications);
    }

    /** 仅固定身份字段参与跨域判定；完整授权交给正式发布解析器。 */
    private AgentDeploymentMapper.Row deployment(String integration) {
        return new AgentDeploymentMapper.Row(11L, integration, 7L, 9L, 13L, 17L, 1,
                "agent", "fingerprint", "{}", 1, Instant.EPOCH, "{}");
    }
}
