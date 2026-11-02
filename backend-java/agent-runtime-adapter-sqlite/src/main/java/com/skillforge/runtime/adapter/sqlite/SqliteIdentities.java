package com.skillforge.runtime.adapter.sqlite;

import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/** 保存桌面可信入口签发的个人身份，不能伪装为机构 IAM 或代办服务身份。 */
final class SqliteIdentities {
    public static final class Row {
        public String tenantId, reference, sessionId, targetKind, targetId;
        public long endUserId, policyRevision;
    }
    interface Mapper {
        int insert(Row row);
        Row find(@Param("tenant") String tenant, @Param("reference") String reference);
        int insertScope(@Param("tenant") String tenant, @Param("reference") String reference, @Param("scope") String scope);
        List<String> scopes(@Param("tenant") String tenant, @Param("reference") String reference);
    }
    private SqliteIdentities() { }
    static void configure(Configuration configuration) { SqliteDatabase.load(configuration, "mapper/SqliteIdentitiesMapper.xml"); }
    static void save(SqlSession session, VerifiedIdentity identity) {
        if (!(identity.owner().subject() instanceof SessionSubject.User user)
                || !(identity.principal() instanceof PrincipalRef.EndUserPrincipal principal)
                || !user.endUserId().equals(principal.endUserId())) throw new ExecutionStore.Rejection("desktop_identity_unsupported");
        var mapper = session.getMapper(Mapper.class);
        var row = new Row(); row.tenantId = identity.owner().sessionRef().tenantId().value(); row.reference = identity.verificationRef().value();
        row.sessionId = identity.owner().sessionRef().sessionId(); row.targetKind = identity.owner().target().kind().name();
        row.targetId = identity.owner().target().id(); row.endUserId = user.endUserId().value(); row.policyRevision = identity.policyRevision();
        if (mapper.insert(row) == 1) for (String scope : identity.scopes()) mapper.insertScope(row.tenantId, row.reference, scope);
        if (!readOriginalVerification(session, identity.owner(), identity.verificationRef()).equals(Optional.of(identity)))
            throw new ExecutionStore.Rejection("desktop_identity_conflict");
    }
    static Optional<VerifiedIdentity> readOriginalVerification(SqlSession session, SessionOwner owner, ResultRef reference) {
        var mapper = session.getMapper(Mapper.class);
        var row = mapper.find(owner.sessionRef().tenantId().value(), reference.value());
        if (row == null) return Optional.empty();
        var tenant = new TenantId(row.tenantId); var user = new EndUserId(row.endUserId);
        var savedOwner = new SessionOwner(new SessionRef(tenant, row.sessionId), SessionSubject.user(user), new SessionTarget(SessionTarget.Kind.valueOf(row.targetKind), row.targetId));
        if (!savedOwner.equals(owner)) return Optional.empty();
        return Optional.of(new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, user), savedOwner,
                Set.copyOf(mapper.scopes(row.tenantId, reference.value())), row.policyRevision, reference));
    }
    static boolean sameOriginalCaller(SqlSession session, SessionOwner owner, ResultRef reference, VerifiedIdentity actor) {
        return readOriginalVerification(session, owner, reference).map(saved -> saved.principal().equals(actor.principal()) && saved.owner().equals(actor.owner())).orElse(false);
    }
}
