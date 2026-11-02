package com.skillforge.systemagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.systemagent.SystemAgentRunner;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.FrozenRun;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.IdentityPolicyPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimePlatformProperties;
import java.time.*;
import java.util.*;

/** 平台系统Agent的业务入口；只受理、查询同一个typed执行Run，不拥有另一执行算法。 */
final class SystemAgentRuntimeRunner implements SystemAgentRunner {
    private static final Duration REQUEST_WAIT=Duration.ofSeconds(15),POLL_INTERVAL=Duration.ofMillis(100),NOT_ACCEPTED_GRACE=Duration.ofSeconds(30);
    private static final Set<String> REQUIRED_SCOPES=Set.of("sessions.read","sessions.write");
    private final SystemAgentExecutions executions;private final AgentRuntimeTrustedAccess trustedAccess;private final JdbcIdentityPolicyPort identities;
    private final AgentRuntimeProperties properties;private final ServicePrincipalService principals;private final SystemAgentRuntimeStore store;
    private final AgentRuntimePlatformProperties platformProperties;
    private final SystemAgentCapabilitySource sources;private final SystemAgentModelProvisioner models;private final ObjectMapper json;private final Clock clock;
    SystemAgentRuntimeRunner(SystemAgentExecutions executions,AgentRuntimeTrustedAccess trustedAccess,JdbcIdentityPolicyPort identities,
            AgentRuntimeProperties properties,AgentRuntimePlatformProperties platformProperties,ServicePrincipalService principals,SystemAgentRuntimeStore store,SystemAgentCapabilitySource sources,
            SystemAgentModelProvisioner models,ObjectMapper json,Clock clock){this.executions=Objects.requireNonNull(executions);this.trustedAccess=Objects.requireNonNull(trustedAccess);this.identities=Objects.requireNonNull(identities);this.properties=Objects.requireNonNull(properties);this.platformProperties=Objects.requireNonNull(platformProperties);this.principals=Objects.requireNonNull(principals);this.store=Objects.requireNonNull(store);this.sources=Objects.requireNonNull(sources);this.models=Objects.requireNonNull(models);this.json=Objects.requireNonNull(json);this.clock=Objects.requireNonNull(clock);}
    @Override public LlmResult execute(FrozenRun run){validate(run);return bound(()->executeBound(run));}
    @Override public Optional<LlmResult> completed(FrozenRun run){validate(run);return bound(()->completedBound(run));}

    private LlmResult executeBound(FrozenRun run){
        var identity=identity(run);var expected=sources.from(run);store.register(identity.owner(),SystemAgentRuntimeContracts.INTEGRATION_ID,identity.input(),expected.generation(),sources.encode(expected));requireSource(identity.owner(),identity.input(),expected);
        var existing=executions.query(systemAccess(identity),identity.runRef());
        if(existing.isPresent()){requireSession(existing.orElseThrow(),identity.owner());executions.wake(identity.runRef());return await(run,identity,systemAccess(identity),existing.orElseThrow(),existing.orElseThrow().createdAt());}
        var access=trustedAccess.serviceAccess(principal(identity.tenant()),identity.owner(),SystemAgentRuntimeContracts.CHANNEL,SystemAgentRuntimeContracts.SOURCE,new RequestId("system-agent-access:"+run.id()),identity.trace().traceId(),identity.trace().spanId());
        var verification=identities.verify(new IdentityRequest(access.authenticationRef(),identity.owner(),access.channel(),access.source(),access.requestId(),Optional.of(identity.input()),access.traceId(),access.spanId()));
        if(!(verification instanceof IdentityResult.Verified actor))throw terminal("system_agent_runtime_identity_rejected");
        var frozen=new RunIdentity(identity.owner(),identity.agentId(),identity.input(),identity.runRef(),identity.trace(),actor.identity().verificationRef(),
                new RunRequester.System(new PrincipalRef.SystemPrincipal(identity.tenant(),SystemAgentRuntimeContracts.SYSTEM_PRINCIPAL_ID)),
                Optional.empty(),new RunOriginRef(identity.tenant(),identity.businessRunId()));ExecutionStore.Run accepted;
        try{accepted=executions.admit(frozen,actor.identity(),models.prepare(identity.owner(),identity.input()),expected,run.input());}
        catch(RuntimeException uncertain){throw (Pending)pending("system_agent_runtime_admission_unknown").initCause(uncertain);}
        try{return await(run,identity,access,accepted,accepted.createdAt());}catch(TerminalFailure|Pending known){throw known;}catch(RuntimeException uncertain){throw (Pending)pending("system_agent_runtime_query_deferred").initCause(uncertain);}
    }
    /** 恢复只核原source和原typed Run，不prepare、创建session、受理输入或重发。 */
    private Optional<LlmResult> completedBound(FrozenRun run){var identity=identity(run);var expected=sources.from(run);var source=store.source(identity.owner(),identity.input());if(source.isEmpty())return notAccepted(run);requireSource(source.orElseThrow(),expected);var saved=executions.query(systemAccess(identity),identity.runRef());if(saved.isEmpty())return notAccepted(run);requireSession(saved.orElseThrow(),identity.owner());return project(run,saved.orElseThrow());}
    private LlmResult await(FrozenRun run,RuntimeIdentity identity,AccessEnvelope access,ExecutionStore.Run initial,Instant acceptedAt){Instant requestDeadline=clock.instant().plus(REQUEST_WAIT),runtimeDeadline=acceptedAt.plusSeconds(run.config().timeoutSeconds());var current=initial;while(true){var completed=project(run,current);if(completed.isPresent())return completed.orElseThrow();var now=clock.instant();if(!now.isBefore(runtimeDeadline)){requestCancel(identity,access,current);current=queryRun(access,identity.runRef());var cancelled=project(run,current);if(cancelled.isPresent())return cancelled.orElseThrow();throw pending("system_agent_runtime_cancel_pending");}if(!now.isBefore(requestDeadline))throw pending("system_agent_runtime_running");pause();current=queryRun(access,identity.runRef());requireSession(current,identity.owner());}}
    private void requestCancel(RuntimeIdentity identity,AccessEnvelope access,ExecutionStore.Run run){var input=new InputId("system-agent-cancel:"+identity.businessRunId());var verified=identities.verify(new IdentityRequest(access.authenticationRef(),identity.owner(),access.channel(),access.source(),new RequestId("system-agent-cancel:"+identity.businessRunId()),Optional.of(input),identity.trace().traceId(),identity.trace().spanId()));if(!(verified instanceof IdentityResult.Verified actor))throw pending("system_agent_runtime_cancel_identity_denied");try{executions.cancel(run,actor.identity(),input);}catch(RuntimeException conflict){/* 紧随其后的原Run查询确认取消/完成竞争。 */}}
    private Optional<LlmResult> project(FrozenRun run,ExecutionStore.Run snapshot){return switch(snapshot.status()){case COMPLETED -> Optional.of(executions.completed(snapshot,run.config().modelConfigId()));case TERMINATED -> throw terminal("system_agent_runtime_terminated");case FAILED -> throw terminal("system_agent_runtime_failed");case CANCELLED -> throw terminal("system_agent_runtime_cancelled");case QUEUED,RUNNING,SUSPENDED,RESUMING -> Optional.empty();};}
    private ExecutionStore.Run queryRun(AccessEnvelope access,RunRef run){return executions.query(access,run).orElseThrow(()->new NoSuchElementException("runtime_run_not_found"));}
    private AccessEnvelope systemAccess(RuntimeIdentity identity){var principal=new PrincipalRef.SystemPrincipal(identity.tenant(),properties.getWorkerId());var authentication=identities.registerTrusted(principal,identity.owner(),SystemAgentRuntimeContracts.CHANNEL,SystemAgentRuntimeContracts.SOURCE,platformProperties.getTrustedAuthenticationValidity());return new AccessEnvelope(authentication,identity.tenant(),identity.owner().target(),Optional.of(identity.agentId()),identity.owner().subject(),new RequestId("system-agent-recovery:"+identity.businessRunId()),SystemAgentRuntimeContracts.CHANNEL,SystemAgentRuntimeContracts.SOURCE,identity.trace().traceId(),identity.trace().spanId());}
    private PrincipalRef.ServicePrincipal principal(TenantId tenant){var principal=principals.ensureInternal(tenant.value(),SystemAgentRuntimeContracts.INTEGRATION_ID,REQUIRED_SCOPES);return new PrincipalRef.ServicePrincipal(tenant,principal.principalId(),principal.integrationId(),principal.credentialVersion());}
    private void requireSource(SessionOwner owner, InputId input,
            SystemAgentCapabilitySource.SourceSnapshot expected) {
        var row = store.source(owner, input)
                .orElseThrow(() -> new IllegalStateException("system_agent_runtime_source_missing"));
        requireSource(row, expected);
    }

    private void requireSource(SystemAgentRuntimeStore.SourceRow row,
            SystemAgentCapabilitySource.SourceSnapshot expected) {
        if (row.generation != expected.generation() || !sources.decode(row.sourceData).equals(expected)) {
            throw new IllegalStateException("system_agent_runtime_source_conflict");
        }
    }

    private Optional<LlmResult> notAccepted(FrozenRun run) {
        Instant threshold = run.view().createdAt().plus(NOT_ACCEPTED_GRACE);
        if (!clock.instant().isBefore(threshold)) {
            throw terminal("system_agent_runtime_not_accepted");
        }
        return Optional.empty();
    }

    private TypedPayload payload(String schema, String message) {
        try {
            return new TypedPayload(schema, json.createObjectNode().put("message", message).toString());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("system_agent_runtime_payload_invalid", failure);
        }
    }

    private RuntimeIdentity identity(FrozenRun run) {
        TenantId tenant = new TenantId(SystemAgentRuntimeContracts.TENANT_ID);
        AgentId agentId = new AgentId(run.agentId());
        InputId input = new InputId("system-agent-input:" + run.id());
        SessionOwner owner = SessionOwner.user(
                new SessionRef(tenant, "system-agent:" + run.id()),
                agentId, new EndUserId(SystemAgentRuntimeContracts.END_USER_ID));
        return new RuntimeIdentity(run.id(), tenant, owner, agentId, input,
                new RunRef(tenant, "system-agent-run:" + run.id()),
                new EventId("system-agent-event:" + run.id()), trace(run.id(), input));
    }

    private static TraceEnvelope trace(String value, InputId root) {
        String digest = SystemAgentModelProvisioner.digest("system-agent-trace:" + value);
        return new TraceEnvelope(digest.substring(0, 32), digest.substring(32, 48), root,
                Optional.empty());
    }

    private static String objective(FrozenRun run) {
        String description = run.config().description();
        return description == null || description.isBlank() ? run.config().name() : description;
    }

    private static void requireSession(ExecutionStore.Run snapshot, SessionOwner owner) {
        if (!snapshot.identity().owner().sessionRef().equals(owner.sessionRef())) {
            throw new IllegalStateException("system_agent_runtime_session_mismatch");
        }
    }

    private static void validate(FrozenRun run) {
        Objects.requireNonNull(run, "run");
        if (!"AGENT_RUN".equals(run.executionMode()) || !run.debug()
                || !SystemAgentRuntimeContracts.TENANT_ID.equals(run.tenantId())
                || run.id() == null || run.id().isBlank() || run.agentId() == null || run.agentId().isBlank()
                || run.config() == null || run.view() == null || run.view().createdAt() == null
                || run.version() != null && run.version() <= 0 || run.modelRevision() <= 0
                || run.input() == null || run.input().isBlank() || run.mounts() == null
                || run.config().mounts() == null || run.mounts().size() != run.config().mounts().size()
                || !run.mounts().stream().map(value -> value.mount()).toList().equals(run.config().mounts())) {
            throw new IllegalArgumentException("system_agent_runtime_run_rejected");
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw pending("system_agent_runtime_wait_interrupted");
        }
    }

    private <T> T bound(Work<T> work) {
        String previous = TenantContext.getTenantId();
        TenantContext.setTenantId(SystemAgentRuntimeContracts.TENANT_ID);
        try {
            return work.run();
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setTenantId(previous);
        }
    }

    private static TerminalFailure terminal(String reason) {
        return new TerminalFailure(reason);
    }

    private static Pending pending(String reason) {
        return new Pending(reason);
    }

    @FunctionalInterface
    private interface Work<T> { T run(); }

    private record RuntimeIdentity(String businessRunId, TenantId tenant, SessionOwner owner,
                                   AgentId agentId, InputId input, RunRef runRef,
                                   EventId event, TraceEnvelope trace) { }
}
