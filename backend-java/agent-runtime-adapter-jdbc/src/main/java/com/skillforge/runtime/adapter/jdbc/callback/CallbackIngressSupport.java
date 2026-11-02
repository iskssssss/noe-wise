package com.skillforge.runtime.adapter.jdbc.callback;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.api.port.RunIdentity;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/** 签名入口对 Runtime Callback 账本的受控访问；不提供身份认证或签名权限。 */
public final class CallbackIngressSupport {
    private CallbackIngressSupport() { }

    public static void configure(Configuration configuration) {
        JdbcCallbackRegistrationPort.configure(configuration);
    }

    /** 入口原事务绑定真实租户并锁定注册事实；调用方仍须验证当前签名与状态。 */
    public static Optional<RegistrationFact> lockRegistration(SqlSession session, CallbackRef reference) {
        var mapper = session.getMapper(CallbackMapper.class);
        mapper.bindTenant(reference.tenantId().value());
        mapper.lockTenant(reference.tenantId().value());
        var row = mapper.registration(reference, true);
        if (row == null) return Optional.empty();
        var command = CallbackCodec.decode(row.commandData, RegisterCallback.class);
        return Optional.of(new RegistrationFact(row.restore(), command.owningRun().orElseThrow(), mapper.now()));
    }

    public static void bindTenant(SqlSession session, TenantId tenant) {
        session.getMapper(CallbackMapper.class).bindTenant(tenant.value());
    }

    /** 稳定业务字节摘要排除每次 HTTP 重试的签名时间。 */
    public static String fingerprint(CallbackRef callback, CallbackDeliveryRef delivery, byte[] body) {
        return JdbcCallbackRegistrationPort.digest(Base64.getEncoder().encodeToString(
                CallbackPayloads.signingBytes(callback, delivery, "1", body)));
    }

    public static String commandDigest(VerifiedCallbackDelivery command) {
        return JdbcCallbackRegistrationPort.digest(CallbackCodec.encode(command));
    }

    /** 只写拒绝摘要，调用方负责原安全审计事务的提交。 */
    public static void auditRejected(SqlSession session, CallbackRef callback, CallbackDeliveryRef delivery,
            String timestamp, String signature, byte[] body) {
        var mapper = session.getMapper(CallbackMapper.class);
        mapper.bindTenant(callback.tenantId().value());
        String digest = JdbcCallbackRegistrationPort.digest(CallbackCodec.encode(List.of(
                callback.callbackId(), delivery.deliveryId(), Objects.toString(timestamp, ""),
                Objects.toString(signature, ""), Base64.getEncoder().encodeToString(body))));
        mapper.audit("delivery", delivery.deliveryId(), digest, "callback-audit:" + UUID.randomUUID(),
                "callback_ingress_rejected");
    }

    public record RegistrationFact(CallbackRegistration registration, RunIdentity owningRun, Instant storageTime) { }
}
