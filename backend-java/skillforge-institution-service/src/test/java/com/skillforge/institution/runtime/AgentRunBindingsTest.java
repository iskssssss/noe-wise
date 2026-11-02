package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.*;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.dto.request.*;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.workflow.WorkflowReferenceService;
import com.skillforge.core.knowledge.mapper.*;
import com.skillforge.core.knowledge.model.*;
import com.skillforge.core.workflow.mapper.*;
import com.skillforge.core.workflow.model.*;
import com.skillforge.institution.service.*;
import com.skillforge.institution.artifact.ArtifactMapper;
import com.skillforge.institution.team.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AgentRunBindingsTest {
    private final AgentRunBindingsMapper mapper=mock(AgentRunBindingsMapper.class);
    private final TeamService teams=mock(TeamService.class);
    private final AgentMapper agents=mock(AgentMapper.class);
    private final ArtifactMapper access=mock(ArtifactMapper.class);
    private final com.skillforge.core.foundation.access.FoundationAccess foundation=mock(com.skillforge.core.foundation.access.FoundationAccess.class);
    private final com.skillforge.core.foundation.access.FoundationAccess.Subject requester=new com.skillforge.core.foundation.access.FoundationAccess.Subject(false,"1","5");
    private final Map<String,AgentRunBindingsMapper.Row> rows=new HashMap<>();
    private final AgentDO primary=agent("11"),delegate=agent("12");
    private final com.skillforge.core.agent.deployment.AgentTemplateGrantService grants=mock(com.skillforge.core.agent.deployment.AgentTemplateGrantService.class);
    private final com.skillforge.core.agent.mapper.AgentDeploymentMapper deployments=mock(com.skillforge.core.agent.mapper.AgentDeploymentMapper.class);
    private final AgentRunBindings subject;
    private static final TenantId TENANT=new TenantId("1");
    private static final RunRef RUN=new RunRef(TENANT,"run:input-1");
    private static final TypedPayload INPUT=new TypedPayload("input.v1","{\"extraParameters\":{\"delegation_requester_id\":\"5\"},\"text\":\"request\"}");

    AgentRunBindingsTest() {
        when(mapper.insert(any())).thenAnswer(call->{var row=call.<AgentRunBindingsMapper.Row>getArgument(0);rows.putIfAbsent(row.runId,row);return 1;});
        when(mapper.find(any(),any())).thenAnswer(call->rows.get(call.getArgument(1)));
        when(agents.selectById(11L)).thenReturn(primary);when(agents.selectById(12L)).thenReturn(delegate);
        primary.setDelegationConfig(new AgentDelegationConfig(true,List.of("12")));
        when(foundation.canAccessResource(requester,"agent","11","execute")).thenReturn(true);
        when(foundation.canAccessResource(requester,"agent","12","execute")).thenReturn(true);
        when(foundation.canAccessResource(requester,"agent","11","read")).thenReturn(true);
        when(foundation.canAccessResource(requester,"agent","12","read")).thenReturn(true);
        when(foundation.withSubject(any(),any())).thenAnswer(call->call.<java.util.function.Supplier<?>>getArgument(1).get());
        subject=new AgentRunBindings(mapper,teams,mock(SessionDelegations.Store.class),mock(DataSource.class),
                new ObjectMapper().findAndRegisterModules(),agents,access,foundation, mock(com.skillforge.core.foundation.integration.IntegrationProvenanceService.class),
                deployments,grants);
        // 通过生产构造器注入的领域 Mapper 提供授权事实，不向服务反射注入执行旁路。
        when(mapper.endUserAvailable(eq(1L), anyString(), eq(9L))).thenReturn(true);
        when(mapper.channelTargetAvailable(eq(1L), anyLong(), anyLong())).thenReturn(true);
        for(String id:List.of("11","12")) {
            var member=member(id);when(teams.freezeAgent("1",id)).thenAnswer(call -> member(id, primary.getDelegationConfig()));
            when(teams.publicProfile(eq("1"), any(TeamService.FrozenMember.class))).thenAnswer(call -> profile(call.<TeamService.FrozenMember>getArgument(1).agentId()));
        }
        when(teams.freezePublished("1","studio")).thenReturn(new TeamService.FrozenTeam(
                new TeamDefinition("studio","工作室","","published",7,List.of(profile("11"),profile("12")),"11"),
                Map.of("11",member("11"),"12",member("12"))));
    }
    @Test void sourceReadAlsoChecksTheCurrentRunsPersistedRequester() {
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN,
                new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"),
                new RunRequester.EndUser(owner(false).requireUser()), Optional.empty(), new RunOriginRef(TENANT,"input:input-1")),INPUT);
        var currentRequester=new AgentRunBindings.Requester("6", "");
        var currentSubject=new com.skillforge.core.foundation.access.FoundationAccess.Subject(false,"1","6");
        assertThrows(SecurityException.class,()->subject.authorizeRead(binding,"11",currentRequester));
        verify(foundation).requireSession(currentSubject,"chat","read");
        when(foundation.canAccessResource(currentSubject,"agent","11","read")).thenReturn(true);
        assertDoesNotThrow(()->subject.authorizeRead(binding,"11",currentRequester));
        when(foundation.canAccessResource(requester,"agent","11","read")).thenReturn(false);
        assertThrows(SecurityException.class,()->subject.authorizeRead(binding,"11",currentRequester),
                "原来源请求者撤权不能被新请求者的权限覆盖");
    }

    @Test void ordinaryAgentFreezesAllowlistProfilesAndRestoresExactBinding() {
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        assertTrue(binding.scope().studio().isEmpty());assertEquals(Set.of("11","12"),binding.scope().members().keySet());
        assertEquals("private-12",binding.scope().members().get("12").systemPrompt());
        primary.setDelegationConfig(new AgentDelegationConfig(true,List.of("12","13")));
        assertEquals(binding,subject.find(RUN).orElseThrow());
        assertEquals(List.of("12"),subject.find(RUN).orElseThrow().scope().delegation().targetAgentIds());
        assertFalse(subject.targetSnapshot(binding,"12").canonicalJson().contains("private"));
    }

    @Test void groupDirectoryDoesNotFallThroughToTeamOrCreatePersonalRunBindings() {
        var ref=new GroupBindingRef(TENANT,"group-lifecycle-1");
        var owner=new SessionOwner(new SessionRef(TENANT,"group-session"),SessionSubject.group(ref),SessionTarget.group(ref));
        assertThrows(IllegalStateException.class,()->subject.primaryAgent(owner));
        assertThrows(IllegalStateException.class,()->subject.currentPrimaryAgent(owner));
        assertThrows(IllegalStateException.class,()->subject.requesterContext(owner));
        var operation=mock(java.util.function.Supplier.class);
        assertThrows(IllegalStateException.class,()->subject.withContinuationRequester(owner,operation));
        verifyNoInteractions(teams,mapper,deployments,grants,operation);
        assertTrue(rows.isEmpty());
    }

    /** 普通OPEN_INSTALL副本保留模板创建溯源，后续执行仍沿自身发布，不因该字段要求受控grant。 */
    @Test void ordinaryCopyWithTemplateOriginDoesNotAcquireControlledDeploymentSemantics() {
        primary.setTemplateId(901L);primary.setTemplateVersion("1.0.0");
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        assertTrue(binding.scope().authorizations().isEmpty());
        assertNull(binding.member().templateId());
        subject.authorize(binding);
        verify(grants,never()).requireFrozenChain(any(),anyLong(),anyLong(),anyString());
    }

    /** 原Run只认受理时冻结的grant；当前部署改挂新grant不能补回已撤销的执行权。 */
    @Test void controlledContinuationChecksOriginalGrantWithoutSelectingCurrentDeployment() {
        var binding=controlledBinding();
        assertEquals(new AgentRunBindings.DeploymentAuthorization(701,1,801,1,chain()),binding.scope().authorizations().get("11"));
        clearInvocations(deployments,grants);
        doThrow(new com.skillforge.core.shared.exception.ApiException(
                org.springframework.http.HttpStatus.FORBIDDEN,"grant_revoked","原授权已撤销",false)).when(grants).requireFrozenChain(chain(),1,901,"execute");
        assertEquals("run_deployment_authorization_revoked",assertThrows(
                com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                ()->subject.authorize(binding)).reasonCode());
        verify(grants).requireFrozenChain(chain(),1,901,"execute");
        verifyNoInteractions(deployments);
        assertEquals(binding,subject.find(RUN).orElseThrow());
    }

    @Test void currentNarrowingDoesNotReplaceOrAutomaticallyRejectTheOriginalFrozenRevision() {
        var binding=controlledBinding();
        subject.authorize(binding);
        verify(grants,atLeastOnce()).requireFrozenChain(chain(),1,901,"execute");
        assertEquals(1,binding.scope().authorizations().get("11").grantChain().hops().getFirst().revision());
    }

    @Test void delegatedChildInheritsOriginalChainWithoutReadingReplacementDeployment() {
        var original=member("12",AgentDelegationConfig.disabled());
        var controlled=new TeamService.FrozenMember(original.agentId(),original.name(),original.systemPrompt(),original.roleDescription(),
                original.executionConfig(),original.modelId(),original.modelRevision(),original.mounts(),original.agentRevisionId(),
                original.agentRevision(),original.delegationConfig(),"1.0.0",901L);
        when(teams.freezeAgent("1","12")).thenReturn(controlled);
        when(deployments.findAgent(1,12)).thenReturn(new com.skillforge.core.agent.mapper.AgentDeploymentMapper.Row(
                702L,"integration-1",1L,12L,901L,801L,1,"external-child","fingerprint","{}",1,Instant.EPOCH,chainJson()));
        var parent=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        clearInvocations(deployments,grants);
        var child=subject.freezeChild(entry(parent,"12"));
        assertEquals(parent.scope().authorizations(),child.scope().authorizations());
        assertEquals(chain(),child.scope().authorizations().get("12").grantChain());
        verifyNoInteractions(deployments);
        verify(grants).requireFrozenChain(chain(),1,901,"execute");
    }

    /** 无法取得当前授权事实是技术失败，不能转换成确认撤权或使用新版本继续。 */
    @Test void controlledContinuationPreservesTechnicalFailures() {
        var binding=controlledBinding();
        var unavailable=new IllegalStateException("storage_unavailable");
        doThrow(unavailable).when(grants).requireFrozenChain(chain(),1,901,"execute");
        assertSame(unavailable,assertThrows(IllegalStateException.class,()->subject.authorize(binding)));
        var serverFailure=new com.skillforge.core.shared.exception.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"iam_unavailable","授权读取暂时不可用",false);
        doThrow(serverFailure).when(grants).requireFrozenChain(chain(),1,901,"execute");
        assertSame(serverFailure,assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->subject.authorize(binding)));
    }
    @Test void serviceRunKeepsTrustedPrincipalAndAgentPublicationAcrossRecovery() {
        var principal=new PrincipalRef.ServicePrincipal(TENANT,"service-1","integration-1",3);
        when(mapper.serviceRequesterAvailable(1L,"service-1","integration-1",3)).thenReturn(true);
        var forged=new TypedPayload("input.v1","{\"extraParameters\":{\"delegation_requester_id\":\"99\"}}");
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),forged,principal);
        assertEquals(Optional.of(principal),binding.scope().requester().servicePrincipal());
        assertEquals(111L,binding.member().agentRevisionId());
        assertEquals(1,binding.member().agentRevision());
        assertEquals(binding,subject.find(RUN).orElseThrow());
        when(mapper.serviceRequesterAvailable(1L,"service-1","integration-1",3)).thenReturn(false);
        assertThrows(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                () -> subject.authorize(binding));
    }
    @Test void confirmedRevocationHasTypedTerminalSignalButStorageFailureDoesNot() {
        var input = new TypedPayload("input.v1",
                "{\"extraParameters\":{\"delegation_channel_id\":\"8\"},\"text\":\"request\"}");
        when(access.canChannelAccess("1", "8", "chat")).thenReturn(true);
        var binding = subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),input);
        when(access.canChannelAccess("1", "8", "chat")).thenReturn(false);
        assertEquals("delegation_requester_revoked", assertThrows(
                com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                () -> subject.authorize(binding)).reasonCode());
        when(access.canChannelAccess("1", "8", "chat")).thenReturn(true);
        when(mapper.channelTargetAvailable(1L, 8L, 11L)).thenReturn(false);
        assertEquals("delegation_channel_target_revoked", assertThrows(
                com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                () -> subject.authorize(binding)).reasonCode());
        when(mapper.channelTargetAvailable(1L, 8L, 11L)).thenReturn(true);
        when(mapper.endUserAvailable(1L, "chat", 9L)).thenReturn(false);
        assertEquals("delegation_end_user_revoked", assertThrows(
                com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                () -> subject.authorize(binding)).reasonCode());
        var unavailable = new IllegalStateException("storage_unavailable");
        when(access.canChannelAccess("1", "8", "chat")).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> subject.authorize(binding)));
    }
    @Test void studioNarrowsScopeAndNeverAddsAgentPermission() {
        var binding=subject.freezePrimary(new RunIdentity(owner(true), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(true)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(true)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        assertEquals(7,binding.scope().studio().orElseThrow().revision());
        subject.authorizeTarget(binding,"12");
        primary.setDelegationConfig(AgentDelegationConfig.disabled());
        subject.authorizeTarget(binding,"12");
        subject.authorizeRead(binding,"12");
    }
    @Test void readIgnoresExecutionAllowlistRevocationButKeepsDisclosureAuthorization() {
        var binding=subject.freezePrimary(new RunIdentity(owner(true), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(true)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(true)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        primary.setDelegationConfig(new AgentDelegationConfig(true,List.of()));
        subject.authorizeRead(binding,"12");
        subject.authorizeTarget(binding,"12");
        delegate.setStatus("disabled");
        assertThrows(SecurityException.class,()->subject.authorizeRead(binding,"12"));
        delegate.setStatus("published");
        doThrow(new SecurityException("member_removed")).when(teams).requireEnabledMember("1","studio",7,"12");
        assertThrows(SecurityException.class,()->subject.authorizeRead(binding,"12"));
        doNothing().when(teams).requireEnabledMember("1","studio",7,"12");
        doThrow(new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"session_revoked","会话访问已撤销",false)).when(foundation).requireSession(requester,"chat","read");
        assertThrows(SecurityException.class,()->subject.authorizeRead(binding,"12"));
    }
    @Test void childAgentTargetMatchesExecutorAndRetainsOwnPrivateRole() {
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        var child=subject.freezeChild(entry(binding,"12"));
        assertEquals(SessionTarget.agent(new AgentId("12")),child.owner().target());
        assertEquals("private-12",child.member().systemPrompt());assertEquals("delegation:d1",child.owner().sessionRef().sessionId());
        assertEquals(Optional.of(RUN),child.parentRunRef());
        assertThrows(SecurityException.class,()->subject.authorizeTarget(child,"11"));
    }
    @Test void publishedTargetChangeKeepsRunScopeWhileRevokedRequesterCannotStartWork() {
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        primary.setDelegationConfig(new AgentDelegationConfig(true,List.of()));
        assertEquals("12",subject.freezeChild(entry(binding,"12")).actor().value());
        primary.setDelegationConfig(new AgentDelegationConfig(true,List.of("12")));
        doThrow(new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"session_revoked","会话访问已撤销",false)).when(foundation).requireSession(requester,"chat","write");
        assertThrows(SecurityException.class,()->subject.authorizeTarget(binding,"12"));
    }
    @Test void disabledAgentHasNoFrozenDelegationTargets() {
        primary.setDelegationConfig(new AgentDelegationConfig(false,List.of("12")));
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        assertEquals(Set.of("11"),binding.scope().members().keySet());
        assertThrows(SecurityException.class,()->subject.authorizeTarget(binding,"12"));
    }
    @Test void emptyDirectoryReasonUsesFrozenPublicationWhileAgentDisablementStaysLive() {
        primary.setDelegationConfig(AgentDelegationConfig.disabled());
        var disabled=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        primary.setDelegationConfig(new AgentDelegationConfig(true,List.of("12")));
        assertEquals(SessionDelegations.EmptyTargetReason.DISABLED,subject.emptyTargetReason(disabled));
        var next=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), new RunRef(TENANT,"run:next"), new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        delegate.setStatus("disabled");
        assertEquals(SessionDelegations.EmptyTargetReason.NO_AUTHORIZED_TARGETS,subject.emptyTargetReason(next));
        assertEquals(Set.of("11"),subject.find(RUN).orElseThrow().scope().members().keySet());
        assertEquals(Set.of("11","12"),subject.find(new RunRef(TENANT,"run:next")).orElseThrow().scope().members().keySet());
    }
    @Test void revokedParentAuthorizationIsRejectedInsteadOfBeingClassifiedAsEmptyDirectory() {
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        doThrow(new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"session_revoked","会话访问已撤销",false)).when(foundation).requireSession(requester,"chat","write");

        assertThrows(SecurityException.class,()->subject.emptyTargetReason(binding));
    }
    @Test void forgedTargetSnapshotIsRejectedBeforeAdmission() {
        var binding=subject.freezePrimary(new RunIdentity(owner(true), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(true)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(true)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        assertThrows(SecurityException.class,()->subject.freezeChild(entry(binding,"99")));
    }
    @Test void currentExecuteDenialNeverPersistsAHostRunBinding() {
        when(foundation.canAccessResource(requester,"agent","11","execute")).thenReturn(false);
        assertThrows(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                () -> subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT));
        assertTrue(rows.isEmpty());
        verify(mapper,never()).insert(any());
    }

    @Test void executionWithdrawalPreservesReadOnlyHistoryButStopsContinuationBeforeDispatch() {
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        when(mapper.findLatestPrimary("1","chat","AGENT","11",9)).thenReturn(rows.get(RUN.runId()));
        doThrow(new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"write_revoked","没有继续执行权限",false))
                .when(foundation).requireSession(requester,"chat","write");
        var operation=mock(java.util.function.Supplier.class);
        subject.authorizeRead(binding,"12");
        assertThrows(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,()->subject.authorize(binding));
        assertThrows(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                ()->subject.withContinuationRequester(owner(false),operation));
        verifyNoInteractions(operation);
        assertEquals(binding,subject.find(RUN).orElseThrow());
        when(foundation.canAccessResource(requester,"agent","12","read")).thenReturn(false);
        assertThrows(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,()->subject.authorizeRead(binding,"12"));
    }

    @Test void memberAndTeamContinuationsResolveRealWorkflowKnowledgeSlotsUnderPersistedSubject() {
        doCallRealMethod().when(foundation).withSubject(any(),any());
        doCallRealMethod().when(foundation).currentSubject();
        doCallRealMethod().when(foundation).requireResource(anyString(),anyString(),anyString());
        deploymentSlots();
        try(var jwt=mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            jwt.when(cn.dev33.satoken.stp.StpUtil::isLogin).thenReturn(false);
            for(boolean studio:List.of(false,true)) {
                var owner=owner(studio);
                var initial=new RunRef(TENANT,"run:initial-"+studio);
                var original=subject.freezePrimary(new RunIdentity(owner, new AgentId("11"), new InputId("input-1"), initial, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
                when(mapper.findLatestPrimary("1","chat",owner.target().kind().name(),owner.target().id(),9))
                        .thenReturn(rows.get(initial.runId()));
                var next=new RunRef(TENANT,"run:continued-"+studio);
                var continued=subject.withContinuationRequester(owner,
                        ()->subject.freezePrimary(new RunIdentity(owner, new AgentId("11"), new InputId("input-1"), next, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT));
                assertEquals(2,continued.member().mounts().mounts().size());
                assertEquals(requester.memberId(),continued.scope().requester().requesterId());
                assertEquals(original,subject.find(initial).orElseThrow());
                assertThrows(com.skillforge.core.shared.exception.ApiException.class,foundation::currentSubject);
                assertNull(com.skillforge.common.context.TenantContext.getTenantId());
            }
            when(foundation.canAccessResource(requester,"workflow","403","execute")).thenReturn(false);
            var denied=new RunRef(TENANT,"run:revoked-workflow");
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                    ()->subject.withContinuationRequester(owner(false),()->subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), denied, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT)));
            assertFalse(rows.containsKey(denied.runId()));
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,foundation::currentSubject);
            assertNull(com.skillforge.common.context.TenantContext.getTenantId());
        }
    }

    @Test void continuationCannotInventRequesterFromTenantOrAcceptDifferentPersistedOwner() {
        var operation=mock(java.util.function.Supplier.class);
        assertThrows(SecurityException.class,()->subject.withContinuationRequester(owner(false),operation));
        var binding=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
        when(mapper.findLatestPrimary("1","other","AGENT","11",9)).thenReturn(rows.get(RUN.runId()));
        var foreign=SessionOwner.user(new SessionRef(TENANT,"other"),binding.owner().target(),new EndUserId(9));
        assertThrows(SecurityException.class,()->subject.withContinuationRequester(foreign,operation));
        verifyNoInteractions(operation);
        assertNull(com.skillforge.common.context.TenantContext.getTenantId());
    }

    @Test void channelContinuationHasOnlyTenantFixedSlotExecutionAndWithdrawalStopsNewFreeze() {
        deploymentSlots();
        doCallRealMethod().when(foundation).currentSubject();
        doCallRealMethod().when(foundation).requireResource(anyString(),anyString(),anyString());
        var resources=new InstitutionResourceAccess(foundation, org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
        var channelInput=new TypedPayload("input.v1","{\"extraParameters\":{\"delegation_channel_id\":\"8\"}}");
        when(access.canChannelAccess("1","8","chat")).thenReturn(true);
        try(var jwt=mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            jwt.when(cn.dev33.satoken.stp.StpUtil::isLogin).thenReturn(false);
            var original=subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),channelInput);
            when(mapper.findLatestPrimary("1","chat","AGENT","11",9)).thenReturn(rows.get(RUN.runId()));
            var next=new RunRef(TENANT,"run:channel-next");
            var continued=subject.withContinuationRequester(owner(false),()->{
                assertEquals("8",com.skillforge.common.context.ChannelContext.getChannelId());
                assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->resources.require("knowledge","503","read"));
                assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->resources.require("workflow","403","manage"));
                assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->resources.require("agent","11","execute"));
                String tenant=com.skillforge.common.context.TenantContext.getTenantId();
                try {
                    com.skillforge.common.context.TenantContext.setTenantId("2");
                    assertThrows(SecurityException.class,()->resources.require("knowledge","503","execute"));
                } finally { com.skillforge.common.context.TenantContext.setTenantId(tenant); }
                return subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), next, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),channelInput);
            });
            assertEquals(2,continued.member().mounts().mounts().size());
            assertNull(com.skillforge.common.context.ChannelContext.getChannelId());
            assertEquals(original,subject.find(RUN).orElseThrow());
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->resources.require("knowledge","503","execute"));
            assertThrows(IllegalStateException.class,()->subject.withContinuationRequester(owner(false),()->{
                assertEquals("8",com.skillforge.common.context.ChannelContext.getChannelId());
                resources.require("knowledge","503","execute");
                throw new IllegalStateException("slot_resolution_failed");
            }));
            assertNull(com.skillforge.common.context.ChannelContext.getChannelId());
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->resources.require("knowledge","503","execute"));
            when(access.canChannelAccess("1","8","chat")).thenReturn(false);
            var operation=mock(java.util.function.Supplier.class);
            assertThrows(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                    ()->subject.withContinuationRequester(owner(false),operation));
            verifyNoInteractions(operation);
            assertNull(com.skillforge.common.context.TenantContext.getTenantId());
            verify(foundation,never()).withSubject(any(),any());
        }
    }

    @Test void channelContinuationRestoresPriorChannelOnFailureAndChecksEndUserBeforeProjection() {
        var channelInput=new TypedPayload("input.v1","{\"extraParameters\":{\"delegation_channel_id\":\"8\"}}");
        when(access.canChannelAccess("1","8","chat")).thenReturn(true);
        subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),channelInput);
        when(mapper.findLatestPrimary("1","chat","AGENT","11",9)).thenReturn(rows.get(RUN.runId()));
        com.skillforge.common.context.ChannelContext.setChannelId("prior-channel");
        try {
            assertThrows(IllegalStateException.class,()->subject.withContinuationRequester(owner(false),()->{
                assertEquals("8",com.skillforge.common.context.ChannelContext.getChannelId());
                throw new IllegalStateException("projection_failed");
            }));
            assertEquals("prior-channel",com.skillforge.common.context.ChannelContext.getChannelId());
            when(mapper.endUserAvailable(1L,"chat",9L)).thenReturn(false);
            var operation=mock(java.util.function.Supplier.class);
            assertThrows(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class,
                    ()->subject.withContinuationRequester(owner(false),operation));
            verifyNoInteractions(operation);
            assertEquals("prior-channel",com.skillforge.common.context.ChannelContext.getChannelId());
        } finally { com.skillforge.common.context.ChannelContext.clear(); }
    }

    /** 真实部署槽位消费者和资源授权入口，Catalog/资源行使用固定领域 fixture。 */
    private CapabilityMountManagementService deploymentSlots() {
        var catalog=mock(CapabilityCatalogService.class);
        var workflows=mock(WorkflowInstanceMapper.class);
        var workflowVersions=mock(WorkflowDefinitionVersionMapper.class);
        var knowledge=mock(KnowledgeBaseMapper.class);
        var revisions=mock(KnowledgeBaseRevisionMapper.class);
        var resources=new InstitutionResourceAccess(foundation, org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
        var service=new CapabilityMountManagementService(new WorkflowReferenceService(workflows, workflowVersions, mock(com.skillforge.core.workflow.mapper.WorkflowContainerMapper.class)),
                new KnowledgeReferenceService(knowledge, revisions), mock(com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService.class),catalog,
                mock(CapabilityCatalogDatabaseContext.class),mock(com.skillforge.core.agent.AgentService.class),resources);
        for(var type:List.of(CapabilityType.WORKFLOW,CapabilityType.KNOWLEDGE_SOURCE)) {
            long definition=type==CapabilityType.WORKFLOW?300:500;
            var definitionView=new CapabilityDefinitionView(definition,1L,type,"slot-"+definition,"Slot","","TENANT","PUBLISHED",null,1L,null,null);
            var version=new CapabilityVersionView(definition+1,definition,1L,type,"v1","Slot","","TENANT",Map.of(),"hash","PUBLISHED",null,null);
            when(catalog.getDefinition(any(),eq(definition))).thenReturn(definitionView);
            when(catalog.getCurrentPublishedVersion(any(),eq(definition))).thenReturn(version);
        }
        var instance=new WorkflowInstanceDO();instance.setId(401L);instance.setTenantId(1L);instance.setStatus("published");instance.setDefinitionVersionId(402L);instance.setInstallationRevision(1L);
        var workflowVersion=new WorkflowDefinitionVersionDO();workflowVersion.setId(402L);workflowVersion.setTenantId(1L);workflowVersion.setContainerId(403L);workflowVersion.setCapabilityVersionId(301L);
        when(workflows.selectOne(any())).thenReturn(instance);
        when(workflowVersions.selectOne(any())).thenReturn(workflowVersion);
        var revision=new KnowledgeBaseRevisionDO();revision.setId(502L);revision.setTenantId(1L);revision.setKbId(503L);revision.setCapabilityVersionId(501L);revision.setStatus("published");
        var base=new KnowledgeBaseDO();base.setId(503L);base.setTenantId(1L);base.setActiveRevisionId(502L);
        when(revisions.selectList(any())).thenReturn(List.of(revision));when(knowledge.selectOne(any())).thenReturn(base);
        when(foundation.canAccessResource(requester,"workflow","403","execute")).thenReturn(true);
        when(foundation.canAccessResource(requester,"knowledge","503","execute")).thenReturn(true);
        when(teams.freezeAgent(eq("1"),anyString())).thenAnswer(call->{
            String id=call.getArgument(1);
            var workflow=service.resolveDeploymentBinding(1,Long.parseLong(id),"workflow-slot",1,
                    new CapabilityMountRequest(CapabilityType.WORKFLOW,300L,301L,null,null,new WorkflowMountConfigRequest("401",402L),null,0));
            var kb=service.resolveDeploymentBinding(1,Long.parseLong(id),"knowledge-slot",1,
                    new CapabilityMountRequest(CapabilityType.KNOWLEDGE_SOURCE,500L,501L,null,null,null,
                            new KnowledgeMountConfigRequest(CapabilityMount.KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE,null),0));
            return new TeamService.FrozenMember(id,"成员"+id,"private-"+id,"公开角色",com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(),"model",3,
                    new CapabilityMountSetResult(1L,Long.valueOf(id),1,List.of(workflow,kb)),Long.valueOf(id)+100,1,AgentDelegationConfig.disabled());
        });
        when(teams.freezePublished("1","studio")).thenAnswer(call->new TeamService.FrozenTeam(
                new TeamDefinition("studio","工作室","","published",7,List.of(profile("11")),"11"),Map.of("11",teams.freezeAgent("1","11"))));
        return service;
    }
    private SessionDelegations.Entry entry(AgentRunBindings.Binding binding,String target) {
        var parent=new RunIdentity(binding.owner(), binding.actor(), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("input-1"),Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((binding.owner()).requireUser()), java.util.Optional.empty(), new RunOriginRef((binding.owner()).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value()));
        return new SessionDelegations.Entry("d1",parent,"op",new SessionDelegations.Target("member:"+target,new AgentId(target),"成员","公开角色",List.of(),subject.targetSnapshot(binding,target)),
                new SessionDelegations.Task("目标","背景",List.of(),"报告"),new RunRef(TENANT,"child:d1"),new InputId("child-input"),Optional.empty(),
                SessionDelegations.State.QUEUED,Optional.empty(),Optional.empty(),1,Instant.EPOCH,Instant.EPOCH);
    }
    private static SessionOwner owner(boolean studio){return SessionOwner.user(new SessionRef(TENANT,"chat"),studio?SessionTarget.team("studio"):SessionTarget.agent(new AgentId("11")),new EndUserId(9));}
    private AgentRunBindings.Binding controlledBinding() {
        var original=member("11",AgentDelegationConfig.disabled());
        var controlled=new TeamService.FrozenMember(original.agentId(),original.name(),original.systemPrompt(),original.roleDescription(),
                original.executionConfig(),original.modelId(),original.modelRevision(),original.mounts(),original.agentRevisionId(),
                original.agentRevision(),original.delegationConfig(),"1.0.0",901L);
        when(teams.freezeAgent("1","11")).thenReturn(controlled);
        when(deployments.findAgent(1,11)).thenReturn(new com.skillforge.core.agent.mapper.AgentDeploymentMapper.Row(
                701L,"integration-1",1L,11L,901L,801L,1,"external-agent","fingerprint","{}",1,Instant.EPOCH,chainJson()));
        return subject.freezePrimary(new RunIdentity(owner(false), new AgentId("11"), new InputId("input-1"), RUN, new TraceEnvelope("trace","span",new InputId("root-1"),Optional.empty()), new ResultRef("verified-fixture"), new RunRequester.EndUser((owner(false)).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner(false)).sessionRef().tenantId(), "input:" + (new InputId("input-1")).value())),INPUT);
    }
    private static com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantChain chain() {
        return new com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantChain(901L,"platform",null,
                List.of(new com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantHop(801L,1,"platform",null,1L,null,null,null,null)));
    }
    private static String chainJson() {
        try{return new ObjectMapper().writeValueAsString(chain());}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException(failure);}
    }
    private static AgentDO agent(String id){var a=new AgentDO();a.setId(Long.valueOf(id));a.setTenantId(1L);a.setStatus("published");return a;}
    private static TeamService.FrozenMember member(String id){return member(id,new AgentDelegationConfig(true,List.of("12")));}
    private static TeamService.FrozenMember member(String id,AgentDelegationConfig delegation){return new TeamService.FrozenMember(id,"成员"+id,"private-"+id,"公开角色",com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(),"model",3,new CapabilityMountSetResult(1L,Long.valueOf(id),1,List.of()), Long.valueOf(id)+100, 1, delegation);}
    private static TeamDefinition.Member profile(String id){return new TeamDefinition.Member(id,"version","成员"+id,"公开角色",List.of());}
}
