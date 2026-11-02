package com.skillforge.core.foundation.integration.runtime.callback;

import com.skillforge.runtime.adapter.jdbc.callback.CallbackIngressSupport;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackInstantTypeHandler;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.AdmissionResource;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 受信 HMAC 入口；仅保留摘要和首次系统认证元数据，不保存原始业务载荷。 */
public final class JdbcCallbackIngress {
    private final SqlSessionFactory sessions;
    private final JdbcCallbackSigningKeys keys;
    private final JdbcIdentityPolicyPort identities;
    private final Function<TenantId, PrincipalRef.SystemPrincipal> systemPrincipal;
    private final Predicate<TypedPayload> businessSchema;
    private final Duration signatureWindow, recoveryWindow;
    private final int maximumBodyBytes;

    public JdbcCallbackIngress(DataSource source, JdbcCallbackSigningKeys keys, JdbcIdentityPolicyPort identities,
            Function<TenantId, PrincipalRef.SystemPrincipal> systemPrincipal, Predicate<TypedPayload> businessSchema,
            Duration signatureWindow, Duration recoveryWindow, int maximumBodyBytes) {
        this.keys = Objects.requireNonNull(keys); this.identities = Objects.requireNonNull(identities);
        this.systemPrincipal = Objects.requireNonNull(systemPrincipal); this.businessSchema = Objects.requireNonNull(businessSchema);
        this.signatureWindow = positive(signatureWindow); this.recoveryWindow = positive(recoveryWindow);
        if (maximumBodyBytes <= 0) throw new IllegalArgumentException("callback_body_limit_invalid");
        this.maximumBodyBytes = maximumBodyBytes;
        var config = new Configuration(new Environment("callback-ingress", new JdbcTransactionFactory(), Objects.requireNonNull(source)));
        config.setMapUnderscoreToCamelCase(true);
        config.getTypeHandlerRegistry().register(Instant.class, new CallbackInstantTypeHandler());
        CallbackIngressSupport.configure(config);
        for (String resource : List.of("mapper/AgentRuntimeCallbackIngressMapper.xml")) {
            try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (xml == null) throw new IllegalStateException("callback_ingress_mapper_missing");
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            } catch (Exception failure) { throw new IllegalStateException("callback_ingress_initialization_failure", failure); }
        }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    /** HTTP 重试仍验证当前签名，但同业务内容复用第一次认证、身份、时间及完整命令。 */
    public VerifiedCallbackDelivery verify(CallbackRef callback, CallbackDeliveryRef delivery,
            String timestamp, String signature, byte[] body) {
        Objects.requireNonNull(callback); Objects.requireNonNull(delivery); Objects.requireNonNull(body);
        if (body.length > maximumBodyBytes) throw rejected();
        byte[] owned = body.clone();
        try (var session = sessions.openSession(false)) {
            var proofs = session.getMapper(CallbackIngressMapper.class);
            var fact = CallbackIngressSupport.lockRegistration(session, callback).orElseThrow(JdbcCallbackIngress::rejected);
            var registration = fact.registration(); var draft = registration.draft();
            Instant now = fact.storageTime();
            byte[] signed = CallbackPayloads.signingBytes(callback, delivery, timestamp, owned);
            Instant sent = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(sent, now).abs().compareTo(signatureWindow) > 0
                    || !CallbackPayloads.SIGNATURE_POLICY.equals(draft.signaturePolicyId())
                    || !keys.verify(draft.signingCredentialRef(), signed, signature)) throw rejected();
            TypedPayload payload = CallbackPayloads.decode(registration, owned);
            if (!businessSchema.test(CallbackPayloads.businessPayload(registration, owned))) throw rejected();
            // 摘要绑定 callback、delivery 和完整业务字节，排除每次 HTTP 可变化的签名时间。
            String fingerprint = CallbackIngressSupport.fingerprint(callback, delivery, owned);
            var proof = proofs.find(delivery.deliveryId());
            if (proof != null && (!proof.callbackId.equals(callback.callbackId()) || !proof.fingerprint.equals(fingerprint))) throw rejected();
            if (proof == null) {
                if (registration.status() != CallbackRegistrationStatus.ACTIVE) throw rejected();
                proof = new CallbackIngressRow(); proof.tenantId = callback.tenantId().value();
                proof.callbackId = callback.callbackId(); proof.deliveryId = delivery.deliveryId();
                proof.fingerprint = fingerprint; proof.registrationRevision = registration.revision(); proof.verifiedAt = now;
                proof.authenticationRef = identities.registerTrusted(systemPrincipal.apply(callback.tenantId()), draft.owner(),
                        "callback", draft.source(), recoveryWindow).value();
            }
            var command = command(registration, fact.owningRun(), delivery, payload, proof);
            String digest = CallbackIngressSupport.commandDigest(command);
            if (proof.commandDigest != null) {
                if (!proof.commandDigest.equals(digest)) throw rejected();
            } else {
                var e = command.envelope();
                var verified = identities.verify(new IdentityPolicyPort.IdentityRequest(e.authenticationRef(), draft.owner(),
                        e.channel(), e.source(), new RequestId("callback:" + delivery.deliveryId()), Optional.of(e.inputId()),
                        e.trace().traceId(), e.trace().spanId()));
                if (!(verified instanceof IdentityPolicyPort.IdentityResult.Verified identity)
                        || !(identity.identity().principal() instanceof PrincipalRef.SystemPrincipal)
                        || !(identities.authorize(new IdentityPolicyPort.AuthorizationRequest(identity.identity(), "runtime.accept",
                            AdmissionResource.encode(e), e.trace().traceId(), e.trace().spanId()))
                            instanceof IdentityPolicyPort.AuthorizationResult.Allowed)) throw rejected();
                proof.commandDigest = digest;
                if (proofs.insert(proof) != 1) throw new IllegalStateException("callback_proof_insert_failed");
            }
            session.commit(); return command;
        } catch (IllegalArgumentException denied) {
            // 独立提交安全拒绝事实，业务事务回滚不吞掉拒绝证据；只写摘要。
            try (var audit = sessions.openSession(false)) {
                CallbackIngressSupport.auditRejected(audit, callback, delivery, timestamp, signature, owned);
                audit.commit();
            }
            throw rejected();
        } finally { Arrays.fill(owned, (byte) 0); }
    }

    /** 作为 registry 的强制 ingressVerifier；DTO 必须完整匹配实际签名入口持久证明。 */
    public boolean verifyProof(CallbackRegistration registration, VerifiedCallbackDelivery command) {
        if (!registration.draft().callbackRef().equals(command.callbackRef())) return false;
        try (var session = sessions.openSession(false)) {
            CallbackIngressSupport.bindTenant(session, command.callbackRef().tenantId());
            var proof = session.getMapper(CallbackIngressMapper.class).find(command.deliveryRef().deliveryId());
            boolean matches = proof != null && proof.callbackId.equals(command.callbackRef().callbackId())
                    && proof.registrationRevision == command.expectedRegistrationRevision()
                    && proof.commandDigest.equals(CallbackIngressSupport.commandDigest(command));
            session.commit(); return matches;
        }
    }

    private static VerifiedCallbackDelivery command(CallbackRegistration registration, RunIdentity originalRun, CallbackDeliveryRef delivery,
            TypedPayload payload, CallbackIngressRow proof) {
        var draft = registration.draft(); var owner = draft.owner();
        String stable = "callback:" + proof.fingerprint;
        var input = new InputId(stable); var event = new EventId(stable);
        var trace = new TraceEnvelope(stable, stable, input, Optional.empty());
        RunTarget target = switch (draft.target()) {
            case CallbackTarget.ExecutionTarget execution ->
                    new RunTarget.ExistingRun(execution.owningRunRef());
        };
        var envelope = new InboundEnvelope(new AuthenticationRef(proof.authenticationRef), Optional.of(registration.causationIdentityRef()),
                owner.sessionRef(), owner.target(), draft.agentId(), owner.subject(), originalRun.requester(), input, event, InboundKind.CALLBACK, target,
                "callback", draft.source(), trace, payload, proof.verifiedAt, originalRun.sourceMessageRef(), originalRun.originRef());
        return new VerifiedCallbackDelivery(draft.callbackRef(), proof.registrationRevision, delivery,
                proof.fingerprint, envelope, proof.verifiedAt);
    }
    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("callback_window_invalid");
        return value;
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("callback_ingress_rejected"); }
}

interface CallbackIngressMapper {
    CallbackIngressRow find(@Param("delivery") String delivery);
    int insert(CallbackIngressRow row);
}
final class CallbackIngressRow {
    public String tenantId, callbackId, deliveryId, fingerprint, authenticationRef, commandDigest;
    public long registrationRevision;
    public Instant verifiedAt;
}
