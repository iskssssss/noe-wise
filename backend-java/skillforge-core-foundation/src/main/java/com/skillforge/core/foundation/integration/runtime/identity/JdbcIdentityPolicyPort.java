package com.skillforge.core.foundation.integration.runtime.identity;

import com.skillforge.runtime.adapter.jdbc.identity.AdmissionResource;
import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.identity.KnowledgePolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.ModelPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields;
import com.skillforge.runtime.adapter.jdbc.identity.SessionCapabilityPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.ToolPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * 解析受信入口签发的认证事实并读取当前 IAM；授权可参与派发所属业务事务。
 * 宿主仅向已认证入口提供 registrar；普通请求只能获得 IdentityPolicyPort。
 * 原入口方法仍独立提交安全责任，SqlSession重载与调用方共同提交或回滚；
 * 不从线程上下文或资源存在性生成身份。
 */
public final class JdbcIdentityPolicyPort implements JdbcIdentityTransactions {
    public static final long POLICY_REVISION = 11;
    private static final String ACCEPT = "runtime.accept";
    private static final String WRITE_SCOPE = "sessions.write";
    private static final int ORIGINAL_VERIFICATION_CACHE_LIMIT = 256;
    private final SqlSessionFactory sessions;
    private final Map<PrincipalRef.SystemPrincipal, Set<String>> systemGrants;
    private final Map<OriginalVerificationKey, VerifiedIdentity> originalVerifications =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<OriginalVerificationKey, VerifiedIdentity> eldest) {
                    return size() > ORIGINAL_VERIFICATION_CACHE_LIMIT;
                }
            });

    @Override public void configureTransaction(Configuration configuration) { configure(configuration); }

    @Override public boolean matchesOriginalCaller(SqlSession session, SessionOwner owner,
            ResultRef originalReference, VerifiedIdentity current) {
        return sameOriginalCaller(session, owner, originalReference, current);
    }

    /**
     * 系统主体授权由宿主登记表提供；空映射禁用系统入口。
     * 传入可并发写入的登记表时，运行期新增租户立即生效。
     */
    public JdbcIdentityPolicyPort(DataSource dataSource,
            Map<PrincipalRef.SystemPrincipal, Set<String>> systemGrants) {
        this.systemGrants = Objects.requireNonNull(systemGrants);
        var configuration = new Configuration(new Environment("runtime-identity",
                new JdbcTransactionFactory(), Objects.requireNonNull(dataSource)));
        configuration.setMapUnderscoreToCamelCase(true);
        configure(configuration);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 为调用方业务事务注册同一身份映射，不创建连接或安装第二套身份账本。
     * 调用方在开始业务事务时绑定可信租户，映射查询只核对绑定，不在内部切换租户。
     */
    public static void configure(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.hasMapper(Mapper.class)) return;
        String resource = "mapper/AgentRuntimeIdentityMapper.xml";
        try (InputStream xml = JdbcIdentityPolicyPort.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("identity_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) {
            throw new IllegalStateException("identity_initialization_failure", failure);
        }
    }

    /**
     * 仅供成功完成凭据校验的受信宿主入口调用；principal 是原认证结果，不接受请求正文替代。
     * ServicePrincipal 的版本和 integration 再与原 IAM 比较；SystemPrincipal 必须在宿主授权表。
     * 此接口不验证密码/HMAC，不能暴露成 HTTP 端点。新引用不携带或保存凭据。
     * 服务入口有效期最多一小时；系统入口由宿主显式覆盖冻结异步事实的完整责任窗口。
     * @throws TrustedAuthenticationRejectedException 当前主体或目标授权失效；在插入认证事实前拒绝
     */
    public AuthenticationRef registerTrusted(PrincipalRef principal, SessionOwner owner,
            String channel, String source, Duration validity) {
        Objects.requireNonNull(principal); Objects.requireNonNull(owner);
        if (!principal.tenantId().equals(owner.sessionRef().tenantId())
                || channel == null || channel.isBlank() || source == null || source.isBlank()
                || validity == null || validity.isNegative() || validity.isZero()
                || (principal instanceof PrincipalRef.ServicePrincipal
                    && validity.compareTo(Duration.ofHours(1)) > 0)
                || principal instanceof PrincipalRef.EndUserPrincipal) {
            throw new IllegalArgumentException("unsupported_trusted_authentication");
        }
        var fact = Fact.from(principal, owner, channel, source);
        try (var session = open()) {
            var mapper = session.getMapper(Mapper.class);
            mapper.bindTenant(fact.tenantId);
            if (currentScopes(mapper, fact) == null || !mapper.ownerAvailable(fact)) {
                throw new TrustedAuthenticationRejectedException();
            }
            fact.authenticationRef = reference("authentication");
            fact.expiresAt = mapper.now().plus(validity);
            mapper.insertAuthentication(fact);
            session.commit();
            return new AuthenticationRef(fact.authenticationRef);
        }
    }

    /** 撤销原认证引用，不改变 IAM 或既有业务事实。 */
    public void revoke(AuthenticationRef reference) {
        Objects.requireNonNull(reference);
        try (var session = open()) {
            var mapper = session.getMapper(Mapper.class);
            String tenant = mapper.resolveTenant(reference.value());
            if (tenant != null) {
                mapper.bindTenant(tenant);
                mapper.revoke(reference.value());
            }
            session.commit();
        }
    }

    @Override
    public IdentityResult verify(IdentityRequest request) {
        Objects.requireNonNull(request);
        String tenant = null;
        String reason = "identity_rejected";
        try (var session = open()) {
            var mapper = session.getMapper(Mapper.class);
            tenant = mapper.resolveTenant(request.authenticationRef().value());
            if (tenant != null) {
                mapper.bindTenant(tenant);
                var fact = mapper.authentication(request.authenticationRef().value());
                Set<String> scopes = fact == null ? null : currentScopes(mapper, fact);
                if (fact != null && fact.active && mapper.now().isBefore(fact.expiresAt)
                        && fact.owner().equals(request.claimedOwner())
                        && fact.channel.equals(request.channel()) && fact.source.equals(request.source())
                        && scopes != null && mapper.ownerAvailable(fact)) {
                    String verification = reference("verification");
                    var identity = new VerifiedIdentity(fact.principal(), fact.owner(), scopes,
                            POLICY_REVISION, new ResultRef(verification));
                    mapper.insertVerification(verification, fact.authenticationRef, tenant,
                            encodeScopes(scopes), snapshot(identity));
                    // 验证事实和验证成功审计共同独立提交，业务回滚无法撤销。
                    mapper.audit(verification, tenant, "verify", "verified", POLICY_REVISION,
                            digest(request.authenticationRef().value()), digest(request.traceId()));
                    session.commit();
                    return new IdentityResult.Verified(identity);
                }
            }
        } catch (RuntimeException failure) {
            reason = "identity_unavailable";
        }
        ResultRef audit = audit(tenant, "verify", reason,
                request.authenticationRef().value(), request.traceId());
        return new IdentityResult.Rejected(new PortFailure(reason, RetryMode.NONE, audit));
    }

    @Override
    public IdentityResult verifyAccess(AccessIdentityRequest request) {
        Objects.requireNonNull(request);
        String tenant = null;
        String reason = "identity_rejected";
        try (var session = open()) {
            var mapper = session.getMapper(Mapper.class);
            tenant = mapper.resolveTenant(request.authenticationRef().value());
            if (tenant != null) {
                mapper.bindTenant(tenant);
                var fact = mapper.authentication(request.authenticationRef().value());
                Set<String> scopes = fact == null ? null : currentScopes(mapper, fact);
                SessionOwner owner = fact == null ? null : fact.owner();
                if (fact != null && fact.active && mapper.now().isBefore(fact.expiresAt)
                        && owner.sessionRef().tenantId().equals(request.claimedTenantId())
                        && owner.target().equals(request.claimedTarget())
                        && (request.claimedAgentId().isEmpty() || mapper.agentAllowed(fact, request.claimedAgentId().orElseThrow().value()))
                        && owner.subject().equals(request.claimedSubject())
                        && fact.channel.equals(request.channel()) && fact.source.equals(request.source())
                        && scopes != null && mapper.ownerAvailable(fact)) {
                    String verification = reference("verification");
                    var identity = new VerifiedIdentity(fact.principal(), owner, scopes,
                            POLICY_REVISION, new ResultRef(verification));
                    mapper.insertVerification(verification, fact.authenticationRef, tenant,
                            encodeScopes(scopes), snapshot(identity));
                    mapper.audit(verification, tenant, "verify", "verified", POLICY_REVISION,
                            digest(request.authenticationRef().value()), digest(request.traceId()));
                    session.commit();
                    return new IdentityResult.Verified(identity);
                }
            }
        } catch (RuntimeException failure) {
            reason = "identity_unavailable";
        }
        ResultRef audit = audit(tenant, "verify", reason,
                request.authenticationRef().value(), request.traceId());
        return new IdentityResult.Rejected(new PortFailure(reason, RetryMode.NONE, audit));
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request);
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(Mapper.class);
            mapper.bindTenant(request.identity().owner().sessionRef().tenantId().value());
            var decision = authorize(session, request);
            session.commit();
            return decision;
        } catch (RuntimeException failure) {
            // 独立入口保持原安全责任：原事务失败后另存一次失败审计，不把未提交判定当Allowed。
            var audit = audit(null, "authorize", "policy_unavailable", request.identity().verificationRef().value(), request.traceId());
            return new AuthorizationResult.Failed(new PortFailure("policy_unavailable", RetryMode.NONE, audit));
        }
    }

    /**
     * 使用已加载的入口身份，在派发所属SqlSession读取当前IAM并保存一次业务判定审计。
     *
     * <p>不创建或提交事务，不绑定或切换租户。已绑定租户不匹配、原验证全值漂移或当前撤权
     * 均返回Denied。Allowed及auditRef仅在调用方业务事务成功提交后生效；技术故障抛出并
     * 保留原因链，由调用方回滚派发、预算与审计，不能转换为放行或另开审计事务。</p>
     */
    public AuthorizationResult authorize(SqlSession session, AuthorizationRequest request) {
        return authorize(session, request, Optional.empty());
    }

    /** 只读取已提交的准确响应；当前身份及冻结授权仍复验，不重新取得群前台执行资格。 */
    public AuthorizationResult authorizeResponseReplay(SqlSession session, AuthorizationRequest request, InputId input) {
        return authorize(session, request, Optional.of(Objects.requireNonNull(input)));
    }

    private AuthorizationResult authorize(SqlSession session, AuthorizationRequest request, Optional<InputId> responseInput) {
        Objects.requireNonNull(session, "session"); Objects.requireNonNull(request, "request");
        try {
            var mapper = session.getMapper(Mapper.class);
            var fact = mapper.authorizationCurrent(request.identity().owner().sessionRef().tenantId().value(),
                    request.identity().verificationRef().value(), request.identity().owner().target().kind().name());
            boolean exact = fact != null && snapshot(request.identity()).equals(fact.identitySnapshot);
            boolean responseExists = responseInput.isEmpty() || exact && "session.execute".equals(request.action())
                    && SessionPolicyResource.controlTarget(request.action(), request.resource())
                            .filter(target -> mapper.acceptedResponseExists(fact.tenantId, target.run().runId(), responseInput.orElseThrow().value()))
                            .isPresent();
            boolean allowed = exact && responseExists && evaluate(mapper, fact, request, responseInput.isPresent());
            String reason = allowed ? "allowed" : "authorization_denied";
            String audit = reference("security-audit");
            if (mapper.audit(audit, exact ? fact.tenantId : null, "authorize", reason, POLICY_REVISION,
                    digest(request.identity().verificationRef().value()), digest(request.traceId())) != 1)
                throw new IllegalStateException("security_audit_not_saved");
            var reference = new ResultRef(audit);
            return allowed ? new AuthorizationResult.Allowed(POLICY_REVISION, reference)
                    : new AuthorizationResult.Denied(reason, POLICY_REVISION, reference);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("authorization_transaction_failed", failure);
        }
    }

    /**
     * 仅分类已持久群trigger准备期间的凭据轮换；不签发Allowed、不写或替换原身份。
     * 原验证、固定域、当前用途scope及完整资源资格均须匹配，仅当前凭据版本变化才建议下轮重新认证。
     */
    public boolean groupCredentialRotated(SqlSession session,AuthorizationRequest request) {
        var fact=session.getMapper(Mapper.class).authorizationCurrent(request.identity().owner().sessionRef().tenantId().value(),
                request.identity().verificationRef().value(),request.identity().owner().target().kind().name());
        if(fact==null || !snapshot(request.identity()).equals(fact.identitySnapshot) || !"GROUP".equals(fact.targetKind)
                || !"SERVICE".equals(fact.principalKind) || fact.currentServiceCredentialVersion==null
                || fact.credentialVersion==fact.currentServiceCredentialVersion)return false;
        fact.credentialVersion=fact.currentServiceCredentialVersion;
        return evaluate(session.getMapper(Mapper.class),fact,request);
    }

    /**
     * 内部执行监测只读取当前 IAM，复用正式授权判定；不签发 Allowed、不写审计。
     * 调用方仍须独立检查 Run、租约和责任期限，任何新派发继续正式授权。
     */
    public boolean executionActive(VerifiedIdentity actor, RunIdentity run) {
        Objects.requireNonNull(actor); Objects.requireNonNull(run);
        var request = new AuthorizationRequest(actor, "session.execute",
                SessionPolicyResource.run(run, Optional.empty()), run.trace().traceId(), run.trace().spanId());
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(Mapper.class);
            mapper.bindTenant(run.runRef().tenantId().value());
            var fact = mapper.authorizationCurrent(run.runRef().tenantId().value(), actor.verificationRef().value(),
                    actor.owner().target().kind().name());
            boolean active = fact != null && snapshot(actor).equals(fact.identitySnapshot)
                    && evaluate(mapper, fact, request);
            // 多条 IAM 查询共用短事务，明确结束只读快照。
            session.rollback(true);
            return active;
        }
    }

    /** 正式授权与内部停止监测使用同一当前事实和规则，不在此组装或持久化审计。 */
    private boolean evaluate(Mapper mapper, Fact fact, AuthorizationRequest request) {
        return evaluate(mapper, fact, request, false);
    }

    private boolean evaluate(Mapper mapper, Fact fact, AuthorizationRequest request, boolean responseReplay) {
        Set<String> scopes = currentAuthorizationScopes(fact);
        var resource = AdmissionResource.parse(request.resource());
        boolean resourceAllowed = ACCEPT.equals(request.action()) && resource.isPresent()
                && resource.get().owner().equals(fact.owner())
                && mapper.agentAdmissionAllowed(fact, resource.get().agentId().value())
                && resource.get().channel().equals(fact.channel)
                && resource.get().source().equals(fact.source);
        if ("callback.register".equals(request.action()) || "callback.status".equals(request.action())) {
            resourceAllowed = CallbackPolicyResource.owner(request.action(), request.resource())
                    .filter(fact.owner()::equals).isPresent();
        }
        if ("model.resolve".equals(request.action()) || "model.generate".equals(request.action()) || "model.stream".equals(request.action())) {
            resourceAllowed = ModelPolicyResource.owner(request.action(), request.resource())
                    .filter(fact.owner()::equals).isPresent();
        }
        if ("tool.invoke".equals(request.action())) {
            resourceAllowed = ToolPolicyResource.owner(request.action(), request.resource())
                    .filter(fact.owner()::equals).isPresent();
        }
        if ("knowledge.execute".equals(request.action())) {
            resourceAllowed = KnowledgePolicyResource.owner(request.action(), request.resource()).filter(fact.owner()::equals).isPresent();
        }
        if (Set.of("workflow.dispatch", "workflow.resume", "workflow.recover", "workflow.cancel", "workflow.query", "workflow.execute").contains(request.action())) {
            resourceAllowed = WorkflowPolicyResource.owner(request.action(), request.resource()).filter(fact.owner()::equals).isPresent();
        }
        if (Set.of("session.claim", "session.execute", "session.commit", "session.cancel").contains(request.action())) {
            resourceAllowed = SessionPolicyResource.owner(request.action(), request.resource()).filter(fact.owner()::equals).isPresent();
        }
        if (SessionCapabilityPolicyResource.ACTION.equals(request.action())) {
            resourceAllowed = SessionCapabilityPolicyResource.owner(request.action(),request.resource()).filter(fact.owner()::equals).isPresent();
        }
        if (AgentRuntimeAccessResource.readAction(request.action()) || "runtime.cancel".equals(request.action()) || "runtime.session.human-assistance".equals(request.action()) || "runtime.session.close".equals(request.action()) || "runtime.plan.select".equals(request.action()) || "runtime.run.steer".equals(request.action())) {
            resourceAllowed = AgentRuntimeAccessResource.owner(request.action(), request.resource())
                    .filter(fact.owner()::equals).isPresent();
        }
        // Session/模型/工具的实际执行读取精确 Run 绑定，避免 Team 的当前版本
        // 或系统责任主体绕过被冻结 actor 所属应用的实时授权。
        var execution = SessionPolicyResource.execution(request.action(),request.resource())
                .or(() -> CallbackPolicyResource.execution(request.action(),request.resource()))
                .or(() -> ModelPolicyResource.execution(request.action(),request.resource()))
                .or(() -> ToolPolicyResource.execution(request.action(),request.resource()))
                .or(() -> KnowledgePolicyResource.executionTarget(request.action(),request.resource()))
                .or(() -> WorkflowPolicyResource.executionTarget(request.action(),request.resource()));
        boolean actorAvailable = execution.isEmpty() || mapper.boundRunActorAvailable(fact,
                execution.orElseThrow().run().runId(),execution.orElseThrow().agent().value(),!responseReplay);
        String required=AgentRuntimeAccessResource.readAction(request.action()) || "workflow.query".equals(request.action()) ? "sessions.read" : WRITE_SCOPE;
        if("GROUP".equals(fact.targetKind) && "SERVICE".equals(fact.principalKind)) {
            required=AgentRuntimeAccessResource.readAction(request.action())?"groups.runs.read"
                    :fact.source.startsWith("group-respond:")?"groups.runs.respond"
                    :fact.source.startsWith("group-control:")?"groups.runs.control":"groups.triggers.write";
        }
        boolean operatorAvailable=!"GROUP".equals(fact.targetKind)||!fact.source.startsWith("group-control:")
                // steer采纳后仍由同一操作者驱动；模型/工具等完整执行资源也指向原Run。
                || execution.or(() -> SessionPolicyResource.controlTarget(request.action(),request.resource()))
                    .map(target->mapper.groupControllerAvailable(fact,target.run().runId())).orElse(false);
        return fact.active && fact.currentTime.isBefore(fact.expiresAt)
                && operatorAvailable && scopes != null && scopes.contains(required)
                && resourceAllowed
                && fact.ownerAvailable && fact.sessionScopeAllowed && actorAvailable;
    }

    /** 读取并反校验原验证全值；历史认证可过期，此方法不执行授权。 */
    public Optional<VerifiedIdentity> originalVerification(SessionOwner owner, ResultRef reference) {
        Objects.requireNonNull(owner); Objects.requireNonNull(reference);
        var key = new OriginalVerificationKey(owner, reference);
        var cached = originalVerifications.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<VerifiedIdentity> identity;
        try (var session = open()) {
            var mapper = session.getMapper(Mapper.class); mapper.bindTenant(owner.sessionRef().tenantId().value());
            identity = originalVerification(session, owner, reference);
        } catch (RuntimeException failure) { throw new IllegalStateException("identity_origin_read_failure", failure); }
        identity.ifPresent(value -> originalVerifications.put(key, value));
        return identity;
    }

    /**
     * 首次加载时在调用方已绑定租户的SqlSession读取原验证身份；不授权、不重绑、不提交。
     * 此重载不写跨事务缓存，避免把调用方尚未提交的验证行变成全局可信事实。
     */
    public Optional<VerifiedIdentity> originalVerification(SqlSession session, SessionOwner owner, ResultRef reference) {
        return readOriginalVerification(session, owner, reference);
    }

    /** 借用已绑定租户的原事务解析不可变验证事实；当前IAM仍由所属业务操作另行核验。 */
    public static Optional<VerifiedIdentity> readOriginalVerification(SqlSession session, SessionOwner owner, ResultRef reference) {
        Objects.requireNonNull(session, "session"); Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(reference, "reference");
        try {
            return restoreOriginalVerification(session.getMapper(Mapper.class).verification(reference.value()), owner, reference);
        } catch (RuntimeException failure) { throw new IllegalStateException("identity_origin_read_failure", failure); }
    }

    /**
     * 比较一次已保存输入与当前已验证调用者的稳定身份；不授予当前业务权限。
     * 认证版本、验证引用、scope和策略版本可以更新，租户、主体、owner及可信入口来源不能替换。
     * 群控制来源包含操作员ID，因此同一集成服务不能替另一操作员重投原输入。
     */
    public static boolean sameOriginalCaller(SqlSession session, SessionOwner owner, ResultRef originalReference,
            VerifiedIdentity current) {
        Objects.requireNonNull(session, "session"); Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(originalReference, "originalReference"); Objects.requireNonNull(current, "current");
        if (!owner.equals(current.owner())) return false;
        try {
            var mapper = session.getMapper(Mapper.class);
            var originalFact = mapper.verification(originalReference.value());
            var currentFact = mapper.verification(current.verificationRef().value());
            var original = restoreOriginalVerification(originalFact, owner, originalReference);
            var verifiedCurrent = restoreOriginalVerification(currentFact, owner, current.verificationRef());
            return original.isPresent() && verifiedCurrent.filter(current::equals).isPresent()
                    && samePrincipal(original.orElseThrow().principal(), current.principal())
                    && Objects.equals(originalFact.channel, currentFact.channel)
                    && Objects.equals(originalFact.source, currentFact.source);
        } catch (RuntimeException failure) { throw new IllegalStateException("identity_origin_read_failure", failure); }
    }

    private static Optional<VerifiedIdentity> restoreOriginalVerification(Fact fact, SessionOwner owner, ResultRef reference) {
        if (fact == null || !fact.owner().equals(owner)) return Optional.empty();
        var parts = fact.identitySnapshot.split("\\|", -1);
        if (parts.length != 10 || !"identity-v2".equals(parts[0])) throw new IllegalStateException("identity_origin_snapshot_invalid");
        Set<String> scopes = parts[7].isEmpty() ? Set.of() : java.util.Arrays.stream(parts[7].split("\\.", -1))
                .map(value -> new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)).collect(Collectors.toUnmodifiableSet());
        var identity = new VerifiedIdentity(fact.principal(), owner, scopes, Long.parseLong(parts[8]), reference);
        if (!snapshot(identity).equals(fact.identitySnapshot)) throw new IllegalStateException("identity_origin_snapshot_invalid");
        return Optional.of(identity);
    }

    private static boolean samePrincipal(PrincipalRef original, PrincipalRef current) {
        return switch (original) {
            case PrincipalRef.EndUserPrincipal user -> current instanceof PrincipalRef.EndUserPrincipal now
                    && user.tenantId().equals(now.tenantId()) && user.endUserId().equals(now.endUserId());
            case PrincipalRef.SystemPrincipal system -> current instanceof PrincipalRef.SystemPrincipal now
                    && system.tenantId().equals(now.tenantId()) && system.principalId().equals(now.principalId());
            case PrincipalRef.ServicePrincipal service -> current instanceof PrincipalRef.ServicePrincipal now
                    && service.tenantId().equals(now.tenantId()) && service.principalId().equals(now.principalId())
                    && service.integrationId().equals(now.integrationId());
        };
    }

    private Set<String> currentScopes(Mapper mapper, Fact fact) {
        if ("SYSTEM".equals(fact.principalKind)) {
            return systemGrants.get(new PrincipalRef.SystemPrincipal(new TenantId(fact.tenantId), fact.principalId));
        }
        if (!"SERVICE".equals(fact.principalKind) || !mapper.serviceCurrent(fact)) return null;
        return Set.copyOf(mapper.serviceScopes(fact));
    }

    private Set<String> currentAuthorizationScopes(Fact fact) {
        if ("SYSTEM".equals(fact.principalKind)) {
            return systemGrants.get(new PrincipalRef.SystemPrincipal(new TenantId(fact.tenantId), fact.principalId));
        }
        if (!"SERVICE".equals(fact.principalKind)
                || !"active".equals(fact.currentServiceStatus)
                || !Objects.equals(fact.integrationId, fact.currentServiceIntegrationId)
                || fact.currentServiceCredentialVersion == null
                || fact.credentialVersion != fact.currentServiceCredentialVersion) {
            return null;
        }
        if (fact.currentServiceScopes == null || fact.currentServiceScopes.isEmpty()) {
            return Set.of();
        }
        return java.util.Arrays.stream(fact.currentServiceScopes.split("\\.", -1))
                .map(value -> new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8))
                .collect(Collectors.toUnmodifiableSet());
    }

    private ResultRef audit(String tenant, String operation, String result, String subject, String trace) {
        String reference = reference("security-audit");
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(Mapper.class);
            if (tenant != null) mapper.bindTenant(tenant);
            mapper.audit(reference, tenant, operation, result, POLICY_REVISION, digest(subject), digest(trace));
            session.commit();
            return new ResultRef(reference);
        } catch (RuntimeException failure) {
            // 无持久审计事实时不可返回伪造的 auditRef，更不能放行。
            throw new IllegalStateException("security_audit_unavailable", failure);
        }
    }

    private SqlSession open() { return sessions.openSession(TransactionIsolationLevel.REPEATABLE_READ); }
    private static String reference(String kind) { return kind + ":" + UUID.randomUUID(); }
    private static String encodeScopes(Set<String> scopes) {
        return scopes.stream().sorted().map(JdbcIdentityPolicyPort::encode).collect(Collectors.joining("."));
    }
    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String snapshot(VerifiedIdentity identity) {
        var owner = identity.owner();
        String principal = switch (identity.principal()) {
            case PrincipalRef.ServicePrincipal value -> "service:" + encode(value.principalId()) + ":"
                    + encode(value.integrationId()) + ":" + value.credentialVersion();
            case PrincipalRef.SystemPrincipal value -> "system:" + encode(value.principalId());
            case PrincipalRef.EndUserPrincipal value -> "end-user:" + value.endUserId().value();
        };
        return String.join("|", "identity-v2", principal, encode(owner.sessionRef().tenantId().value()),
                owner.target().kind().name(), encode(owner.target().id()), encode(owner.sessionRef().sessionId()),
                owner.target().kind()==com.skillforge.runtime.api.SessionTarget.Kind.GROUP?"group:"+encode(owner.target().id()):OwnerPolicyFields.subject(owner), encodeScopes(identity.scopes()),
                Long.toString(identity.policyRevision()), encode(identity.verificationRef().value()));
    }
    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("runtime-security-v1:" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("security_digest_unavailable", failure);
        }
    }

    private record OriginalVerificationKey(SessionOwner owner, ResultRef reference) {
        private OriginalVerificationKey {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(reference);
        }
    }

    /** MyBatis 私有持久化映射，不属于 Runtime 公共 API。 */
    public static final class Fact {
        public String authenticationRef;
        public String tenantId;
        public String principalKind;
        public String principalId;
        public String integrationId;
        public long credentialVersion;
        public String targetKind;
        public String targetId;
        public String sessionId;
        public Long endUserId;
        public String channel;
        public String source;
        public Instant expiresAt;
        public boolean active;
        public String identitySnapshot;
        public Instant currentTime;
        public String currentServiceStatus;
        public String currentServiceIntegrationId;
        public Long currentServiceCredentialVersion;
        public String currentServiceScopes;
        public boolean ownerAvailable;
        public boolean sessionScopeAllowed;

        static Fact from(PrincipalRef principal, SessionOwner owner, String channel, String source) {
            var fact = new Fact();
            fact.tenantId = principal.tenantId().value();
            fact.targetKind = owner.target().kind().name();
            fact.targetId = owner.target().id();
            fact.sessionId = owner.sessionRef().sessionId();
            fact.endUserId = owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User user ? user.endUserId().value() : null;
            fact.channel = channel; fact.source = source; fact.active = true;
            switch (principal) {
                case PrincipalRef.ServicePrincipal value -> {
                    fact.principalKind = "SERVICE"; fact.principalId = value.principalId();
                    fact.integrationId = value.integrationId(); fact.credentialVersion = value.credentialVersion();
                }
                case PrincipalRef.SystemPrincipal value -> {
                    fact.principalKind = "SYSTEM"; fact.principalId = value.principalId();
                }
                default -> throw new IllegalArgumentException("unsupported_principal");
            }
            return fact;
        }
        SessionOwner owner() {
            return OwnerPolicyFields.owner(new TenantId(tenantId),sessionId,targetKind,targetId,
                    "GROUP".equals(targetKind)?"group:"+targetId:Long.toString(Objects.requireNonNull(endUserId)));
        }
        PrincipalRef principal() {
            return switch (principalKind) {
                case "SERVICE" -> new PrincipalRef.ServicePrincipal(new TenantId(tenantId),
                        principalId, integrationId, credentialVersion);
                case "SYSTEM" -> new PrincipalRef.SystemPrincipal(new TenantId(tenantId), principalId);
                default -> throw new IllegalStateException("unsupported_persisted_principal");
            };
        }
    }

    public interface Mapper {
        String bindTenant(@Param("tenant") String tenant);
        Instant now();
        String resolveTenant(@Param("reference") String reference);
        Fact authentication(@Param("reference") String reference);
        Fact verification(@Param("reference") String reference);
        Fact authorizationCurrent(@Param("tenant") String tenant,
                @Param("reference") String reference, @Param("targetKind") String targetKind);
        boolean serviceCurrent(Fact fact);
        List<String> serviceScopes(Fact fact);
        boolean ownerAvailable(Fact fact);
        boolean agentAllowed(@Param("fact") Fact fact, @Param("agentId") String agentId);
        boolean agentAdmissionAllowed(@Param("fact") Fact fact, @Param("agentId") String agentId);
        boolean groupControllerAvailable(@Param("fact") Fact fact,@Param("runId") String runId);
        boolean boundRunActorAvailable(@Param("fact") Fact fact,@Param("runId") String runId,@Param("agentId") String agentId,
                @Param("requireActiveSlot") boolean requireActiveSlot);
        boolean acceptedResponseExists(@Param("tenant") String tenant,@Param("runId") String runId,@Param("input") String input);
        boolean sessionScopeAllowed(Fact fact);
        int insertAuthentication(Fact fact);
        int revoke(@Param("reference") String reference);
        int insertVerification(@Param("reference") String reference, @Param("authentication") String authentication,
                @Param("tenant") String tenant, @Param("scopes") String scopes, @Param("snapshot") String snapshot);
        int audit(@Param("reference") String reference, @Param("tenant") String tenant,
                @Param("operation") String operation, @Param("result") String result,
                @Param("revision") long revision, @Param("subjectDigest") String subjectDigest,
                @Param("traceDigest") String traceDigest);
    }
}
