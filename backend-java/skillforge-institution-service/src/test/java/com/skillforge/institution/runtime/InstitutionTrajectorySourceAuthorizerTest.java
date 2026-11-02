package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.core.chat.trajectory.TrajectorySourceAuthorizer.Source;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 来源身份与当前历史读权独立复核，不把父产品会话权限或历史快照当成成员权限。 */
class InstitutionTrajectorySourceAuthorizerTest {
    private static final TenantId TENANT = new TenantId("7");
    private static final AgentId PRIMARY = new AgentId("101");
    private static final AgentId MEMBER = new AgentId("102");
    private static final EndUserId USER = new EndUserId(8);
    private static final SessionOwner ORIGIN = SessionOwner.user(new SessionRef(TENANT, "product-session"), PRIMARY, USER);
    private static final SessionOwner CHILD = SessionOwner.user(new SessionRef(TENANT, "delegation:member"), MEMBER, USER);
    private static final RunRef PARENT_RUN = new RunRef(TENANT, "parent-run");
    private static final RunRef CHILD_RUN = new RunRef(TENANT, "member-run");
    private static final TrajectoryQuery.Resource PARENT = new TrajectoryQuery.Resource(7, "product-session", "root-input");
    private static final Source SOURCE = new Source(7, "delegation:member", "root-input", "member-run", "102", "parent-run", "DELEGATION");

    private final FoundationAccess foundation = mock(FoundationAccess.class);
    private final SessionObservationAuthorizer scopes = new SessionObservationAuthorizer(foundation);
    private final SessionService sessions = mock(SessionService.class);
    private final AgentRunBindings bindings = mock(AgentRunBindings.class);
    private final ExecutionStore executions = mock(ExecutionStore.class);
    private final AgentRunBindings.Binding binding = mock(AgentRunBindings.Binding.class);
    private final AgentRunBindings.Scope bindingScope = mock(AgentRunBindings.Scope.class);
    private final InstitutionTrajectorySourceAuthorizer subject = new InstitutionTrajectorySourceAuthorizer(scopes, sessions, bindings, executions);
    private SessionObservationScope scope;

    @BeforeEach
    void fixture() {
        var reader = new FoundationAccess.Subject(false, "7", "99");
        when(foundation.currentSubject()).thenReturn(reader);
        scope = scopes.tenant();
        when(sessions.require("7", "product-session")).thenReturn(SessionView.builder()
                .tenantId(7L).sessionId("product-session").agentId(101L).endUserId(8L).build());
        run(identity(CHILD_RUN, CHILD, MEMBER));
        when(bindings.find(CHILD_RUN)).thenReturn(Optional.of(binding));
        when(binding.owner()).thenReturn(CHILD);
        when(binding.actor()).thenReturn(MEMBER);
        when(binding.role()).thenReturn(AgentRunBindings.Role.DELEGATE);
        when(binding.scope()).thenReturn(bindingScope);
        when(bindingScope.origin()).thenReturn(ORIGIN);
        when(binding.parentRunRef()).thenReturn(Optional.of(PARENT_RUN));
    }

    @Test
    void completedMemberUsesCurrentHistoricalReadInsteadOfExecutionAuthorization() {
        assertTrue(subject.canRead(scope, PARENT, SOURCE));
        verify(bindings).authorizeRead(binding, "102");
        verify(bindings, never()).authorize(any());
        verify(foundation).requireSession(any(), org.mockito.ArgumentMatchers.eq("product-session"), org.mockito.ArgumentMatchers.eq("read"));
    }

    @Test
    void readRevocationIsReevaluatedOnEverySourceCheckAndDoesNotBecomeCachedPermission() {
        assertTrue(subject.canRead(scope, PARENT, SOURCE));
        doThrow(new SecurityException("member_read_revoked")).when(bindings).authorizeRead(binding, "102");
        assertFalse(subject.canRead(scope, PARENT, SOURCE));
        doNothing().when(bindings).authorizeRead(binding, "102");
        assertTrue(subject.canRead(scope, PARENT, SOURCE));
        verify(bindings, times(3)).authorizeRead(binding, "102");
    }

    @Test
    void parentOwnerActorAndSourceRootMustMatchBeforeMemberPermissionIsRead() {
        when(bindingScope.origin()).thenReturn(SessionOwner.user(new SessionRef(TENANT, "other-session"), PRIMARY, USER));
        assertFalse(subject.canRead(scope, PARENT, SOURCE));
        when(bindingScope.origin()).thenReturn(ORIGIN);
        when(binding.actor()).thenReturn(PRIMARY);
        assertFalse(subject.canRead(scope, PARENT, SOURCE));
        when(binding.actor()).thenReturn(MEMBER);
        when(binding.parentRunRef()).thenReturn(Optional.of(new RunRef(TENANT, "other-parent")));
        assertFalse(subject.canRead(scope, PARENT, SOURCE));
        when(binding.parentRunRef()).thenReturn(Optional.of(PARENT_RUN));
        assertFalse(subject.canRead(scope, PARENT,
                new Source(7, SOURCE.sessionId(), "other-root", SOURCE.runId(), SOURCE.agentId(), SOURCE.parentRunId(), "DELEGATION")));
        verify(bindings, never()).authorizeRead(any(), any());
    }

    @Test
    void unsupportedOriginMissingBindingAndCrossTenantSourceRemainRestricted() {
        assertFalse(subject.canRead(scope, PARENT, new Source(8, SOURCE.sessionId(), SOURCE.rootInputId(), SOURCE.runId(), SOURCE.agentId(), SOURCE.parentRunId(), "DELEGATION")));
        assertFalse(subject.canRead(scope, PARENT, new Source(7, SOURCE.sessionId(), SOURCE.rootInputId(), SOURCE.runId(), SOURCE.agentId(), SOURCE.parentRunId(), "UNKNOWN")));
        when(bindings.find(CHILD_RUN)).thenReturn(Optional.empty());
        assertFalse(subject.canRead(scope, PARENT, SOURCE));
        verify(bindings, never()).authorizeRead(any(), any());
    }

    @Test
    void internalProgramAndWorkflowSourcesOnlyInheritExactAlreadyCheckedParentOwnerAndActor() {
        var internal = new RunRef(TENANT, "internal-run");
        run(identity(internal, CHILD, MEMBER));
        for (String kind : new String[] { "PROGRAM", "WORKFLOW_NODE" })
            assertTrue(subject.canRead(scope, PARENT,
                    new Source(7, CHILD.sessionRef().sessionId(), "root-input", internal.runId(), "102", CHILD_RUN.runId(), kind)));
        run(identity(CHILD_RUN, ORIGIN, PRIMARY));
        assertFalse(subject.canRead(scope, PARENT,
                new Source(7, CHILD.sessionRef().sessionId(), "root-input", internal.runId(), "102", CHILD_RUN.runId(), "PROGRAM")));
        verifyNoInteractions(bindings);
    }

    @Test
    void rootProductOwnerKeepsItsExistingSessionAuthorization() {
        run(identity(PARENT_RUN, ORIGIN, PRIMARY));
        assertTrue(subject.canRead(scope, PARENT,
                new Source(7, ORIGIN.sessionRef().sessionId(), "root-input", PARENT_RUN.runId(), "101", null, null)));
        verifyNoInteractions(bindings);
    }

    @Test
    void platformScopeCannotUseTheInstitutionImplementationAsAPrivilegeFallback() {
        var reader = new FoundationAccess.Subject(true, null, "100");
        when(foundation.currentSubject()).thenReturn(reader);
        var platform = scopes.platformAdmin();
        assertFalse(subject.canRead(platform, PARENT, SOURCE));
        verifyNoInteractions(sessions, bindings, executions);
    }

    @Test
    void groupSourceRetainsRealGroupOwnerAndNeverInventsAnEndUser() {
        var group = new GroupBindingRef(TENANT, "77");
        var origin = new SessionOwner(ORIGIN.sessionRef(), SessionSubject.group(group), SessionTarget.group(group));
        var child = new SessionOwner(CHILD.sessionRef(), SessionSubject.group(group), SessionTarget.group(group));
        when(sessions.require("7", "product-session")).thenReturn(SessionView.builder()
                .tenantId(7L).sessionId("product-session").groupBindingId(77L).build());
        when(binding.owner()).thenReturn(child);
        when(bindingScope.origin()).thenReturn(origin);
        var identity = new RunIdentity(child, MEMBER, new InputId("child-input"), CHILD_RUN,
                new TraceEnvelope("trace", "span", new InputId("root-input"), Optional.empty()),
                new ResultRef("identity"), new RunRequester.EndUser(USER), Optional.empty(), new RunOriginRef(TENANT, "origin"));
        run(identity);
        assertTrue(subject.canRead(scope, PARENT, SOURCE));
        verify(bindings).authorizeRead(binding, "102");
    }

    @Test
    void technicalFailureIsNotSilentlyTurnedIntoAnAuthorizationDenial() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "member_read_denied")).when(bindings).authorizeRead(binding, "102");
        assertFalse(subject.canRead(scope, PARENT, SOURCE));
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "permission_store_unavailable")).when(bindings).authorizeRead(binding, "102");
        assertThrows(ApiException.class, () -> subject.canRead(scope, PARENT, SOURCE));
    }

    private void run(RunIdentity identity) {
        var run = mock(ExecutionStore.Run.class);
        when(run.identity()).thenReturn(identity);
        when(executions.load(identity.runRef())).thenReturn(Optional.of(run));
    }

    private static RunIdentity identity(RunRef run, SessionOwner owner, AgentId actor) {
        return new RunIdentity(owner, actor, new InputId("input:" + run.runId()), run,
                new TraceEnvelope("trace", "span", new InputId("root-input"), Optional.empty()),
                new ResultRef("identity"), new RunRequester.EndUser(USER), Optional.empty(), new RunOriginRef(TENANT, "origin"));
    }
}
