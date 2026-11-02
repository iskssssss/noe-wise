package com.skillforge.runtime.adapter.jdbc.fixture;

import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/**
 * SDK 测试宿主拥有的身份事实；只接受显式登记的完整验证值，不读取平台 IAM 表。
 * 原验证不可变，当前权限独立撤销；借用事务只读取既有租户设置，不绑定租户或提交。
 */
public final class TestJdbcIdentityTransactions implements JdbcIdentityTransactions {
    private final Map<Key, Fact> facts = new ConcurrentHashMap<>();
    private final Map<Key, Authority> authorities = new ConcurrentHashMap<>();
    private final Map<ResultRef, Audit> audits = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private record Key(TenantId tenant, ResultRef reference) { }
    private record Fact(VerifiedIdentity identity, String channel, String source) { }
    private record Authority(Set<String> scopes, long revision, boolean active) { }
    private record Audit(VerifiedIdentity identity, String action, boolean allowed) { }

    /** 宿主测试入口明确提供已验证的全值与来源，重复引用不能覆盖任何原事实。 */
    public void register(VerifiedIdentity identity, String channel, String source) {
        Objects.requireNonNull(identity);
        if (channel == null || channel.isBlank() || source == null || source.isBlank())
            throw new IllegalArgumentException("test_identity_provenance_missing");
        var fact = new Fact(identity, channel, source);
        var key = new Key(identity.owner().sessionRef().tenantId(), identity.verificationRef());
        var previous = facts.putIfAbsent(key, fact);
        if (previous != null && !previous.equals(fact))
            throw new IllegalArgumentException("test_identity_verification_conflict");
        authorities.putIfAbsent(key, new Authority(identity.scopes(), identity.policyRevision(), true));
    }

    /** 常规存储用例仍显式登记身份，仅使用固定的测试入口来源。 */
    public void register(VerifiedIdentity identity) {
        register(identity, "fixture", "fixture");
    }

    /** 撤销只改变当前权限，历史验证值仍可用于准确恢复。 */
    public void revoke(TenantId tenant, ResultRef reference) {
        authorities.compute(new Key(tenant, reference), (key, current) -> {
            if (current == null) throw new IllegalArgumentException("test_identity_verification_missing");
            return new Authority(current.scopes(), Math.incrementExact(current.revision()), false);
        });
    }

    @Override public void configureTransaction(Configuration configuration) {
        if (!configuration.hasMapper(TransactionScope.class)) configuration.addMapper(TransactionScope.class);
    }

    @Override public Optional<VerifiedIdentity> originalVerification(SqlSession session, SessionOwner owner, ResultRef reference) {
        requireScope(session, owner);
        return Optional.ofNullable(facts.get(new Key(owner.sessionRef().tenantId(), reference))).map(Fact::identity).filter(identity -> identity.owner().equals(owner));
    }

    @Override public boolean matchesOriginalCaller(SqlSession session, SessionOwner owner, ResultRef originalReference,
            VerifiedIdentity current) {
        requireScope(session, owner);
        var original = facts.get(new Key(owner.sessionRef().tenantId(), originalReference));
        var candidate = facts.get(new Key(owner.sessionRef().tenantId(), current.verificationRef()));
        return original != null && candidate != null && current.owner().equals(owner)
                && original.identity().owner().equals(owner) && candidate.identity().equals(current)
                && original.channel().equals(candidate.channel()) && original.source().equals(candidate.source())
                && samePrincipal(original.identity().principal(), current.principal());
    }

    @Override public AuthorizationResult authorize(SqlSession session, AuthorizationRequest request) {
        requireScope(session, request.identity().owner());
        return authorize(request);
    }

    @Override public AuthorizationResult authorize(AuthorizationRequest request) {
        var key = new Key(request.identity().owner().sessionRef().tenantId(), request.identity().verificationRef());
        var fact = facts.get(key);
        var authority = authorities.get(key);
        boolean allowed = fact != null && fact.identity().equals(request.identity()) && authority != null
                && authority.active() && authority.scopes().contains(request.action());
        var audit = new ResultRef("test-identity-audit:" + sequence.incrementAndGet());
        audits.put(audit, new Audit(request.identity(), request.action(), allowed));
        long revision = authority == null ? request.identity().policyRevision() : authority.revision();
        return allowed ? new AuthorizationResult.Allowed(revision, audit)
                : new AuthorizationResult.Denied("test_identity_unauthorized", revision, audit);
    }

    @Override public IdentityResult verify(IdentityRequest request) {
        return lookup(request.claimedOwner().sessionRef().tenantId(), request.authenticationRef().value()).filter(fact -> fact.identity().owner().equals(request.claimedOwner())
                && fact.channel().equals(request.channel()) && fact.source().equals(request.source()))
                .<IdentityResult>map(fact -> new IdentityResult.Verified(fact.identity())).orElseGet(this::rejected);
    }

    @Override public IdentityResult verifyAccess(AccessIdentityRequest request) {
        return lookup(request.claimedTenantId(), request.authenticationRef().value()).filter(fact -> {
            var owner = fact.identity().owner();
            return owner.sessionRef().tenantId().equals(request.claimedTenantId()) && owner.target().equals(request.claimedTarget())
                    && owner.subject().equals(request.claimedSubject()) && fact.channel().equals(request.channel())
                    && fact.source().equals(request.source()) && request.claimedAgentId().map(agent ->
                            owner.target().kind() == com.skillforge.runtime.api.SessionTarget.Kind.AGENT
                                    && owner.target().id().equals(agent.value())).orElse(true);
        }).<IdentityResult>map(fact -> new IdentityResult.Verified(fact.identity())).orElseGet(this::rejected);
    }

    private Optional<Fact> lookup(TenantId tenant, String authentication) {
        if (!authentication.startsWith("authentication:") || authentication.length() == "authentication:".length()) return Optional.empty();
        var reference = new ResultRef(authentication.substring("authentication:".length()));
        var key = new Key(tenant, reference);
        var authority = authorities.get(key);
        return authority != null && authority.active() ? Optional.ofNullable(facts.get(key)) : Optional.empty();
    }

    private IdentityResult rejected() {
        return new IdentityResult.Rejected(new PortFailure("test_identity_unregistered", RetryMode.NONE,
                new ResultRef("test-identity-rejection:" + sequence.incrementAndGet())));
    }

    private static boolean samePrincipal(PrincipalRef original, PrincipalRef current) {
        if (original instanceof PrincipalRef.ServicePrincipal first && current instanceof PrincipalRef.ServicePrincipal second)
            return first.tenantId().equals(second.tenantId()) && first.principalId().equals(second.principalId())
                    && first.integrationId().equals(second.integrationId());
        return original.equals(current);
    }

    private static void requireScope(SqlSession session, SessionOwner owner) {
        try {
            if (session.getConnection().getAutoCommit() || !owner.sessionRef().tenantId().value()
                    .equals(session.getMapper(TransactionScope.class).tenant()))
                throw new IllegalStateException("test_identity_transaction_scope_mismatch");
        } catch (SQLException failure) { throw new IllegalStateException("test_identity_transaction_unavailable", failure); }
    }

    /** 只读借用事务的当前租户；映射在构造工厂时安装。 */
    public interface TransactionScope {
        @Select("SELECT current_setting('app.tenant_id',true)")
        @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
        String tenant();
    }
}
