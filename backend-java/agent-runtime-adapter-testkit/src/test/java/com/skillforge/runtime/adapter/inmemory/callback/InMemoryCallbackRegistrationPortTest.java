package com.skillforge.runtime.adapter.inmemory.callback;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryCallbackRegistrationPortTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("agent"), new EndUserId(1));

    @Test
    void registrationIsFullValueIdempotentAndEveryReplayRequiresCurrentAuthorization() {
        var f = new Fixture();
        assertInstanceOf(CallbackRegisterResult.Registered.class, f.port.register(f.registration));
        assertInstanceOf(CallbackRegisterResult.Replayed.class, f.port.register(f.registration));
        var original = f.registration;
        var changed = new RegisterCallback(original.registration(), original.requester(), original.owningRun(), original.causationInputId(),
                original.trace(), original.causationIdentityRef(), new IdempotencyKey("other"), original.requestFingerprint());
        var conflict = assertInstanceOf(CallbackRegisterResult.Conflict.class, f.port.register(changed));
        assertTrue(f.port.audit(conflict.auditRef()).isPresent());
        f.allowRegister = false;
        var rejected = assertInstanceOf(CallbackRegisterResult.Rejected.class, f.port.register(f.registration));
        assertTrue(f.port.audit(rejected.failure().resultRef()).isPresent());
        assertInstanceOf(CallbackRegisterResult.Rejected.class, f.port.register(changed));
    }

    @Test
    void suspendedAndRevokedRegistrationRejectNewDeliveriesWithoutDeletingPreviouslyReadyEnvelope() {
        var f = new Fixture(); f.register();
        var accepted = assertInstanceOf(CallbackDeliveryResult.Ready.class, f.port.acceptVerified(f.delivery));
        var suspended = f.status(1, CallbackRegistrationStatus.SUSPENDED, "suspend");
        assertInstanceOf(CallbackStatusResult.Applied.class, f.port.changeStatus(suspended));
        assertInstanceOf(CallbackStatusResult.Replayed.class, f.port.changeStatus(suspended));
        var rejectedCommand = new VerifiedCallbackDelivery(f.delivery.callbackRef(), 2, new CallbackDeliveryRef(TENANT, "rejected"),
                "fp", f.delivery.envelope(), NOW);
        var rejected = assertInstanceOf(CallbackDeliveryResult.Rejected.class, f.port.acceptVerified(rejectedCommand)).receipt();
        assertTrue(rejected.envelope().isEmpty()); assertTrue(f.port.audit(rejected.auditRef().orElseThrow()).isPresent());
        assertInstanceOf(CallbackDeliveryResult.Replayed.class, f.port.acceptVerified(rejectedCommand));
        assertEquals(accepted.receipt(), assertInstanceOf(CallbackDeliveryResult.Replayed.class, f.port.acceptVerified(f.delivery)).receipt());
        f.allowStatus = false;
        assertInstanceOf(CallbackStatusResult.Rejected.class, f.port.changeStatus(suspended));
        f.allowStatus = true;
        assertInstanceOf(CallbackStatusResult.Applied.class, f.port.changeStatus(f.status(2, CallbackRegistrationStatus.REVOKED, "revoke")));
        assertInstanceOf(CallbackStatusResult.Rejected.class, f.port.changeStatus(f.status(3, CallbackRegistrationStatus.ACTIVE, "resurrect")));
        assertEquals(1, f.port.scanReady(TENANT, 10).size());
        assertEquals(f.delivery.envelope(), f.claim().receipt().envelope().orElseThrow());
    }

    @Test
    void rejectedIngressKeepsOnlyDigestAndRejectsFullValueReplayDrift() throws Exception {
        var f = new Fixture(); f.register(); f.allowIngress = false;
        assertInstanceOf(CallbackDeliveryResult.Rejected.class, f.port.acceptVerified(f.delivery));
        var changed = new VerifiedCallbackDelivery(f.delivery.callbackRef(), 1, f.delivery.deliveryRef(), "changed", f.delivery.envelope(), NOW);
        assertInstanceOf(CallbackDeliveryResult.Conflict.class, f.port.acceptVerified(changed));
        assertInstanceOf(CallbackClaimResult.Empty.class, f.port.claimReady(f.request));
        var field = InMemoryCallbackRegistrationPort.class.getDeclaredField("deliveries"); field.setAccessible(true);
        var entry = ((Map<?, ?>) field.get(f.port)).values().iterator().next();
        for (var member : entry.getClass().getDeclaredFields()) {
            member.setAccessible(true); var value = member.get(entry);
            assertFalse(value instanceof VerifiedCallbackDelivery || value instanceof InboundEnvelope);
            if (value instanceof String text) assertFalse(text.contains("sensitive-callback"));
        }
    }

    @Test
    void deliveryReplayRejectsEachFrozenSourceIdentityDrift() {
        for (boolean accepted : List.of(false, true)) {
            var f = new Fixture(); f.register(); f.allowIngress = accepted;
            var first = f.port.acceptVerified(f.delivery);
            assertEquals(accepted, first instanceof CallbackDeliveryResult.Ready);
            var e = f.delivery.envelope();
            var changed = List.of(
                    new InboundEnvelope(e.authenticationRef(), e.causationIdentityRef(), e.sessionRef(), SessionTarget.team("other-team"), e.agentId(), e.subject(), e.requester(), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), e.source(), e.trace(), e.payload(), e.receivedAt(), e.sourceMessageRef(), e.originRef()),
                    new InboundEnvelope(e.authenticationRef(), e.causationIdentityRef(), e.sessionRef(), e.sessionTarget(), e.agentId(), e.subject(), new RunRequester.System(new PrincipalRef.SystemPrincipal(TENANT, "other-requester")), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), e.source(), e.trace(), e.payload(), e.receivedAt(), e.sourceMessageRef(), e.originRef()),
                    new InboundEnvelope(e.authenticationRef(), e.causationIdentityRef(), e.sessionRef(), e.sessionTarget(), e.agentId(), e.subject(), e.requester(), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), e.source(), e.trace(), e.payload(), e.receivedAt(), Optional.of(new SourceMessageRef(e.sessionRef(), "other-message")), e.originRef()),
                    new InboundEnvelope(e.authenticationRef(), e.causationIdentityRef(), e.sessionRef(), e.sessionTarget(), e.agentId(), e.subject(), e.requester(), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), e.source(), e.trace(), e.payload(), e.receivedAt(), e.sourceMessageRef(), new RunOriginRef(TENANT, "other-origin")));
            for (var envelope : changed) {
                assertInstanceOf(CallbackDeliveryResult.Conflict.class, f.port.acceptVerified(new VerifiedCallbackDelivery(
                        f.delivery.callbackRef(), f.delivery.expectedRegistrationRevision(), f.delivery.deliveryRef(),
                        f.delivery.requestFingerprint(), envelope, f.delivery.verifiedAt())));
            }
            var replay = assertInstanceOf(CallbackDeliveryResult.Replayed.class, f.port.acceptVerified(f.delivery));
            assertEquals(first instanceof CallbackDeliveryResult.Ready ready ? ready.receipt()
                    : ((CallbackDeliveryResult.Rejected) first).receipt(), replay.receipt());
        }
    }

    @Test
    void strictRegistrationBindingRejectsOwnerSourceSchemaCausationAndRevisionDrift() {
        var f = new Fixture(); f.register(); var e = f.delivery.envelope();
        var envelopes = List.of(
                new InboundEnvelope(e.authenticationRef(), e.causationIdentityRef(), e.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(new AgentId("other")), new AgentId("other"), e.subject(), e.requester(), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), e.source(), e.trace(), e.payload(), e.receivedAt(), e.sourceMessageRef(), e.originRef()),
                new InboundEnvelope(e.authenticationRef(), e.causationIdentityRef(), e.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(e.agentId()), e.agentId(), e.subject(), e.requester(), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), "other", e.trace(), e.payload(), e.receivedAt(), e.sourceMessageRef(), e.originRef()),
                new InboundEnvelope(e.authenticationRef(), e.causationIdentityRef(), e.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(e.agentId()), e.agentId(), e.subject(), e.requester(), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), e.source(), e.trace(), new TypedPayload("other", "{}"), e.receivedAt(), e.sourceMessageRef(), e.originRef()),
                new InboundEnvelope(e.authenticationRef(), Optional.of(new ResultRef("other")), e.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(e.agentId()), e.agentId(), e.subject(), e.requester(), e.inputId(), e.eventId(), e.kind(), e.runTarget(), e.channel(), e.source(), e.trace(), e.payload(), e.receivedAt(), e.sourceMessageRef(), e.originRef()));
        for (int i = 0; i < envelopes.size(); i++) {
            assertInstanceOf(CallbackDeliveryResult.Rejected.class, f.port.acceptVerified(new VerifiedCallbackDelivery(f.delivery.callbackRef(), 1,
                    new CallbackDeliveryRef(TENANT, "drift-" + i), "fp", envelopes.get(i), NOW)));
        }
        assertInstanceOf(CallbackDeliveryResult.Rejected.class, f.port.acceptVerified(new VerifiedCallbackDelivery(f.delivery.callbackRef(), 2,
                new CallbackDeliveryRef(TENANT, "revision"), "fp", e, NOW)));
    }

    @Test
    void executionTargetAcceptsOnlyItsExactExistingRun() {
        var f = new Fixture();
        var run = new RunIdentity(OWNER, OWNER.target().requireAgent(), new InputId("cause"), new RunRef(TENANT, "run"), f.trace, f.requester.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("cause").value()));
        var target = new CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"execution"),run.runRef(),"action",3);
        var draft = new CallbackRegistrationDraft(new CallbackRef(TENANT, "action-callback"),
                OWNER, target, "source", "callback.action.v1", "signature-v1",
                new CredentialRef(TENANT, "credential"));
        var registration = new RegisterCallback(draft, f.requester, Optional.of(run),
                run.causationInputId(), run.trace(), run.causationIdentityRef(),
                new IdempotencyKey("action-callback-key"), "action-callback-fp");
        assertInstanceOf(CallbackRegisterResult.Registered.class, f.port.register(registration));
        var exact = new InboundEnvelope(new AuthenticationRef("system"), Optional.of(run.causationIdentityRef()), OWNER.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(OWNER.target().requireAgent()), OWNER.target().requireAgent(), OWNER.subject(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), new InputId("action-callback-input"), new EventId("action-callback-event"), InboundKind.CALLBACK, new RunTarget.ExistingRun(run.runRef()), "callback", "source", f.trace, new TypedPayload("callback.action.v1", "{}"), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("action-callback-input").value()));
        assertInstanceOf(CallbackDeliveryResult.Ready.class, f.port.acceptVerified(
                new VerifiedCallbackDelivery(draft.callbackRef(), 1,
                        new CallbackDeliveryRef(TENANT, "action-delivery"), "fp", exact, NOW)));
        var current = f.port.find(draft.callbackRef()).orElseThrow();
        assertEquals(CallbackRegistrationStatus.ACTIVE,current.status());assertEquals(1,current.revision());
        var wrongRun = new InboundEnvelope(exact.authenticationRef(), exact.causationIdentityRef(), exact.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(exact.agentId()), exact.agentId(), exact.subject(), exact.requester(), new InputId("wrong-run-input"), new EventId("wrong-run-event"), exact.kind(), new RunTarget.ExistingRun(new RunRef(TENANT, "other-run")), exact.channel(), exact.source(), exact.trace(), exact.payload(), exact.receivedAt(), exact.sourceMessageRef(), exact.originRef());
        assertInstanceOf(CallbackDeliveryResult.Rejected.class, f.port.acceptVerified(
                new VerifiedCallbackDelivery(draft.callbackRef(), 1,
                        new CallbackDeliveryRef(TENANT, "wrong-run-delivery"), "fp", wrongRun, NOW)));
    }

    @Test
    void retryTakeoverKeepsExactLeaseAndOperationIdentity() {
        var f = new Fixture(); f.register(); f.port.acceptVerified(f.delivery); var first = f.claim();
        var retry = new CallbackRetry(first.lease(), NOW.plusSeconds(5), new PortFailure("retry", RetryMode.IDEMPOTENT_BY_STABLE_KEY, new ResultRef("diagnostic")), new RequestId("retry"), "fp");
        assertInstanceOf(CallbackMutationResult.Applied.class, f.port.retry(retry));
        assertInstanceOf(CallbackMutationResult.AlreadyApplied.class, f.port.retry(retry));
        assertInstanceOf(CallbackClaimResult.Empty.class, f.port.claimReady(f.request));
        f.clock.now = NOW.plusSeconds(5); var second = f.claim();
        assertTrue(second.lease().fence().fencingToken() > first.lease().fence().fencingToken());
        assertInstanceOf(CallbackMutationResult.Conflict.class, f.port.retry(retry));
        var staleRetry = new CallbackRetry(first.lease(), NOW.plusSeconds(10),
                new PortFailure("retry", RetryMode.IDEMPOTENT_BY_STABLE_KEY, new ResultRef("diagnostic")), new RequestId("stale-retry"), "fp");
        assertInstanceOf(CallbackMutationResult.StaleLease.class, f.port.retry(staleRetry));
        var currentRetry = new CallbackRetry(second.lease(), NOW.plusSeconds(10),
                new PortFailure("retry", RetryMode.IDEMPOTENT_BY_STABLE_KEY, new ResultRef("diagnostic")), new RequestId("current-retry"), "fp");
        assertInstanceOf(CallbackMutationResult.Applied.class, f.port.retry(currentRetry));
        assertInstanceOf(CallbackMutationResult.AlreadyApplied.class, f.port.retry(currentRetry));
        assertInstanceOf(CallbackClaimResult.Empty.class, f.port.claimReady(f.request));
    }

    @Test
    void renewExpiresAndDeadLetterKeepsEnvelopeAndDiagnostic() {
        var f = new Fixture(); f.register(); f.port.acceptVerified(f.delivery); var first = f.claim();
        var renew = new CallbackRenew(first.lease(), Duration.ofSeconds(3), new RequestId("renew"), "fp");
        assertInstanceOf(CallbackMutationResult.Applied.class, f.port.renew(renew));
        assertInstanceOf(CallbackMutationResult.AlreadyApplied.class, f.port.renew(renew));
        f.clock.now = NOW.plusSeconds(5); var next = f.claim();
        assertInstanceOf(CallbackMutationResult.Conflict.class, f.port.renew(renew));
        var diagnostic = new ResultRef("permanent-fact");
        var dead = new CallbackDeadLetter(next.lease(), new PortFailure("permanent", RetryMode.NONE, diagnostic), new RequestId("dead"), "fp");
        var receipt = assertInstanceOf(CallbackMutationResult.Applied.class, f.port.deadLetter(dead)).current();
        assertEquals(CallbackDeliveryStatus.DEAD_LETTER, receipt.status()); assertEquals(Optional.of(diagnostic), receipt.auditRef());
        assertTrue(f.port.audit(diagnostic).isPresent()); assertEquals(Optional.of(f.delivery.envelope()), receipt.envelope());
        assertInstanceOf(CallbackMutationResult.AlreadyApplied.class, f.port.deadLetter(dead));
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        boolean allowRegister = true, allowStatus = true, allowIngress = true;
        final VerifiedIdentity requester = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser()), OWNER, Set.of("sessions.write"), 2, new ResultRef("verified"));
        final TraceEnvelope trace = new TraceEnvelope("trace", "span", new InputId("root"), Optional.empty());
        final RunIdentity run = new RunIdentity(OWNER, OWNER.target().requireAgent(), new InputId("cause"), new RunRef(TENANT,"callback-run"), trace, requester.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("cause").value()));
        final CallbackRegistrationDraft draft = new CallbackRegistrationDraft(new CallbackRef(TENANT, "callback"), OWNER, new CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"execution"),run.runRef(),"action",1),
                "source", "callback.v1", "signature-v1", new CredentialRef(TENANT, "credential"));
        final RegisterCallback registration = new RegisterCallback(draft, requester, Optional.of(run), new InputId("cause"), trace, requester.verificationRef(), new IdempotencyKey("callback-key"), "fp");
        final InboundEnvelope envelope = new InboundEnvelope(new AuthenticationRef("system"), Optional.of(requester.verificationRef()), OWNER.sessionRef(), com.skillforge.runtime.api.SessionTarget.agent(OWNER.target().requireAgent()), OWNER.target().requireAgent(), OWNER.subject(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), new InputId("callback-input"), new EventId("callback-event"), InboundKind.CALLBACK, new RunTarget.ExistingRun(run.runRef()), "callback", "source", trace, new TypedPayload("callback.v1", "{\"value\":\"sensitive-callback\"}"), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("callback-input").value()));
        final VerifiedCallbackDelivery delivery = new VerifiedCallbackDelivery(draft.callbackRef(), 1, new CallbackDeliveryRef(TENANT, "delivery"), "fp", envelope, NOW);
        final InMemoryCallbackRegistrationPort port = new InMemoryCallbackRegistrationPort(clock, command -> allowRegister,
                (record, command) -> allowStatus, (record, command) -> allowIngress);
        final CallbackClaimRequest request = new CallbackClaimRequest(TENANT, "worker", 10, Duration.ofSeconds(2));
        void register() { port.register(registration); }
        CallbackClaim claim() { return assertInstanceOf(CallbackClaimResult.Claimed.class, port.claimReady(request)).claims().getFirst(); }
        ChangeCallbackStatus status(long revision, CallbackRegistrationStatus status, String operation) {
            return new ChangeCallbackStatus(draft.callbackRef(), requester, revision, status, registration.causationInputId(), trace, new RequestId(operation), "fp");
        }
    }
    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException(); return this; }
        @Override public Instant instant() { return now; }
    }
}
