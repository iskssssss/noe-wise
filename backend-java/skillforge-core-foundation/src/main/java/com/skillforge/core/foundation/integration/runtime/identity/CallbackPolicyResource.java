package com.skillforge.core.foundation.integration.runtime.identity;

import com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields;
import com.skillforge.runtime.adapter.jdbc.identity.PolicyResourceFields;
import com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource;

import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackSigningKeys;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionOwner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Callback 控制面明确动作资源；原签名凭据、目标及因果身份均纳入规范值。 */
public final class CallbackPolicyResource {
    public static final String REGISTER_SCHEMA = "skillforge.runtime.callback.register-resource.v2";
    public static final String STATUS_SCHEMA = "skillforge.runtime.callback.status-resource.v2";
    private CallbackPolicyResource() { }

    public static TypedPayload registration(RegisterCallback command) {
        return new TypedPayload(REGISTER_SCHEMA, json(fields(command.registration(), command.causationIdentityRef())));
    }
    public static TypedPayload status(CallbackRegistration original, ChangeCallbackStatus command) {
        if (!original.draft().callbackRef().equals(command.callbackRef())
                || !original.draft().owner().equals(command.requester().owner())) {
            throw new IllegalArgumentException("callback_status_identity_mismatch");
        }
        var fields = fields(original.draft(), original.causationIdentityRef());
        fields.add(Long.toString(command.expectedRevision())); fields.add(command.targetStatus().name());
        return new TypedPayload(STATUS_SCHEMA, json(fields));
    }

    /** 只支持已冻结的 target/schema 和一个签名策略；不从资源文本生成授权主体。 */
    public static Optional<SessionOwner> owner(String action, TypedPayload resource) {
        boolean status = "callback.status".equals(action);
        if (resource == null || !(status ? STATUS_SCHEMA.equals(resource.schemaId())
                : "callback.register".equals(action) && REGISTER_SCHEMA.equals(resource.schemaId()))) return Optional.empty();
        try {
            var f = PolicyResourceFields.parse(resource.canonicalJson(), status ? 19 : 17);
            var tenant = new TenantId(f.get(0));
            var owner = OwnerPolicyFields.owner(tenant,f.get(4),f.get(1),f.get(2),f.get(5));
            var agentId = new AgentId(f.get(3));
            var credential = new CredentialRef(tenant, f.get(10));
            if (credentialPrincipal(credential).isEmpty()) return Optional.empty();
            CallbackTarget target;
            if ("execution".equals(f.get(11))) {
                target = new CallbackTarget.ExecutionTarget(new ExecutionRef(tenant, f.get(12)),
                        new RunRef(tenant, f.get(13)), f.get(14), positive(f.get(15)));
                if (!CallbackPayloads.EXECUTION_SCHEMA.equals(f.get(8))) return Optional.empty();
            } else return Optional.empty();
            if (!CallbackPayloads.SIGNATURE_POLICY.equals(f.get(9))) return Optional.empty();
            new CallbackRegistrationDraft(new CallbackRef(tenant, f.get(6)), owner, agentId, target,
                    f.get(7), f.get(8), f.get(9), credential);
            new ResultRef(f.get(16));
            if (status) { positive(f.get(17)); CallbackRegistrationStatus.valueOf(f.get(18)); }
            return Optional.of(owner);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    /** 新注册属于原 owning Run；状态治理保留既有管理责任，不借当前部署换链。 */
    public static Optional<SessionPolicyResource.ExecutionTarget> execution(String action,TypedPayload resource) {
        if(!"callback.register".equals(action)||owner(action,resource).isEmpty())return Optional.empty();
        var fields=PolicyResourceFields.parse(resource.canonicalJson(),17);
        return Optional.of(new SessionPolicyResource.ExecutionTarget(new RunRef(new TenantId(fields.get(0)),fields.get(13)),
                new AgentId(fields.get(3))));
    }

    /** 只解析现有签名器的规范引用；integration 必须再从原权威主体事实读取核对。 */
    public static Optional<PrincipalRef.ServicePrincipal> credentialPrincipal(CredentialRef credential) {
        try {
            String prefix = "service-principal-v1.";
            if (!credential.value().startsWith(prefix)) return Optional.empty();
            String[] parts = credential.value().substring(prefix.length()).split("\\.", -1);
            if (parts.length != 2) return Optional.empty();
            var principal = new PrincipalRef.ServicePrincipal(credential.tenantId(),
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
                    "reference", positive(parts[1]));
            return JdbcCallbackSigningKeys.reference(principal).equals(credential) ? Optional.of(principal) : Optional.empty();
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private static List<String> fields(CallbackRegistrationDraft draft, ResultRef cause) {
        var owner = draft.owner();
        List<String> values = new ArrayList<>(List.of(owner.sessionRef().tenantId().value(),
                owner.target().kind().name(), owner.target().id(), draft.agentId().value(),
                owner.sessionRef().sessionId(), OwnerPolicyFields.subject(owner),
                draft.callbackRef().callbackId(), draft.source(), draft.payloadSchemaId(), draft.signaturePolicyId(),
                draft.signingCredentialRef().value()));
        if (draft.target() instanceof CallbackTarget.ExecutionTarget target) {
            values.addAll(List.of("execution", target.executionRef().executionId(), target.owningRunRef().runId(),
                    target.actionKey(), Long.toString(target.expectedExecutionRevision())));
        }
        values.add(cause.value()); return values;
    }
    private static long positive(String value) {
        long number = Long.parseLong(value);
        if (number <= 0 || !Long.toString(number).equals(value)) throw new IllegalArgumentException("callback_resource_number");
        return number;
    }
    private static String json(List<String> fields) {
        return fields.stream().map(CallbackPolicyResource::quote).collect(Collectors.joining(",", "[", "]"));
    }
    private static String quote(String text) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if ("\b\t\n\f\r".indexOf(c) >= 0) out.append('\\').append("btnfr".charAt("\b\t\n\f\r".indexOf(c)));
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
