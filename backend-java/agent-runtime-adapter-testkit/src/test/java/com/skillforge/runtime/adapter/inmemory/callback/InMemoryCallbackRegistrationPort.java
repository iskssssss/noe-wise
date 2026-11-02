package com.skillforge.runtime.adapter.inmemory.callback;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** development 专用回调注册与投递账本；被拒绝输入只保留不可逆摘要，不提供生产持久性。 */
public final class InMemoryCallbackRegistrationPort implements CallbackRegistrationPort {
    private final Clock clock;
    private final Predicate<RegisterCallback> registerAuthorizer;
    private final BiPredicate<CallbackRegistration, ChangeCallbackStatus> statusAuthorizer;
    private final BiPredicate<CallbackRegistration, VerifiedCallbackDelivery> ingressVerifier;
    private final Map<CallbackRef, Registration> registrations = new HashMap<>();
    private final Map<Key, CallbackRef> keys = new HashMap<>();
    private final Map<CallbackDeliveryRef, Delivery> deliveries = new LinkedHashMap<>();
    private final Map<ResultRef, Audit> audits = new LinkedHashMap<>();
    private long fence;

    public InMemoryCallbackRegistrationPort(Clock clock, Predicate<RegisterCallback> registerAuthorizer,
            BiPredicate<CallbackRegistration, ChangeCallbackStatus> statusAuthorizer,
            BiPredicate<CallbackRegistration, VerifiedCallbackDelivery> ingressVerifier) {
        this.clock = Objects.requireNonNull(clock); this.registerAuthorizer = Objects.requireNonNull(registerAuthorizer);
        this.statusAuthorizer = Objects.requireNonNull(statusAuthorizer); this.ingressVerifier = Objects.requireNonNull(ingressVerifier);
    }
    public String supportedProfile() { return "development"; }
    public synchronized Optional<Audit> audit(ResultRef reference) { return Optional.ofNullable(audits.get(reference)); }
    public record Audit(String reasonCode, Instant recordedAt) { }

    /** 原注册全值冻结；首次和重放均重新检查当前授权。 */
    @Override public synchronized CallbackRegisterResult register(RegisterCallback command) {
        Objects.requireNonNull(command);
        if (!registerAuthorizer.test(command)) return new CallbackRegisterResult.Rejected(failure("callback_register_denied"));
        var ref = command.registration().callbackRef(); var key = new Key(ref.tenantId(), command.idempotencyKey());
        var previous = registrations.get(ref); var bound = keys.get(key);
        if (previous != null || bound != null) {
            if (previous != null && ref.equals(bound) && previous.command.equals(command)) return new CallbackRegisterResult.Replayed(previous.record);
            return new CallbackRegisterResult.Conflict(ref, audit("callback_register_conflict"));
        }
        var now = clock.instant();
        var record = new CallbackRegistration(command.registration(), command.causationIdentityRef(), CallbackRegistrationStatus.ACTIVE, 1, now, now);
        registrations.put(ref, new Registration(command, record)); keys.put(key, ref);
        return new CallbackRegisterResult.Registered(record);
    }
    @Override public synchronized Optional<CallbackRegistration> find(CallbackRef ref) {
        var registration = registrations.get(Objects.requireNonNull(ref)); return registration == null ? Optional.empty() : Optional.of(registration.record);
    }
    @Override public synchronized CallbackStatusResult changeStatus(ChangeCallbackStatus command) {
        Objects.requireNonNull(command); var entry = registrations.get(command.callbackRef());
        if (entry == null) return new CallbackStatusResult.NotFound(failure("callback_not_found"));
        var current = entry.record;
        if (!current.draft().owner().equals(command.requester().owner()) || !statusAuthorizer.test(current, command)) {
            return new CallbackStatusResult.Rejected(failure("callback_status_denied"));
        }
        var previous = entry.operations.get(command.operationId());
        if (previous != null) {
            return previous.command.equals(command) && previous.revision == entry.record.revision()
                    ? new CallbackStatusResult.Replayed(entry.record) : new CallbackStatusResult.Conflict(entry.record, audit("callback_status_conflict"));
        }
        if (current != entry.record || current.revision() != command.expectedRevision()) return new CallbackStatusResult.Conflict(entry.record, audit("callback_revision_conflict"));
        if (current.status() == CallbackRegistrationStatus.REVOKED || current.status() == command.targetStatus()) {
            return new CallbackStatusResult.Rejected(failure("callback_status_transition_rejected"));
        }
        var next = new CallbackRegistration(current.draft(), current.causationIdentityRef(), command.targetStatus(),
                Math.incrementExact(current.revision()), current.createdAt(), clock.instant());
        entry.record = next; entry.operations.put(command.operationId(), new Operation(command, next.revision()));
        return new CallbackStatusResult.Applied(next);
    }

    /** 已收录 delivery 独立于后续注册状态；新拒绝仅保存全命令摘要和安全拒绝事实。 */
    @Override public synchronized CallbackDeliveryResult acceptVerified(VerifiedCallbackDelivery command) {
        Objects.requireNonNull(command); var digest = digest(command); var previous = deliveries.get(command.deliveryRef());
        if (previous != null) {
            return previous.digest.equals(digest) ? new CallbackDeliveryResult.Replayed(previous.receipt)
                    : new CallbackDeliveryResult.Conflict(command.deliveryRef(), audit("callback_delivery_conflict"));
        }
        var registration = registrations.get(command.callbackRef());
        var current = registration == null ? null : registration.record;
        boolean accepted = current != null && matches(current, command) && ingressVerifier.test(current, command)
                && registration.record == current;
        var now = clock.instant();
        var receipt = new CallbackDeliveryReceipt(command.callbackRef(), command.deliveryRef(),
                accepted ? CallbackDeliveryStatus.READY : CallbackDeliveryStatus.REJECTED, 1,
                accepted ? Optional.of(command.envelope()) : Optional.empty(),
                accepted ? Optional.empty() : Optional.of("callback_ingress_rejected"),
                accepted ? Optional.empty() : Optional.of(audit("callback_ingress_rejected")), now);
        deliveries.put(command.deliveryRef(), new Delivery(digest, receipt, now));
        return accepted ? new CallbackDeliveryResult.Ready(receipt) : new CallbackDeliveryResult.Rejected(receipt);
    }
    private static boolean matches(CallbackRegistration registration, VerifiedCallbackDelivery command) {
        var draft = registration.draft(); var e = command.envelope();
        if (registration.status() != CallbackRegistrationStatus.ACTIVE || registration.revision() != command.expectedRegistrationRevision()
                || !draft.callbackRef().equals(command.callbackRef()) || !draft.owner().equals(new SessionOwner(e.sessionRef(), e.subject(), e.sessionTarget()))
                || !draft.source().equals(e.source()) || !draft.payloadSchemaId().equals(e.payload().schemaId())
                || !e.causationIdentityRef().equals(Optional.of(registration.causationIdentityRef()))) return false;
        return (!(draft.target() instanceof CallbackTarget.ExecutionTarget target)
                || e.runTarget() instanceof RunTarget.ExistingRun existing
                    && target.owningRunRef().equals(existing.runRef()));
    }
    /** 扫描仅提供发现信息，不签发 lease；同样包含可接管的过期 PROCESSING 记录。 */
    @Override public synchronized List<CallbackReadyDelivery> scanReady(TenantId tenant, int limit) {
        Objects.requireNonNull(tenant); if (limit <= 0) throw new IllegalArgumentException("callback_limit_invalid");
        return deliveries.values().stream().filter(entry -> ready(entry, tenant, clock.instant())).limit(limit).map(entry -> {
            var r = entry.receipt; var e = r.envelope().orElseThrow();
            return new CallbackReadyDelivery(r.deliveryRef(), r.callbackRef(), new SessionOwner(e.sessionRef(), e.subject(), e.sessionTarget()), r.ledgerRevision(), r.recordedAt());
        }).toList();
    }
    @Override public synchronized CallbackClaimResult claimReady(CallbackClaimRequest request) {
        Objects.requireNonNull(request); var now = clock.instant(); var claims = new ArrayList<CallbackClaim>();
        for (var entry : deliveries.values()) {
            if (!ready(entry, request.tenantId(), now)) continue;
            var lease = new LeaseFence(request.tenantId(), UUID.randomUUID().toString(), request.workerId(), Math.incrementExact(fence), now.plus(request.leaseDuration()));
            entry.receipt = updated(entry.receipt, CallbackDeliveryStatus.PROCESSING, Optional.empty());
            fence = lease.fencingToken(); entry.lease = lease;
            claims.add(new CallbackClaim(entry.receipt, new CallbackLease(entry.receipt.deliveryRef(), lease, entry.receipt.ledgerRevision())));
            if (claims.size() == request.limit()) break;
        }
        return claims.isEmpty() ? new CallbackClaimResult.Empty() : new CallbackClaimResult.Claimed(claims);
    }
    @Override public synchronized CallbackMutationResult renew(CallbackRenew command) {
        Objects.requireNonNull(command);
        return mutate(command.lease(), command.operationId(), command, entry -> {
            var old = entry.lease;
            var next = updated(entry.receipt, CallbackDeliveryStatus.PROCESSING, Optional.empty());
            entry.lease = new LeaseFence(old.tenantId(), old.claimId(), old.workerId(), old.fencingToken(), old.leaseUntil().plus(command.extension()));
            return next;
        });
    }
    @Override public synchronized CallbackMutationResult retry(CallbackRetry command) {
        Objects.requireNonNull(command);
        return mutate(command.lease(), command.operationId(), command, entry -> {
            var next = updated(entry.receipt, CallbackDeliveryStatus.READY, Optional.empty());
            entry.availableAt = command.availableAt(); return next;
        });
    }
    @Override public synchronized CallbackMutationResult deadLetter(CallbackDeadLetter command) {
        Objects.requireNonNull(command);
        return mutate(command.lease(), command.operationId(), command,
                entry -> updated(entry.receipt, CallbackDeliveryStatus.DEAD_LETTER, Optional.of(command.failure())));
    }
    /** lease、revision、完整命令 journal 三者收敛；接管后旧 lease 不再可写。 */
    private CallbackMutationResult mutate(CallbackLease lease, RequestId operation, Object command, java.util.function.Function<Delivery, CallbackDeliveryReceipt> change) {
        var entry = deliveries.get(lease.deliveryRef());
        if (entry == null) return new CallbackMutationResult.NotFound(failure("callback_delivery_not_found"));
        var current = entry.receipt; var previous = entry.operations.get(operation);
        if (previous != null) return previous.command.equals(command) && previous.revision == current.ledgerRevision()
                ? new CallbackMutationResult.AlreadyApplied(current) : new CallbackMutationResult.Conflict(current, audit("callback_mutation_conflict"));
        if (terminal(current)) return new CallbackMutationResult.Terminal(current);
        if (!writable(entry, lease)) return new CallbackMutationResult.StaleLease(current, failure("callback_stale_lease"));
        var next = change.apply(entry); entry.receipt = next;
        if (next.status() != CallbackDeliveryStatus.PROCESSING) entry.lease = null;
        entry.operations.put(operation, new Operation(command, next.ledgerRevision())); return new CallbackMutationResult.Applied(next);
    }
    private CallbackDeliveryReceipt updated(CallbackDeliveryReceipt before, CallbackDeliveryStatus status, Optional<PortFailure> failure) {
        failure.ifPresent(value -> audits.putIfAbsent(value.resultRef(), new Audit(value.reasonCode(), clock.instant())));
        return new CallbackDeliveryReceipt(before.callbackRef(), before.deliveryRef(), status, Math.incrementExact(before.ledgerRevision()), before.envelope(),
                failure.map(PortFailure::reasonCode), failure.map(PortFailure::resultRef), before.recordedAt());
    }
    private boolean writable(Delivery entry, CallbackLease lease) {
        return entry.receipt.status() == CallbackDeliveryStatus.PROCESSING && entry.receipt.ledgerRevision() == lease.ledgerRevision()
                && lease.fence().equals(entry.lease) && entry.lease.leaseUntil().isAfter(clock.instant());
    }
    private static boolean ready(Delivery entry, TenantId tenant, Instant now) {
        return entry.receipt.deliveryRef().tenantId().equals(tenant) && (entry.receipt.status() == CallbackDeliveryStatus.READY && !entry.availableAt.isAfter(now)
                || entry.receipt.status() == CallbackDeliveryStatus.PROCESSING && !entry.lease.leaseUntil().isAfter(now));
    }
    private static boolean terminal(CallbackDeliveryReceipt receipt) {
        return receipt.status() == CallbackDeliveryStatus.DELIVERED || receipt.status() == CallbackDeliveryStatus.REJECTED || receipt.status() == CallbackDeliveryStatus.DEAD_LETTER;
    }
    private PortFailure failure(String reason) { return new PortFailure(reason, RetryMode.NONE, audit(reason)); }
    private ResultRef audit(String reason) { var ref = new ResultRef("callback-audit:" + UUID.randomUUID()); audits.put(ref, new Audit(reason, clock.instant())); return ref; }
    /** 长度分帧覆盖 envelope 全字段，拒绝记录不保留可逆原文或原 command 引用。 */
    private static String digest(VerifiedCallbackDelivery value) {
        var e = value.envelope(); var target = e.runTarget() instanceof RunTarget.ExistingRun existing ? existing.runRef() : null;
        var fields = new ArrayList<>(List.of(value.callbackRef().tenantId().value(), value.callbackRef().callbackId(), Long.toString(value.expectedRegistrationRevision()),
                value.deliveryRef().tenantId().value(), value.deliveryRef().deliveryId(), value.requestFingerprint(), value.verifiedAt().toString(),
                e.authenticationRef().value(), e.causationIdentityRef().orElseThrow().value(), e.sessionRef().tenantId().value(), e.sessionRef().sessionId(),
                e.agentId().value(), e.subject().toString(), e.sessionTarget().kind().name(), e.sessionTarget().id(),
                e.inputId().value(), e.eventId().value(), e.kind().name(),
                target == null ? "new" : "existing", target == null ? "" : target.tenantId().value(), target == null ? "" : target.runId(),
                e.channel(), e.source(), e.trace().traceId(), e.trace().spanId(), e.trace().rootInputId().value(),
                e.trace().causedByEventId().map(EventId::value).orElse(""), e.payload().schemaId(), e.payload().canonicalJson(), e.receivedAt().toString(),
                e.originRef().tenantId().value(), e.originRef().originId()));
        fields.addAll(switch (e.requester()) {
            case RunRequester.EndUser user -> List.of("END_USER", Long.toString(user.endUserId().value()));
            case RunRequester.System system -> List.of("SYSTEM", system.principal().tenantId().value(), system.principal().principalId());
        });
        e.sourceMessageRef().ifPresentOrElse(source -> fields.addAll(List.of("present", source.sessionRef().tenantId().value(),
                source.sessionRef().sessionId(), source.messageId())), () -> fields.add("absent"));
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (var field : fields) { var bytes = field.getBytes(StandardCharsets.UTF_8); hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII)); hash.update((byte) ':'); hash.update(bytes); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Key(TenantId tenant, IdempotencyKey key) { }
    private record Operation(Object command, long revision) { }
    private static final class Registration {
        final RegisterCallback command; CallbackRegistration record; final Map<RequestId, Operation> operations = new HashMap<>();
        Registration(RegisterCallback command, CallbackRegistration record) { this.command = command; this.record = record; }
    }
    private static final class Delivery {
        final String digest; CallbackDeliveryReceipt receipt; Instant availableAt; LeaseFence lease;
        final Map<RequestId, Operation> operations = new HashMap<>();
        Delivery(String digest, CallbackDeliveryReceipt receipt, Instant availableAt) { this.digest = digest; this.receipt = receipt; this.availableAt = availableAt; }
    }
}
