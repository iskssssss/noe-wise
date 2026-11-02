package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 验证所有 Port 共用身份、引用、租约、失败与调用控制契约。 */
class PortSharedContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final EndUserId USER = new EndUserId(7);
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final SessionOwner OWNER = SessionOwner.user(SESSION, new AgentId("agent-1"), USER);
    private static final InputId INPUT = new InputId("input-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    /** 验证租户内引用、幂等键、能力快照与封闭枚举的精确 ABI 顺序。 */
    @Test
    void validatesTenantScopedReferencesAndEnums() {
        assertEquals("execution-1", new ExecutionRef(TENANT, "execution-1").executionId());
        assertEquals("action-1", new ActionRef(TENANT, "action-1").actionId());
        assertEquals("delegation-1", new DelegationRef(TENANT, "delegation-1").delegationId());
        assertEquals("key-1", new IdempotencyKey("key-1").value());
        assertEquals(
                List.of("NONE", "IDEMPOTENT_BY_STABLE_KEY", "RESULT_QUERYABLE", "COORDINATED"),
                Arrays.stream(RetryMode.values()).map(Enum::name).toList());
        assertEquals(
                List.of("APPLIED", "ALREADY_APPLIED", "STALE_LEASE", "NOT_FOUND", "TERMINAL"),
                Arrays.stream(LeaseMutationStatus.values()).map(Enum::name).toList());
        assertEquals(
                List.of(
                        "QUEUED",
                        "RUNNING",
                        "WAITING_HITL", "WAITING_EXTERNAL",
                        "RESUMING",
                        "COMPLETED",
                        "FAILED",
                        "LOOP_TERMINATED",
                        "CANCELLED"),
                Arrays.stream(WorkflowExecutionState.values()).map(Enum::name).toList());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionRef(TENANT, " "));
        assertThrows(IllegalArgumentException.class, () -> new ActionRef(TENANT, " "));
        assertThrows(IllegalArgumentException.class, () -> new DelegationRef(TENANT, " "));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new CapabilitySnapshotRef(SESSION, INPUT, "snapshot-1", 0));
    }

    /** 验证 Principal 的封闭成员与 VerifiedIdentity 的租户、用户、scope 和版本不变量。 */
    @Test
    void validatesVerifiedIdentityBoundary() {
        assertEquals(
                Set.of(
                        PrincipalRef.EndUserPrincipal.class,
                        PrincipalRef.ServicePrincipal.class,
                        PrincipalRef.SystemPrincipal.class),
                Set.of(PrincipalRef.class.getPermittedSubclasses()));

        PrincipalRef.EndUserPrincipal principal = new PrincipalRef.EndUserPrincipal(TENANT, USER);
        HashSet<String> mutableScopes = new HashSet<>(Set.of("runtime:accept"));
        VerifiedIdentity identity = new VerifiedIdentity(
                principal, OWNER, mutableScopes, 3, new ResultRef("verification-1"));
        mutableScopes.add("runtime:admin");

        assertEquals(Set.of("runtime:accept"), identity.scopes());
        assertNotSame(mutableScopes, identity.scopes());
        assertThrows(UnsupportedOperationException.class,
                () -> identity.scopes().add("runtime:query"));
        assertThrows(IllegalArgumentException.class,
                () -> new PrincipalRef.ServicePrincipal(TENANT, "service-1", "integration-1", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VerifiedIdentity(principal, OWNER, Set.of(" "), 1, new ResultRef("v-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new VerifiedIdentity(
                        new PrincipalRef.EndUserPrincipal(TENANT, new EndUserId(8)),
                        OWNER, Set.of(), 1, new ResultRef("v-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new VerifiedIdentity(
                        new PrincipalRef.SystemPrincipal(new TenantId("tenant-2"), "runtime"),
                        OWNER, Set.of(), 1, new ResultRef("v-1")));
    }

    /** 验证普通调用上下文不携带 attempt 身份，Provider上下文必须绑定明确派发。 */
    @Test
    void validatesRunAndInvocationIdentity() {
        RunIdentity runIdentity = runIdentity(OWNER, RUN);
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, USER),
                OWNER, Set.of("runtime:invoke"), 1, new ResultRef("verification-1"));
        InvocationControl control = new InvocationControl(NOW.plusSeconds(30), () -> false);
        PortCallContext context = new PortCallContext(runIdentity, identity, control);
        ProviderCallContext providerContext = ProviderDispatchTestFixtures.providerContext(
                context, PortalCallKind.TOOL_INVOKE);

        assertEquals(runIdentity, context.runIdentity());
        assertEquals(context, providerContext.callContext());
        assertThrows(IllegalArgumentException.class,
                () -> runIdentity(OWNER, new RunRef(new TenantId("tenant-2"), "run-2")));
        assertThrows(IllegalArgumentException.class,
                () -> new PortCallContext(
                        runIdentity, identity,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCallContext.dispatched(context, null));
    }

    /** 验证取消信号可单调触发，并验证租约与稳定失败字段。 */
    @Test
    void validatesCancellationLeaseAndFailureFacts() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CancellationSignal signal = cancelled::get;
        assertFalse(signal.isCancellationRequested());
        cancelled.set(true);
        assertTrue(signal.isCancellationRequested());
        assertTrue(signal.isCancellationRequested());

        LeaseFence lease = new LeaseFence(TENANT, "claim-1", "worker-1", 1, NOW.plusSeconds(30));
        PortFailure failure = new PortFailure("provider_timeout", RetryMode.RESULT_QUERYABLE,
                new ResultRef("result-1"));
        PortConflict conflict = new PortConflict("run", "run-1", new ResultRef("audit-1"));
        assertEquals(1, lease.fencingToken());
        assertEquals(RetryMode.RESULT_QUERYABLE, failure.retryMode());
        assertEquals("run", conflict.resourceKind());
        assertThrows(IllegalArgumentException.class,
                () -> new LeaseFence(TENANT, "claim-1", "worker-1", 0, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PortFailure(" ", RetryMode.NONE, new ResultRef("result-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new PortConflict("run", " ", new ResultRef("audit-1")));
    }

    /** 构造测试使用的完整 Run identity。 */
    private static RunIdentity runIdentity(SessionOwner owner, RunRef runRef) {
        TraceEnvelope trace = new TraceEnvelope(
                "trace-1", "span-1", INPUT, Optional.of(new EventId("event-1")));
        return new RunIdentity(owner, owner.target().requireAgent(), INPUT, runRef, trace, new ResultRef("causation-identity-1"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), INPUT.value()));
    }
}
