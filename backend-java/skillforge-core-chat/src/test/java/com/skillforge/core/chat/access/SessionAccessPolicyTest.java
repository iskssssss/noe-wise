package com.skillforge.core.chat.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.chat.persistence.SessionDO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.chat.session.SessionView;
import org.springframework.http.HttpStatus;

/** 三种真实认证体系互斥，租户上下文不能替代认证或会话自身所有权。 */
class SessionAccessPolicyTest {
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final FoundationOrganizationService organization=mock(FoundationOrganizationService.class);
    private final OpenPlatformAccessService openPlatform=mock(OpenPlatformAccessService.class);
    private final SessionAccessMapper mapper=mock(SessionAccessMapper.class);
    private final InstitutionIntegrationService integrations=mock(InstitutionIntegrationService.class);
    private final SessionAccessPolicy policy=new SessionAccessPolicy(access,organization,openPlatform,mapper,integrations, org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
    private final FoundationAccess.Subject member=new FoundationAccess.Subject(false,"7","9");
    @AfterEach void clear(){TenantContext.clear();ChannelContext.clear();ServicePrincipalContext.clear();}
    private SessionDO session(){var row=new SessionDO();row.setTenantId(7L);row.setSessionId("business-session");row.setChannelId(8L);row.setIntegrationId("integration");row.setScopeType("open-platform:integration");row.setBusinessScopeId("store");return row;}
    /** 群治理凭证、后台成员和个人资源入口均不替代群当前成员读取权。 */
    @Test void personalAccessRejectsGroupBeforeAnyAuthorizationOrResourceRead(){
        var group=session();group.setGroupBindingId(31L);group.setChannelId(null);
        assertThrows(ApiException.class,()->policy.requireCurrent(view(group),"read"));
        assertThrows(ApiException.class,()->policy.requireCurrent(view(group),"write"));
        assertThrows(ApiException.class,()->policy.requireCurrentRead(view(group),member));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("principal","7","integration",2,Set.of("sessions.read","groups.read")));
        assertThrows(ApiException.class,()->policy.requireCurrent(view(group),"read"));
        verifyNoInteractions(access,organization,openPlatform,mapper,integrations);
    }
    @Test void memberChecksCurrentSessionActionsAndRegistersOnlyTheCreatedSession(){
        when(access.currentSubject()).thenReturn(member);when(access.visibleSessionIds(member,"read")).thenReturn(List.of("business-session"));
        policy.requireCreation("7");policy.requireCurrent(view(session()),"read");policy.requireCurrent(view(session()),"write");policy.registerCreated("7","business-session");
        assertEquals(List.of("business-session"),policy.visibleSessionIds("7"));
        verify(access).require(member,"foundation.sessions.write");verify(access).requireSession(member,"business-session","read");verify(access).requireSession(member,"business-session","write");verify(organization).registerSessionOwnership("business-session");verifyNoInteractions(openPlatform,mapper,integrations);
    }
    @Test void tenantContextAloneAndPlatformAccountDoNotGrantTenantSessionAccess(){
        TenantContext.setTenantId("7");when(access.currentSubject()).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,"请先登录"));
        assertThrows(ApiException.class,()->policy.requireCurrent(view(session()),"read"));assertThrows(ApiException.class,()->policy.requireCreation("7"));
        doReturn(new FoundationAccess.Subject(true,null,"9")).when(access).currentSubject();
        assertThrows(ApiException.class,()->policy.requireCurrent(view(session()),"read"));verifyNoInteractions(organization,openPlatform,mapper,integrations);
    }
    /** 前置身份检查不读取IAM，认证渠道/服务主体仍在后续真实会话检查中复验当前访问权。 */
    @Test void existingIdentityChecksOnlyTrustedAuthenticationAndExactTenantWithoutIamReads(){
        TenantContext.setTenantId("7");when(access.currentSubject()).thenReturn(member);
        policy.requireExistingIdentity("7");verify(access).currentSubject();verifyNoMoreInteractions(access);
        ChannelContext.setChannelId("8");policy.requireExistingIdentity("7");ChannelContext.clear();
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("principal","7","integration",2,Set.of("sessions.write")));
        policy.requireExistingIdentity("7");
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("foreign","8","integration",2,Set.of("sessions.write")));
        assertEquals(403,assertThrows(ApiException.class,()->policy.requireExistingIdentity("7")).getStatus().value());
        TenantContext.clear();assertEquals(401,assertThrows(ApiException.class,()->policy.requireExistingIdentity("7")).getStatus().value());
        verifyNoMoreInteractions(access);verifyNoInteractions(organization,openPlatform,mapper,integrations);
    }
    @Test void channelIsRecheckedForEveryReadAndNeverReceivesMemberOwnership(){
        TenantContext.setTenantId("7");ChannelContext.setChannelId("8");when(mapper.activeChannel("7","8")).thenReturn(true);
        policy.requireCreation("7");policy.requireCurrent(view(session()),"read");policy.registerCreated("7","business-session");assertNull(policy.visibleSessionIds("7"));
        when(mapper.activeChannel("7","8")).thenReturn(false);assertThrows(ApiException.class,()->policy.requireCurrent(view(session()),"read"));
        when(mapper.activeChannel("7","8")).thenReturn(true);var other=session();other.setChannelId(18L);assertThrows(ApiException.class,()->policy.requireCurrent(view(other),"write"));
        TenantContext.setTenantId("17");assertThrows(ApiException.class,()->policy.requireCurrent(view(session()),"read"));verifyNoInteractions(access,organization,openPlatform,integrations);
    }
    /** SSE的显式成员身份仍须匹配真实Session租户，渠道和服务主体不能混用成员授权。 */
    @Test void subscriptionMemberRequiresExactSessionTenantAndSeparateAuthenticationBoundary(){
        policy.requireCurrentRead(view(session()),member);
        verify(access).requireSession(member,"business-session","read");
        assertThrows(ApiException.class,()->policy.requireCurrentRead(view(session()),new FoundationAccess.Subject(false,"8","9")));
        assertThrows(ApiException.class,()->policy.requireCurrentRead(view(session()),new FoundationAccess.Subject(true,null,"9")));
        assertThrows(ApiException.class,()->policy.requireCurrentRead(view(session()),null));
        ChannelContext.setChannelId("8");assertThrows(ApiException.class,()->policy.requireCurrentRead(view(session()),member));ChannelContext.clear();
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("principal","7","integration",2,Set.of("sessions.read")));
        assertThrows(ApiException.class,()->policy.requireCurrentRead(view(session()),member));
        verifyNoMoreInteractions(access);verifyNoInteractions(organization,openPlatform,mapper,integrations);
    }
    @Test void servicePrincipalUsesCurrentCredentialAndDedicatedProvenanceWithoutMemberOwnership(){
        var identity=new ServicePrincipalContext.Identity("principal","7","integration",2,Set.of("sessions.read","sessions.write"));ServicePrincipalContext.set(identity);
        var grant=new OpenPlatformAccessService.Access(identity);when(openPlatform.require(identity,"sessions.write")).thenReturn(grant);
        policy.requireCreation("7");policy.requireCurrent(view(session()),"write");policy.registerCreated("7","business-session");
        verify(openPlatform,times(2)).require(identity,"sessions.write");verify(integrations).requireBusinessScopeById("7","integration","store");
        assertThrows(ApiException.class,()->policy.visibleSessionIds("7"));verifyNoInteractions(access,organization,mapper);
    }
    private SessionView view(SessionDO row) {
        return SessionView.builder().tenantId(row.getTenantId()).sessionId(row.getSessionId())
                .channelId(row.getChannelId()).groupBindingId(row.getGroupBindingId())
                .integrationId(row.getIntegrationId()).scopeType(row.getScopeType())
                .businessScopeId(row.getBusinessScopeId()).build();
    }
}
