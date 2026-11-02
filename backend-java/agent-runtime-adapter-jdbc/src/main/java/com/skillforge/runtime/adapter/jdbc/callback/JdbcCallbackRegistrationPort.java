package com.skillforge.runtime.adapter.jdbc.callback;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 独立注册与投递账本；原签名入口证明和已提交准入事实通过强制 guard 接入。 */
public final class JdbcCallbackRegistrationPort implements CallbackRegistrationPort {
    private final SqlSessionFactory sessions;
    private final Predicate<RegisterCallback> registerAuthorizer;
    private final BiPredicate<CallbackRegistration, ChangeCallbackStatus> statusAuthorizer;
    private final BiPredicate<CallbackRegistration, VerifiedCallbackDelivery> ingressVerifier;

    /** 加入调用方事务配置；numeric callback时间使用专属resultMap，不改变其他领域Instant映射。 */
    public static void configure(Configuration configuration) {
        Objects.requireNonNull(configuration);
        if (configuration.hasMapper(CallbackMapper.class)) return;
        String resource = "mapper/AgentRuntimeCallbackMapper.xml";
        try (InputStream xml = JdbcCallbackRegistrationPort.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("callback_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("callback_initialization_failure", failure); }
    }

    /**
     * 新Workflow等待恢复已由调用方同事务核实后，确认原HMAC ingress事实的准确delivery。
     * 调用方已持有Run/Execution锁；本方法只锁注册和投递，不绑定租户、不提交或开启连接。
     */
    public void confirmWorkflowDelivery(SqlSession session, CallbackDeliveryRef reference,
            CallbackRegistration expected, InboundEnvelope envelope) {
        Objects.requireNonNull(session); Objects.requireNonNull(reference); Objects.requireNonNull(expected); Objects.requireNonNull(envelope);
        var callback = expected.draft().callbackRef(); var mapper = session.getMapper(CallbackMapper.class);
        if (!reference.tenantId().equals(callback.tenantId()) || !reference.tenantId().equals(envelope.sessionRef().tenantId())
                || !reference.tenantId().value().equals(mapper.workflowTenant())) throw rejectedWorkflow("callback_workflow_tenant_mismatch");
        var registration = mapper.workflowRegistration(callback);
        if (registration == null || !registration.restore().equals(expected) || expected.status() != CallbackRegistrationStatus.ACTIVE
                || !(expected.draft().target() instanceof CallbackTarget.ExecutionTarget target))
            throw rejectedWorkflow("callback_workflow_registration_changed");
        var row = mapper.workflowDelivery(reference);
        if (row == null || !row.callbackId.equals(callback.callbackId()) || row.envelopeData == null || row.inputId == null
                || !row.restore().envelope().orElseThrow().equals(envelope) || !row.inputId.equals(envelope.inputId().value()))
            throw rejectedWorkflow("callback_workflow_delivery_mismatch");
        var draft = expected.draft();
        if (!draft.owner().equals(new SessionOwner(envelope.sessionRef(), envelope.subject(), envelope.sessionTarget()))
                || !draft.agentId().equals(envelope.agentId()) || !draft.source().equals(envelope.source())
                || !draft.payloadSchemaId().equals(envelope.payload().schemaId()) || envelope.kind() != InboundKind.CALLBACK
                || !envelope.causationIdentityRef().equals(Optional.of(expected.causationIdentityRef()))
                || !(envelope.runTarget() instanceof RunTarget.ExistingRun run) || !run.runRef().equals(target.owningRunRef()))
            throw rejectedWorkflow("callback_workflow_source_mismatch");
        var proof = mapper.workflowProof(reference);
        if (proof == null || !proof.callbackId.equals(callback.callbackId()) || proof.registrationRevision > expected.revision()
                || !proof.authenticationRef.equals(envelope.authenticationRef().value()) || !proof.verifiedAt.equals(envelope.receivedAt()))
            throw rejectedWorkflow("callback_workflow_proof_mismatch");
        var original = new VerifiedCallbackDelivery(callback,proof.registrationRevision,reference,proof.fingerprint,envelope,proof.verifiedAt);
        String fingerprint = digest(CallbackCodec.encode(original));
        if (!fingerprint.equals(proof.commandDigest) || !fingerprint.equals(row.commandDigest))
            throw rejectedWorkflow("callback_workflow_proof_mismatch");
        if ("DELIVERED".equals(row.status)) {
            if (!mapper.workflowBinding(reference,target,envelope)) throw rejectedWorkflow("callback_workflow_closure_missing");
            return;
        }
        if (!"READY".equals(row.status) && !"PROCESSING".equals(row.status)) throw rejectedWorkflow("callback_workflow_delivery_closed");
        if (mapper.bindWorkflowDelivery(reference,target,envelope) != 1) throw rejectedWorkflow("callback_workflow_wait_mismatch");
        long previous = row.revision; boolean ready = "READY".equals(row.status); row.status = "DELIVERED"; row.revision = Math.incrementExact(previous);
        // 直接交给真实Workflow业务的首次投递也确实发生过一次；不是Provider Attempt或虚构claim。
        if (ready) row.attempt = Math.incrementExact(row.attempt);
        var now = mapper.workflowNow(); var seconds = now.setScale(0, java.math.RoundingMode.FLOOR);
        row.updatedAt = Instant.ofEpochSecond(seconds.longValueExact(),now.subtract(seconds).movePointRight(9).intValueExact());
        row.leaseClaim = null; row.leaseWorker = null; row.leaseToken = null; row.leaseUntil = null;
        if (mapper.confirmWorkflowDelivery(row,previous) != 1) throw rejectedWorkflow("callback_workflow_delivery_changed");
    }

    private static com.skillforge.runtime.session.spi.ExecutionStore.Rejection rejectedWorkflow(String reason) {
        return new com.skillforge.runtime.session.spi.ExecutionStore.Rejection(reason);
    }

    public JdbcCallbackRegistrationPort(DataSource dataSource, Predicate<RegisterCallback> registerAuthorizer,
            BiPredicate<CallbackRegistration, ChangeCallbackStatus> statusAuthorizer,
            BiPredicate<CallbackRegistration, VerifiedCallbackDelivery> ingressVerifier) {
        this.registerAuthorizer = Objects.requireNonNull(registerAuthorizer);
        this.statusAuthorizer = Objects.requireNonNull(statusAuthorizer);
        this.ingressVerifier = Objects.requireNonNull(ingressVerifier);
        var configuration = new Configuration(new Environment("callback-registry",
                new JdbcTransactionFactory(), Objects.requireNonNull(dataSource)));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(Instant.class, new CallbackInstantTypeHandler());
        configure(configuration);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Override public CallbackRegisterResult register(RegisterCallback command) {
        Objects.requireNonNull(command);
        if (!registerAuthorizer.test(command)) return new CallbackRegisterResult.Rejected(failure("callback_register_denied"));
        return transaction(command.registration().callbackRef().tenantId(), mapper -> {
            mapper.lockTenant(command.registration().callbackRef().tenantId().value());
            var ref = command.registration().callbackRef();
            var previous = mapper.registration(ref, true);
            var byKey = mapper.registrationKey(ref.tenantId().value(), command.idempotencyKey().value());
            String encoded = CallbackCodec.encode(command);
            if (previous != null || byKey != null) {
                if (previous != null && byKey != null && previous.callbackId.equals(byKey.callbackId)
                        && previous.commandData.equals(encoded)) return new CallbackRegisterResult.Replayed(previous.restore());
                return new CallbackRegisterResult.Conflict(ref, audit(mapper, "registration", ref.callbackId(), encoded, "callback_register_conflict"));
            }
            Instant now = mapper.now();
            var row = CallbackRegistrationRow.initial(command, now);
            if (mapper.insertRegistration(row) != 1) throw new IllegalStateException("callback_registration_insert_failed");
            return new CallbackRegisterResult.Registered(row.restore());
        });
    }

    @Override public Optional<CallbackRegistration> find(CallbackRef ref) {
        Objects.requireNonNull(ref);
        return transaction(ref.tenantId(), mapper -> Optional.ofNullable(mapper.registration(ref, false)).map(CallbackRegistrationRow::restore));
    }

    /** 精确读取原已验证delivery回执；不领取、不改变lease或生成旧Inbox输入。 */
    public Optional<CallbackDeliveryReceipt> delivery(CallbackDeliveryRef reference) {
        Objects.requireNonNull(reference);
        return transaction(reference.tenantId(),mapper -> Optional.ofNullable(mapper.delivery(reference,false)).map(CallbackDeliveryRow::restore));
    }

    /** 注册行锁线性化暂停/撤销和新 delivery 接收；旧已收录 delivery 不随注册状态删除。 */
    @Override public CallbackStatusResult changeStatus(ChangeCallbackStatus command) {
        Objects.requireNonNull(command);
        return transaction(command.callbackRef().tenantId(), mapper -> {
            var row = mapper.registration(command.callbackRef(), true);
            if (row == null) return new CallbackStatusResult.NotFound(failure("callback_registration_not_found"));
            var current = row.restore();
            if (!current.draft().owner().equals(command.requester().owner()) || !statusAuthorizer.test(current, command))
                return new CallbackStatusResult.Rejected(failure("callback_status_denied"));
            String encoded = CallbackCodec.encode(command);
            var previous = mapper.registrationOperation(row.callbackId, command.operationId().value());
            if (previous != null) {
                return previous.commandData.equals(encoded) && previous.outputRevision == row.revision
                    ? new CallbackStatusResult.Replayed(current)
                    : new CallbackStatusResult.Conflict(current, audit(mapper, "registration", row.callbackId, encoded, "callback_status_conflict"));
            }
            if (row.revision != command.expectedRevision()) return new CallbackStatusResult.Conflict(current,
                    audit(mapper, "registration", row.callbackId, encoded, "callback_revision_conflict"));
            if (current.status() == CallbackRegistrationStatus.REVOKED || current.status() == command.targetStatus())
                return new CallbackStatusResult.Rejected(failure("callback_status_transition_rejected"));
            long before = row.revision;
            row.status = command.targetStatus().name(); row.revision = Math.incrementExact(row.revision);
            row.updatedAt = mapper.now();
            if (mapper.updateRegistration(row, before) != 1
                    || mapper.insertRegistrationOperation(row.callbackId, command.operationId().value(), encoded, row.revision) != 1)
                throw new IllegalStateException("callback_status_cas_failed");
            return new CallbackStatusResult.Applied(row.restore());
        });
    }

    /** 新拒绝只存全命令摘要与安全拒绝事实，不存原 body/envelope/VerifiedCallbackDelivery。 */
    @Override public CallbackDeliveryResult acceptVerified(VerifiedCallbackDelivery command) {
        Objects.requireNonNull(command);
        return transaction(command.callbackRef().tenantId(), mapper -> {
            mapper.lockTenant(command.callbackRef().tenantId().value());
            var registration = mapper.registration(command.callbackRef(), true);
            var previous = mapper.delivery(command.deliveryRef(), true);
            String digest = digest(CallbackCodec.encode(command));
            if (previous != null) return previous.commandDigest.equals(digest)
                    ? new CallbackDeliveryResult.Replayed(previous.restore())
                    : new CallbackDeliveryResult.Conflict(command.deliveryRef(),
                        audit(mapper, "delivery", command.deliveryRef().deliveryId(), digest, "callback_delivery_conflict"));
            var current = registration == null ? null : registration.restore();
            boolean accepted = current != null && matches(current, command) && ingressVerifier.test(current, command);
            Instant now = mapper.now();
            var row = CallbackDeliveryRow.initial(command, accepted, now);
            if (!accepted) row.auditRef = audit(mapper, "delivery", row.deliveryId, digest, "callback_ingress_rejected").value();
            if (mapper.insertDelivery(row) != 1) throw new IllegalStateException("callback_delivery_insert_failed");
            return accepted ? new CallbackDeliveryResult.Ready(row.restore()) : new CallbackDeliveryResult.Rejected(row.restore());
        });
    }

    private static boolean matches(CallbackRegistration registration, VerifiedCallbackDelivery command) {
        var draft = registration.draft(); var e = command.envelope();
        return registration.status() == CallbackRegistrationStatus.ACTIVE && registration.revision() == command.expectedRegistrationRevision()
                && draft.owner().equals(new SessionOwner(e.sessionRef(), e.subject(), e.sessionTarget()))
                && draft.agentId().equals(e.agentId())
                && draft.callbackRef().equals(command.callbackRef()) && draft.source().equals(e.source())
                && draft.payloadSchemaId().equals(e.payload().schemaId())
                && e.causationIdentityRef().equals(Optional.of(registration.causationIdentityRef()))
                && draft.target() instanceof CallbackTarget.ExecutionTarget target
                && e.runTarget() instanceof RunTarget.ExistingRun run && run.runRef().equals(target.owningRunRef());
    }

    @Override public List<CallbackReadyDelivery> scanReady(TenantId tenant, int limit) {
        Objects.requireNonNull(tenant); if (limit <= 0) throw new IllegalArgumentException("callback_limit_invalid");
        return transaction(tenant, mapper -> mapper.ready(limit, mapper.now(), false).stream().map(row -> {
            var receipt = row.restore(); var envelope = receipt.envelope().orElseThrow();
            return new CallbackReadyDelivery(receipt.deliveryRef(), receipt.callbackRef(),
                    new SessionOwner(envelope.sessionRef(), envelope.subject(), envelope.sessionTarget()), receipt.ledgerRevision(), receipt.recordedAt());
        }).toList());
    }

    /** 原delivery关系的有界键集扫描；调用方先推进实际页尾再按自己的业务目标筛选。 */
    public List<CallbackReadyDelivery> scanReadyAfter(TenantId tenant,String afterDeliveryId,int limit) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(afterDeliveryId);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("callback_limit_invalid");
        return transaction(tenant,mapper -> mapper.readyAfter(afterDeliveryId,limit,mapper.now()).stream().map(row -> {
            var receipt = row.restore(); var envelope = receipt.envelope().orElseThrow();
            return new CallbackReadyDelivery(receipt.deliveryRef(),receipt.callbackRef(),new SessionOwner(envelope.sessionRef(), envelope.subject(), envelope.sessionTarget()),receipt.ledgerRevision(),receipt.recordedAt());
        }).toList());
    }

    /** 领取和失效接管保持原 envelope，仅推进 delivery revision/attempt/fence。 */
    @Override public CallbackClaimResult claimReady(CallbackClaimRequest request) {
        Objects.requireNonNull(request);
        return transaction(request.tenantId(), mapper -> {
            List<CallbackClaim> claims = new ArrayList<>();
            for (var row : mapper.ready(request.limit(), mapper.now(), true)) {
                row.restore();
                long before = row.revision;
                row.revision = Math.incrementExact(row.revision); row.attempt = Math.incrementExact(row.attempt);
                row.status = "PROCESSING"; row.updatedAt = mapper.now();
                row.leaseClaim = UUID.randomUUID().toString(); row.leaseWorker = request.workerId();
                row.leaseToken = mapper.nextFence(); row.leaseUntil = row.updatedAt.plus(request.leaseDuration());
                update(mapper, row, before);
                claims.add(new CallbackClaim(row.restore(), new CallbackLease(row.ref(), row.fence(), row.revision)));
            }
            return claims.isEmpty() ? new CallbackClaimResult.Empty() : new CallbackClaimResult.Claimed(claims);
        });
    }

    @Override public CallbackMutationResult renew(CallbackRenew command) {
        Objects.requireNonNull(command);
        return mutate(command.lease(), command.operationId(), command, row -> row.leaseUntil = row.leaseUntil.plus(command.extension()));
    }
    @Override public CallbackMutationResult retry(CallbackRetry command) {
        Objects.requireNonNull(command);
        return mutate(command.lease(), command.operationId(), command, row -> {
            row.status = "READY"; row.availableAt = command.availableAt();
        });
    }
    @Override public CallbackMutationResult deadLetter(CallbackDeadLetter command) {
        Objects.requireNonNull(command);
        return mutate(command.lease(), command.operationId(), command, row -> {
            row.status = "DEAD_LETTER"; row.reasonCode = command.failure().reasonCode(); row.auditRef = command.failure().resultRef().value();
        });
    }

    private CallbackMutationResult mutate(CallbackLease lease, RequestId operation, Object command, Consumer<CallbackDeliveryRow> change) {
        return transaction(lease.deliveryRef().tenantId(), mapper -> {
            var row = mapper.delivery(lease.deliveryRef(), true);
            if (row == null) return new CallbackMutationResult.NotFound(failure("callback_delivery_not_found"));
            var current = row.restore(); String encoded = CallbackCodec.encode(command);
            var previous = mapper.deliveryOperation(row.deliveryId, operation.value());
            if (previous != null) return previous.commandData.equals(encoded) && previous.outputRevision == row.revision
                    ? new CallbackMutationResult.AlreadyApplied(current)
                    : new CallbackMutationResult.Conflict(current, audit(mapper, "delivery", row.deliveryId, encoded, "callback_mutation_conflict"));
            if (row.terminal()) return new CallbackMutationResult.Terminal(current);
            if (!row.writable(lease, mapper.now())) return new CallbackMutationResult.StaleLease(current, failure("callback_stale_lease"));
            Instant now = mapper.now();
            if (!row.writable(lease, now)) return new CallbackMutationResult.StaleLease(current, failure("callback_stale_lease"));
            long before = row.revision; change.accept(row); row.revision = Math.incrementExact(row.revision); row.updatedAt = now;
            if (!row.status.equals("PROCESSING")) { row.leaseClaim = null; row.leaseWorker = null; row.leaseToken = null; row.leaseUntil = null; }
            update(mapper, row, before);
            if (mapper.insertDeliveryOperation(row.deliveryId, operation.value(), encoded, row.revision) != 1)
                throw new IllegalStateException("callback_delivery_operation_failed");
            return new CallbackMutationResult.Applied(row.restore());
        });
    }

    private static void update(CallbackMapper mapper, CallbackDeliveryRow row, long before) {
        if (mapper.updateDelivery(row, before) != 1) throw new IllegalStateException("callback_delivery_cas_failed");
    }
    static String digest(String encoded) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("callback-v1:" + encoded).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static ResultRef audit(CallbackMapper mapper, String kind, String id, String command, String reason) {
        return new ResultRef(mapper.audit(kind, id, digest(command), "callback-audit:" + UUID.randomUUID(), reason));
    }
    private static PortFailure failure(String reason) { return new PortFailure(reason, RetryMode.NONE, new ResultRef(reason)); }
    private <T> T transaction(TenantId tenant, Function<CallbackMapper,T> work) {
        try (var session = sessions.openSession(false)) {
            try {
                var mapper = session.getMapper(CallbackMapper.class); mapper.bindTenant(tenant.value());
                T result = work.apply(mapper); session.commit(); return result;
            } catch (RuntimeException failure) { session.rollback(); throw failure; }
        } catch (RuntimeException failure) { throw new IllegalStateException("callback_storage_failure", failure); }
    }
}

interface CallbackMapper {
    String workflowTenant();
    CallbackRegistrationRow workflowRegistration(@Param("ref") CallbackRegistrationPort.CallbackRef reference);
    CallbackDeliveryRow workflowDelivery(@Param("ref") CallbackRegistrationPort.CallbackDeliveryRef reference);
    CallbackWorkflowProofRow workflowProof(@Param("ref") CallbackRegistrationPort.CallbackDeliveryRef reference);
    java.math.BigDecimal workflowNow();
    int confirmWorkflowDelivery(@Param("row") CallbackDeliveryRow row,@Param("before") long before);
    int bindWorkflowDelivery(@Param("ref") CallbackRegistrationPort.CallbackDeliveryRef reference,
            @Param("target") CallbackRegistrationPort.CallbackTarget.ExecutionTarget target,@Param("envelope") InboundEnvelope envelope);
    boolean workflowBinding(@Param("ref") CallbackRegistrationPort.CallbackDeliveryRef reference,
            @Param("target") CallbackRegistrationPort.CallbackTarget.ExecutionTarget target,@Param("envelope") InboundEnvelope envelope);
    String bindTenant(@Param("tenant") String tenant);
    void lockTenant(@Param("tenant") String tenant);
    Instant now();
    long nextFence();
    CallbackRegistrationRow registration(@Param("ref") CallbackRegistrationPort.CallbackRef ref, @Param("lock") boolean lock);
    CallbackRegistrationRow registrationKey(@Param("tenant") String tenant, @Param("key") String key);
    int insertRegistration(CallbackRegistrationRow row);
    int updateRegistration(@Param("row") CallbackRegistrationRow row, @Param("before") long before);
    CallbackOperationRow registrationOperation(@Param("id") String id, @Param("operation") String operation);
    int insertRegistrationOperation(@Param("id") String id, @Param("operation") String operation, @Param("command") String command, @Param("revision") long revision);
    CallbackDeliveryRow delivery(@Param("ref") CallbackRegistrationPort.CallbackDeliveryRef ref, @Param("lock") boolean lock);
    List<CallbackDeliveryRow> ready(@Param("limit") int limit, @Param("now") Instant now, @Param("lock") boolean lock);
    List<CallbackDeliveryRow> readyAfter(@Param("after") String after,@Param("limit") int limit,@Param("now") Instant now);
    int insertDelivery(CallbackDeliveryRow row);
    int updateDelivery(@Param("row") CallbackDeliveryRow row, @Param("before") long before);
    CallbackOperationRow deliveryOperation(@Param("id") String id, @Param("operation") String operation);
    int insertDeliveryOperation(@Param("id") String id, @Param("operation") String operation, @Param("command") String command, @Param("revision") long revision);
    String audit(@Param("kind") String kind, @Param("id") String id, @Param("digest") String digest, @Param("audit") String audit, @Param("reason") String reason);
}
/** Runtime 只读取已提交的接入证明；不依赖宿主接入器的持久化类型。 */
final class CallbackWorkflowProofRow {
    public String tenantId, callbackId, deliveryId, fingerprint, authenticationRef, commandDigest;
    public long registrationRevision;
    public Instant verifiedAt;
}
final class CallbackOperationRow { public String commandData; public long outputRevision; }
final class CallbackRegistrationRow {
    public String tenantId, callbackId, idempotencyKey, commandData, status;
    public long revision;
    public Instant createdAt, updatedAt;
    static CallbackRegistrationRow initial(CallbackRegistrationPort.RegisterCallback command, Instant now) {
        var row = new CallbackRegistrationRow();
        row.tenantId = command.registration().callbackRef().tenantId().value(); row.callbackId = command.registration().callbackRef().callbackId();
        row.idempotencyKey = command.idempotencyKey().value(); row.commandData = CallbackCodec.encode(command);
        row.status = "ACTIVE"; row.revision = 1; row.createdAt = now; row.updatedAt = now;
        return row;
    }
    CallbackRegistrationPort.CallbackRegistration restore() {
        var command = CallbackCodec.decode(commandData, CallbackRegistrationPort.RegisterCallback.class);
        if (!command.registration().callbackRef().equals(new CallbackRegistrationPort.CallbackRef(new TenantId(tenantId), callbackId))
                || !command.idempotencyKey().value().equals(idempotencyKey)) throw new IllegalStateException("callback_registration_snapshot_mismatch");
        return new CallbackRegistrationPort.CallbackRegistration(command.registration(), command.causationIdentityRef(),
                CallbackRegistrationPort.CallbackRegistrationStatus.valueOf(status), revision, createdAt, updatedAt);
    }
}
final class CallbackDeliveryRow {
    public String tenantId, deliveryId, callbackId, commandDigest, inputId, envelopeData, status, reasonCode, auditRef, leaseClaim, leaseWorker;
    public long revision;
    public int attempt;
    public Long leaseToken;
    public Instant leaseUntil, availableAt, recordedAt, updatedAt;
    static CallbackDeliveryRow initial(CallbackRegistrationPort.VerifiedCallbackDelivery command, boolean accepted, Instant now) {
        var row = new CallbackDeliveryRow();
        row.tenantId = command.callbackRef().tenantId().value(); row.callbackId = command.callbackRef().callbackId(); row.deliveryId = command.deliveryRef().deliveryId();
        row.commandDigest = JdbcCallbackRegistrationPort.digest(CallbackCodec.encode(command));
        row.inputId = accepted ? command.envelope().inputId().value() : null;
        row.envelopeData = accepted ? CallbackCodec.encode(command.envelope()) : null;
        row.status = accepted ? "READY" : "REJECTED"; row.revision = 1;
        row.reasonCode = accepted ? null : "callback_ingress_rejected";
        row.availableAt = now; row.recordedAt = now; row.updatedAt = now; return row;
    }
    CallbackRegistrationPort.CallbackDeliveryRef ref() { return new CallbackRegistrationPort.CallbackDeliveryRef(new TenantId(tenantId), deliveryId); }
    LeaseFence fence() { return new LeaseFence(new TenantId(tenantId), leaseClaim, leaseWorker, leaseToken, leaseUntil); }
    boolean terminal() { return Set.of("DELIVERED", "REJECTED", "DEAD_LETTER").contains(status); }
    boolean writable(CallbackRegistrationPort.CallbackLease lease, Instant now) {
        return status.equals("PROCESSING") && revision == lease.ledgerRevision() && ref().equals(lease.deliveryRef())
                && fence().equals(lease.fence()) && leaseUntil.isAfter(now);
    }
    CallbackRegistrationPort.CallbackDeliveryReceipt restore() {
        return new CallbackRegistrationPort.CallbackDeliveryReceipt(new CallbackRegistrationPort.CallbackRef(new TenantId(tenantId), callbackId), ref(),
                CallbackRegistrationPort.CallbackDeliveryStatus.valueOf(status), revision,
                Optional.ofNullable(envelopeData).map(value -> CallbackCodec.decode(value, InboundEnvelope.class)),
                Optional.ofNullable(reasonCode), Optional.ofNullable(auditRef).map(ResultRef::new), recordedAt);
    }
}
