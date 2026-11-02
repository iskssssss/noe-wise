package com.skillforge.runtime.adapter.inmemory.identity;
import com.skillforge.runtime.api.PrincipalRef;

import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** development 专用显式信任注册；不能作为生产身份失败后的 fallback，也不从请求正文推断主体。 */
public final class InMemoryIdentityPolicyPort implements IdentityPolicyPort {
    private static final String ACTION = "runtime.accept";
    private static final String REQUIRED_SCOPE = "sessions.write";
    private static final String RESOURCE_SCHEMA = "skillforge.runtime.session.admission-resource.v1";
    private static final long POLICY_REVISION = 10;
    private final Clock clock;
    private final Map<AuthenticationRef, Registration> registrations = new HashMap<>();
    private final Map<ResultRef, Verification> verifications = new HashMap<>();
    private final Map<ResultRef, SecurityAudit> audits = new LinkedHashMap<>();

    public InMemoryIdentityPolicyPort(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }
    public String supportedProfile() { return "development"; }

    /** 仅供受信开发装配显式注册；认证引用随机生成，调用方不能指定或从主体字段算出。 */
    public synchronized AuthenticationRef registerTrusted(TrustedRegistration trusted) {
        Objects.requireNonNull(trusted, "trusted");
        if (!trusted.expiresAt().isAfter(clock.instant())) throw new IllegalArgumentException("identity_registration_expired");
        var reference = new AuthenticationRef("development-auth:" + UUID.randomUUID());
        registrations.put(reference, new Registration(trusted));
        audit("register", "registered", POLICY_REVISION, Optional.empty());
        return reference;
    }

    /** 更新当前权限而不改写已签发的 VerifiedIdentity 历史事实。 */
    public synchronized void updateScopes(AuthenticationRef reference, Set<String> scopes) {
        var current = registration(reference);
        current.scopes = scopes(scopes);
        audit("permissions", "updated", current.revision, Optional.empty());
    }

    public synchronized void revoke(AuthenticationRef reference) {
        var current = registration(reference);
        current.revoked = true;
        audit("registration", "revoked", current.revision, Optional.empty());
    }

    @Override
    public synchronized IdentityResult verify(IdentityRequest request) {
        Objects.requireNonNull(request, "request");
        var current = registrations.get(request.authenticationRef());
        if (current == null || !active(current) || !current.trusted.owner().equals(request.claimedOwner())
                || !current.trusted.channel().equals(request.channel()) || !current.trusted.source().equals(request.source())) {
            return new IdentityResult.Rejected(new PortFailure("identity_rejected", RetryMode.NONE,
                    audit("verify", "rejected", current == null ? POLICY_REVISION : current.revision, Optional.empty())));
        }
        var reference = audit("verify", "verified", current.revision, Optional.empty());
        var identity = new VerifiedIdentity(current.trusted.principal(), current.trusted.owner(), current.scopes, current.revision, reference);
        verifications.put(reference, new Verification(request.authenticationRef(), identity));
        return new IdentityResult.Verified(identity);
    }

    @Override
    public synchronized IdentityResult verifyAccess(AccessIdentityRequest request) {
        Objects.requireNonNull(request, "request");
        var current = registrations.get(request.authenticationRef());
        var owner = current == null ? null : current.trusted.owner();
        if (current == null || !active(current)
                || !owner.sessionRef().tenantId().equals(request.claimedTenantId())
                || !owner.target().equals(request.claimedTarget())
                || owner.target().kind() == com.skillforge.runtime.api.SessionTarget.Kind.AGENT
                    && !java.util.Optional.of(owner.target().requireAgent()).equals(request.claimedAgentId())
                || !owner.subject().equals(request.claimedSubject())
                || !current.trusted.channel().equals(request.channel())
                || !current.trusted.source().equals(request.source())) {
            return new IdentityResult.Rejected(new PortFailure("identity_rejected", RetryMode.NONE,
                    audit("verify-access", "rejected", current == null ? POLICY_REVISION : current.revision, Optional.empty())));
        }
        var reference = audit("verify-access", "verified", current.revision, Optional.empty());
        var identity = new VerifiedIdentity(current.trusted.principal(), owner, current.scopes, current.revision, reference);
        verifications.put(reference, new Verification(request.authenticationRef(), identity));
        return new IdentityResult.Verified(identity);
    }

    @Override
    public synchronized AuthorizationResult authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        var verified = verifications.get(request.identity().verificationRef());
        var current = verified == null ? null : registrations.get(verified.authenticationRef());
        long revision = current == null ? POLICY_REVISION : current.revision;
        boolean allowed = verified != null && verified.identity().equals(request.identity()) && current != null && active(current)
                && current.scopes.contains(readAction(request.action()) ? "sessions.read" : REQUIRED_SCOPE)
                && (ACTION.equals(request.action()) && RESOURCE_SCHEMA.equals(request.resource().schemaId())
                    && resourceMatches(request.resource().canonicalJson(), current.trusted)
                    || callbackMatches(request.action(), request.resource(), current.trusted.owner())
                    || modelMatches(request.action(), request.resource(), current.trusted.owner())
                    || toolMatches(request.action(), request.resource(), current.trusted.owner())
                    || workflowMatches(request.action(), request.resource(), current.trusted.owner())
                    || knowledgeMatches(request.action(), request.resource(), current.trusted.owner())
                    || sessionMatches(request.action(), request.resource(), current.trusted.owner())
                    || facadeMatches(request.action(), request.resource(), current.trusted.owner()));
        var audit = audit("authorize", allowed ? "allowed" : "denied", revision,
                verified == null ? Optional.empty() : Optional.of(verified.identity().verificationRef()));
        return allowed ? new AuthorizationResult.Allowed(revision, audit)
                : new AuthorizationResult.Denied("authorization_denied", revision, audit);
    }

    /** 审计与业务账本独立保存；记录不含 AuthenticationRef、请求载荷或主体原值。 */
    public synchronized Optional<SecurityAudit> audit(ResultRef reference) { return Optional.ofNullable(audits.get(reference)); }

    public record TrustedRegistration(PrincipalRef principal, SessionOwner owner, String channel, String source,
                                      Instant expiresAt, Set<String> scopes) {
        public TrustedRegistration {
            Objects.requireNonNull(expiresAt, "expiresAt"); requireText(channel); requireText(source);
            scopes = InMemoryIdentityPolicyPort.scopes(scopes);
            // 复用公开契约校验 principal 与完整 owner 的租户及终端用户绑定。
            new VerifiedIdentity(principal, owner, scopes, 1, new ResultRef("registration-validation"));
        }
    }
    public record SecurityAudit(String operation, String outcome, long policyRevision, Instant recordedAt,
                                Optional<ResultRef> verificationRef) { }
    private record Verification(AuthenticationRef authenticationRef, VerifiedIdentity identity) { }
    private static final class Registration {
        final TrustedRegistration trusted;
        Set<String> scopes;
        final long revision = POLICY_REVISION;
        boolean revoked;
        Registration(TrustedRegistration trusted) { this.trusted = trusted; scopes = trusted.scopes(); }
    }
    private Registration registration(AuthenticationRef reference) {
        var registration = registrations.get(Objects.requireNonNull(reference, "reference"));
        if (registration == null) throw new IllegalArgumentException("identity_registration_missing"); return registration;
    }
    private boolean active(Registration registration) { return !registration.revoked && clock.instant().isBefore(registration.trusted.expiresAt()); }
    private ResultRef audit(String operation, String outcome, long revision, Optional<ResultRef> verificationRef) {
        var reference = new ResultRef("identity-audit:" + UUID.randomUUID());
        audits.put(reference, new SecurityAudit(operation, outcome, revision, clock.instant(), verificationRef)); return reference;
    }
    private static Set<String> scopes(Set<String> values) {
        var result = Set.copyOf(values); result.forEach(InMemoryIdentityPolicyPort::requireText); return result;
    }

    private static boolean readAction(String action) {
        return Set.of("workflow.query", "runtime.run.query", "runtime.session.query",
                "runtime.events.read").contains(action);
    }

    private static boolean facadeMatches(String action, TypedPayload payload, SessionOwner owner) {
        if (!Set.of("runtime.run.query", "runtime.session.query", "runtime.events.read", "runtime.cancel", "runtime.plan.select", "runtime.run.steer").contains(action)
                || !"skillforge.runtime.facade-access.v1".equals(payload.schemaId())) return false;
        try {
            var reader = new ResourceReader(payload.canonicalJson()); var values = reader.array(0);
            if (reader.position != payload.canonicalJson().length() || values.size() != 9
                    || values.stream().anyMatch(value -> !(value instanceof String))) return false;
            var f = values.stream().map(String.class::cast).toList();
            if (!f.get(0).equals(owner.sessionRef().tenantId().value())
                    || !f.get(1).equals(owner.target().requireAgent().value())
                    || !f.get(2).equals(owner.sessionRef().sessionId())
                    || !f.get(3).equals(Long.toString(owner.requireUser().value()))
                    || !f.get(4).equals(action)) return false;
            long cursor = Long.parseLong(f.get(7));
            if (cursor < 0 || !Long.toString(cursor).equals(f.get(7))) return false;
            if ("runtime.run.query".equals(action) || "runtime.run.steer".equals(action)) {
                if (!f.get(5).equals("run") || cursor != 0) return false;
                new RunRef(owner.sessionRef().tenantId(), f.get(6)); new com.skillforge.runtime.api.RequestId(f.get(8));
            } else if (("runtime.session.query".equals(action) || "runtime.plan.select".equals(action))) {
                if (!f.get(5).equals("session") || !f.get(6).equals(owner.sessionRef().sessionId()) || cursor != 0) return false;
                new com.skillforge.runtime.api.RequestId(f.get(8));
            } else if ("runtime.events.read".equals(action)) {
                if (!f.get(5).equals("session") || !f.get(6).equals(owner.sessionRef().sessionId())) return false;
                new com.skillforge.runtime.api.RequestId(f.get(8));
            } else {
                if (!f.get(5).equals("run") || cursor <= 0) return false;
                new RunRef(owner.sessionRef().tenantId(), f.get(6)); new InputId(f.get(8));
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
    private static void requireText(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("identity_text_missing"); }

    private static boolean sessionMatches(String action,TypedPayload payload,SessionOwner owner) {
        try {
            int size;
            if("session.claim".equals(action)&&"skillforge.runtime.session.claim-resource.v1".equals(payload.schemaId()))size=4;
            else if("session.capabilities.freeze".equals(action)&&"skillforge.runtime.session.capability-freeze-resource.v1".equals(payload.schemaId()))size=5;
            else if("session.execute".equals(action)&&"skillforge.runtime.session.input-resource.v1".equals(payload.schemaId()))size=9;
            else if(Set.of("session.execute","session.commit","session.cancel").contains(action)&&"skillforge.runtime.session.run-resource.v1".equals(payload.schemaId()))size=12;
            else return false;
            var parser=new ResourceReader(payload.canonicalJson());var values=parser.array(0);
            if(parser.position!=payload.canonicalJson().length()||values.size()!=size||values.stream().anyMatch(value->!(value instanceof String)))return false;
            var f=values.stream().map(String.class::cast).toList();
            if(!f.get(0).equals(owner.sessionRef().tenantId().value())||!f.get(1).equals(owner.target().requireAgent().value())||!f.get(2).equals(owner.sessionRef().sessionId())||!f.get(3).equals(Long.toString(owner.requireUser().value())))return false;
            if(size==5)new InputId(f.get(4));
            if(size==9){new InputId(f.get(4));new EventId(f.get(5));new ResultRef(f.get(6));new RunRef(owner.sessionRef().tenantId(),f.get(7));requireText(f.get(8));}
            if(size==12){var trace=new TraceEnvelope(f.get(7),f.get(8),new InputId(f.get(9)),f.get(10).isEmpty()?Optional.empty():Optional.of(new EventId(f.get(10))));
                new RunIdentity(owner, owner.target().requireAgent(), new InputId(f.get(5)), new RunRef(owner.sessionRef().tenantId(),f.get(4)), trace, new ResultRef(f.get(6)), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), new InputId(f.get(5)).value()));
                if(!f.get(11).isEmpty())new InputId(f.get(11));if("session.cancel".equals(action)&&f.get(11).isEmpty())return false;}
            return true;
        } catch(RuntimeException invalid){return false;}
    }

    /** 只接纳 SessionAdmission 的固定数组 schema，完整解析后核对 owner、来源与 target 租户。 */
    private static boolean callbackMatches(String action, com.skillforge.runtime.api.TypedPayload payload, SessionOwner owner) {
        boolean status = "callback.status".equals(action);
        if (!(status ? "skillforge.runtime.callback.status-resource.v1".equals(payload.schemaId())
                : "callback.register".equals(action) && "skillforge.runtime.callback.register-resource.v1".equals(payload.schemaId()))) return false;
        try {
            var reader = new ResourceReader(payload.canonicalJson());
            var f = reader.array(0);
            if (reader.position != payload.canonicalJson().length() || f.size() != (status ? 17 : 15)
                    || f.stream().anyMatch(value -> !(value instanceof String))) return false;
            if (!f.get(0).equals(owner.sessionRef().tenantId().value()) || !f.get(1).equals(owner.target().requireAgent().value())
                    || !f.get(2).equals(owner.sessionRef().sessionId()) || !f.get(3).equals(Long.toString(owner.requireUser().value()))) return false;
            for (int index : new int[]{4, 5, 6, 7, 8, 14}) requireText((String) f.get(index));
            if (!f.get(7).equals("hmac-sha256-callback.v1")) return false;
            String credential = (String) f.get(8);
            String prefix = "service-principal-v1.";
            if (!credential.startsWith(prefix)) return false;
            String[] key = credential.substring(prefix.length()).split("\\.", -1);
            if (key.length != 2) return false;
            String principal = new String(Base64.getUrlDecoder().decode(key[0]), java.nio.charset.StandardCharsets.UTF_8);
            requireText(principal);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(principal.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(key[0])) return false;
            positive((String) key[1]);
            if (f.get(9).equals("session")) {
                if (!f.get(6).equals("skillforge.runtime.callback.session-event.v1")
                        || f.subList(10, 14).stream().anyMatch(value -> !value.equals(""))) return false;
            } else if (f.get(9).equals("execution")) {
                if (!f.get(6).equals("skillforge.runtime.callback.execution-result.v1")) return false;
                requireText((String) f.get(10)); requireText((String) f.get(11)); requireText((String) f.get(12));
                positive((String) f.get(13));
            } else if (f.get(9).equals("action")) {
                if (!f.get(6).equals("skillforge.runtime.callback.action-result.v1")) return false;
                requireText((String) f.get(10)); requireText((String) f.get(11)); requireText((String) f.get(12));
                positive((String) f.get(13));
            } else return false;
            if (status) {
                positive((String) f.get(15));
                CallbackRegistrationPort.CallbackRegistrationStatus.valueOf((String) f.get(16));
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    /** 模型动作使用完整 Run/版本化绑定资源，当前权限仍由原信任登记核验。 */
    private static boolean modelMatches(String action, com.skillforge.runtime.api.TypedPayload payload, SessionOwner owner) {
        if (!("model.resolve".equals(action) && "skillforge.runtime.model.resolve-resource.v1".equals(payload.schemaId())
                || ("model.generate".equals(action) || "model.stream".equals(action))
                    && "skillforge.runtime.model.dispatch-resource.v1".equals(payload.schemaId()))) return false;
        try {
            var reader = new ResourceReader(payload.canonicalJson());
            var f = reader.array(0);
            if (reader.position != payload.canonicalJson().length() || f.size() != 15
                    || f.stream().anyMatch(value -> !(value instanceof String))) return false;
            if (!f.get(0).equals(owner.sessionRef().tenantId().value()) || !f.get(1).equals(owner.target().requireAgent().value())
                    || !f.get(2).equals(owner.sessionRef().sessionId()) || !f.get(3).equals(Long.toString(owner.requireUser().value()))) return false;
            var trace = new TraceEnvelope((String) f.get(7), (String) f.get(8), new InputId((String) f.get(9)),
                    f.get(10).equals("") ? Optional.empty() : Optional.of(new EventId((String) f.get(10))));
            new RunIdentity(owner, owner.target().requireAgent(), new InputId((String) f.get(5)), new RunRef(owner.sessionRef().tenantId(), (String) f.get(4)), trace, new ResultRef((String) f.get(6)), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), new InputId((String) f.get(5)).value()));
            new ModelPort.ModelBindingRef(owner.sessionRef().tenantId(), (String) f.get(11), positive((String) f.get(12)));
            new ModelPort.RequestedModelStrategy((String) f.get(13), (String) f.get(14));
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    /** tool.invoke 的完整目标必须保持版本、schema、副作用与幂等能力，不接受未知动作。 */
    private static boolean toolMatches(String action, TypedPayload payload, SessionOwner owner) {
        if (!"tool.invoke".equals(action) || !"skillforge.runtime.tool.invoke-resource.v3".equals(payload.schemaId())) return false;
        try {
            var reader = new ResourceReader(payload.canonicalJson());
            var f = reader.array(0);
            if (reader.position != payload.canonicalJson().length() || f.size() != 32
                    || f.stream().anyMatch(value -> !(value instanceof String))) return false;
            if (!f.get(0).equals(owner.sessionRef().tenantId().value()) || !f.get(1).equals(owner.target().requireAgent().value())
                    || !f.get(2).equals(owner.sessionRef().sessionId()) || !f.get(3).equals(Long.toString(owner.requireUser().value()))) return false;
            var trace = new TraceEnvelope((String) f.get(7), (String) f.get(8), new InputId((String) f.get(9)),
                    f.get(10).equals("") ? Optional.empty() : Optional.of(new EventId((String) f.get(10))));
            new RunIdentity(owner, owner.target().requireAgent(), new InputId((String) f.get(5)), new RunRef(owner.sessionRef().tenantId(), (String) f.get(4)), trace, new ResultRef((String) f.get(6)), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), new InputId((String) f.get(5)).value()));
            new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.valueOf((String) f.get(11)),
                    (String) f.get(12), (String) f.get(13));
            if (!booleanText((String) f.get(24)) || !booleanText((String) f.get(26))
                    || !booleanText((String) f.get(30))) return false;
            var timeout = java.time.Duration.parse((String) f.get(25));
            if (!timeout.toString().equals(f.get(25))) return false;
            new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle((String) f.get(14)),
                    new CapabilityRef(CapabilityType.TOOL, (String) f.get(15), (String) f.get(16)),
                    (String) f.get(17), positive((String) f.get(18)),
                    new TypedPayload((String) f.get(19), (String) f.get(20)),
                    new TypedPayload((String) f.get(21), (String) f.get(22)),
                    ToolEffect.valueOf((String) f.get(23)), Boolean.parseBoolean((String) f.get(24)), timeout,
                    Boolean.parseBoolean((String) f.get(26)),
                    ToolPortal.ToolConfirmationPolicy.valueOf((String) f.get(27)),
                    unpacked((String) f.get(28)), unpacked((String) f.get(29)),
                    Boolean.parseBoolean((String) f.get(30)));
            new IdempotencyKey((String) f.get(31));
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    private static boolean booleanText(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    private static Set<String> unpacked(String packed) {
        var result = new LinkedHashSet<String>();
        int index = 0;
        while (index < packed.length()) {
            int colon = packed.indexOf(':', index);
            if (colon <= index) throw new IllegalArgumentException("tool_resource_set");
            int length = Integer.parseInt(packed.substring(index, colon));
            int end = Math.addExact(colon + 1, length);
            if (length <= 0 || end > packed.length()) throw new IllegalArgumentException("tool_resource_set");
            String value = packed.substring(colon + 1, end);
            if (!result.add(value)) throw new IllegalArgumentException("tool_resource_set");
            index = end;
        }
        return Set.copyOf(result);
    }

    /** development 同样只接受五个明确Workflow schema，不因拥有write scope自动允许读查询。 */
    private static boolean workflowMatches(String action, TypedPayload payload, SessionOwner owner) {
        int count = switch (action) {
            case "workflow.dispatch", "workflow.cancel" -> 27;
            case "workflow.resume" -> 29;
            case "workflow.recover" -> 24;
            case "workflow.query", "workflow.execute" -> 5;
            default -> 0;
        };
        if (count == 0 || !("skillforge.runtime." + action + "-resource.v1").equals(payload.schemaId())) return false;
        try {
            var parser = new ResourceReader(payload.canonicalJson()); var values = parser.array(0);
            if (parser.position != payload.canonicalJson().length() || values.size() != count || values.stream().anyMatch(value -> !(value instanceof String))) return false;
            var f = values.stream().map(String.class::cast).toList();
            if (!f.get(0).equals(owner.sessionRef().tenantId().value()) || !f.get(1).equals(owner.target().requireAgent().value())
                    || !f.get(2).equals(owner.sessionRef().sessionId()) || positive(f.get(3)) != owner.requireUser().value()) return false;
            var tenant = owner.sessionRef().tenantId();
            if (count == 5) { new com.skillforge.runtime.api.ExecutionRef(tenant, f.get(4)); return true; }
            var run = new RunIdentity(owner, owner.target().requireAgent(), new InputId(f.get(5)), new RunRef(tenant, f.get(4)), new TraceEnvelope(f.get(7), f.get(8), new InputId(f.get(9)), f.get(10).isEmpty() ? Optional.empty() : Optional.of(new EventId(f.get(10)))), new ResultRef(f.get(6)), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), new InputId(f.get(5)).value()));
            new CapabilitySnapshotRef(owner.sessionRef(), run.causationInputId(), f.get(11), positive(f.get(12)));
            var timeout = java.time.Duration.parse(f.get(18)); if (!timeout.toString().equals(f.get(18))) return false;
            new WorkflowPortal.FrozenWorkflowTarget(new WorkflowPortal.WorkflowHandle(f.get(13)), f.get(16), positive(f.get(17)),
                    new CapabilityRef(CapabilityType.WORKFLOW, f.get(14), f.get(15)), timeout, 1L);
            new com.skillforge.runtime.api.ExecutionRef(tenant, f.get(19)); new ActionRef(tenant, f.get(20)); requireText(f.get(21)); new ResultRef(f.get(22));
            switch (action) {
                case "workflow.dispatch" -> { new TypedPayload(f.get(23), f.get(24)); if (!f.get(25).matches("[0-9a-f]{64}")) return false; new IdempotencyKey(f.get(26)); }
                case "workflow.resume" -> { requireText(f.get(23)); requireText(f.get(24)); new TypedPayload(f.get(25), f.get(26)); positive(f.get(27)); new IdempotencyKey(f.get(28)); }
                case "workflow.cancel" -> { requireText(f.get(23)); positive(f.get(24)); requireText(f.get(25)); new IdempotencyKey(f.get(26)); }
                case "workflow.recover" -> requireText(f.get(23));
                default -> { return false; }
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    private static long positive(String text) {
        long number = Long.parseLong(text);
        if (number <= 0 || !Long.toString(number).equals(text)) throw new IllegalArgumentException("invalid_resource_number");
        return number;
    }

    /** 开发适配器接受同一43字段Knowledge资源；不复用JDBC模块或扩大动作集合。 */
    private static boolean knowledgeMatches(String action,TypedPayload payload,SessionOwner owner) {
        if(!"knowledge.execute".equals(action)||!"skillforge.runtime.knowledge.execute-resource.v1".equals(payload.schemaId()))return false;
        try {
            var parser=new ResourceReader(payload.canonicalJson());var values=parser.array(0);
            if(parser.position!=payload.canonicalJson().length()||values.size()!=43||values.stream().anyMatch(value->!(value instanceof String)))return false;
            var f=values.stream().map(String.class::cast).toList();
            if(!f.get(0).equals(owner.sessionRef().tenantId().value())||!f.get(1).equals(owner.target().requireAgent().value())
                    ||!f.get(2).equals(owner.sessionRef().sessionId())||positive(f.get(3))!=owner.requireUser().value())return false;
            var tenant=owner.sessionRef().tenantId();var run=new RunIdentity(owner, owner.target().requireAgent(), new InputId(f.get(5)), new RunRef(tenant,f.get(4)), new TraceEnvelope(f.get(7),f.get(8),new InputId(f.get(9)),f.get(10).isEmpty()?Optional.empty():Optional.of(new EventId(f.get(10)))), new ResultRef(f.get(6)), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), new InputId(f.get(5)).value()));
            new CapabilitySnapshotRef(owner.sessionRef(),run.causationInputId(),f.get(11),positive(f.get(12)));
            var timeout=java.time.Duration.parse(f.get(18));if(!timeout.toString().equals(f.get(18)))return false;
            var targetOps=knowledgeOperators(f.get(20));
            new KnowledgePortal.FrozenKnowledgeTarget(new KnowledgePortal.KnowledgeHandle(f.get(13)),new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,f.get(14),f.get(15)),
                    f.get(16),positive(f.get(17)),Set.of(),targetOps,timeout);
            for(int index:List.of(19,25,26))if(!f.get(index).matches("[0-9a-f]{64}"))return false;
            requireText(f.get(21));if(com.skillforge.runtime.api.KnowledgeMode.valueOf(f.get(22))==com.skillforge.runtime.api.KnowledgeMode.NONE)return false;
            requireText(f.get(23));requireText(f.get(24));if(!targetOps.containsAll(knowledgeOperators(f.get(27))))return false;
            new com.skillforge.runtime.api.KnowledgeBudget(Math.toIntExact(positive(f.get(28))),Math.toIntExact(positive(f.get(29))),Math.toIntExact(positive(f.get(30))),Math.toIntExact(positive(f.get(31))),positive(f.get(32)));
            if(java.time.Duration.ofMillis(positive(f.get(32))).compareTo(timeout)>0)return false;
            if(!Instant.parse(f.get(33)).toString().equals(f.get(33))||!f.get(34).isEmpty()&&!Instant.parse(f.get(34)).toString().equals(f.get(34)))return false;
            if(!Set.of("true","false").contains(f.get(35)))return false;
            new EventId(f.get(36));new IdempotencyKey(f.get(37));new ActionRef(tenant,f.get(38));requireText(f.get(39));new ResultRef(f.get(40));requireText(f.get(41));requireText(f.get(42));
            return true;
        }catch(RuntimeException invalid){return false;}
    }

    private static Set<com.skillforge.runtime.api.KnowledgeOperatorType> knowledgeOperators(String value) {
        if(value.isEmpty())return Set.of();var result=java.util.Arrays.stream(value.split(",",-1)).map(com.skillforge.runtime.api.KnowledgeOperatorType::valueOf).collect(java.util.stream.Collectors.toSet());
        if(!result.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")).equals(value))throw new IllegalArgumentException("knowledge_resource_operators");return result;
    }

    private static boolean resourceMatches(String json, TrustedRegistration trusted) {
        try {
            var parser = new ResourceReader(json);
            var value = parser.array(0);
            if (parser.position != json.length() || value.size() != 17) return false;
            for (int index = 0; index < value.size(); index++) {
                if (index != 7 && index != 13 && (!(value.get(index) instanceof String text) || text.isBlank())) return false;
            }
            InboundKind.valueOf((String) value.get(6));
            if (!Instant.parse((String) value.get(16)).toString().equals(value.get(16))) return false;
            var owner = trusted.owner();
            if (!value.get(0).equals(owner.sessionRef().tenantId().value()) || !value.get(1).equals(owner.target().requireAgent().value())
                    || !value.get(2).equals(owner.sessionRef().sessionId()) || !value.get(3).equals(Long.toString(owner.requireUser().value()))
                    || !value.get(8).equals(trusted.channel()) || !value.get(9).equals(trusted.source())) return false;
            if (!(value.get(7) instanceof List<?> target) || !(value.get(13) instanceof List<?> cause)) return false;
            if (!(target.equals(List.of("new")) || target.size() == 3 && target.get(0).equals("existing")
                    && target.get(1).equals(owner.sessionRef().tenantId().value()) && target.get(2) instanceof String run && !run.isBlank())) return false;
            return cause.isEmpty() || cause.size() == 1 && cause.getFirst() instanceof String id && !id.isBlank();
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException rejected) { return false; }
    }

    /** 仅支持上述 schema 需要的数组与字符串；拒绝尾随字段、重复结构和无效 Unicode。 */
    private static final class ResourceReader {
        final String json;
        int position;
        ResourceReader(String json) { this.json = json; if (json.length() > 1_000_000) throw invalid(); }
        List<Object> array(int depth) {
            if (depth > 1) throw invalid();
            need('['); var values = new ArrayList<Object>();
            if (take(']')) return values;
            do {
                // 最大注册资源为 knowledge.execute 的43字段，各动作仍单独核对精确长度。
                if (position >= json.length() || values.size() >= 43) throw invalid();
                values.add(json.charAt(position) == '[' ? array(depth + 1) : string());
            } while (take(','));
            need(']'); return values;
        }
        String string() {
            need('"'); var text = new StringBuilder();
            while (position < json.length()) {
                char c = json.charAt(position++);
                if (c == '"') {
                    var value = text.toString();
                    for (int i = 0; i < value.length(); i++) {
                        char unit = value.charAt(i);
                        if (Character.isHighSurrogate(unit)) { if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(); }
                        else if (Character.isLowSurrogate(unit)) throw invalid();
                    }
                    return value;
                }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (position == json.length()) throw invalid();
                    c = json.charAt(position++);
                    c = switch (c) {
                        case '"', '\\' -> c;
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> {
                            if (position + 4 > json.length()) throw invalid();
                            // 与 Session canonical writer 一致：只有无短转义的控制字符使用小写 \\u00xx。
                            String hex = json.substring(position, position + 4);
                            if (!hex.matches("00[01][0-9a-f]")) throw invalid();
                            char control = (char) Integer.parseInt(hex, 16);
                            if ("\b\t\n\f\r".indexOf(control) >= 0) throw invalid();
                            position += 4; yield control;
                        }
                        default -> throw invalid();
                    };
                }
                text.append(c);
            }
            throw invalid();
        }
        boolean take(char expected) { if (position < json.length() && json.charAt(position) == expected) { position++; return true; } return false; }
        void need(char expected) { if (!take(expected)) throw invalid(); }
        static IllegalArgumentException invalid() { return new IllegalArgumentException("identity_resource_invalid"); }
    }
}
