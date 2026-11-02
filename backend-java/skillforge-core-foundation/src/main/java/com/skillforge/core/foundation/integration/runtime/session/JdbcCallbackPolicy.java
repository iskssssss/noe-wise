package com.skillforge.core.foundation.integration.runtime.session;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.SourceMessageRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.core.foundation.integration.runtime.identity.CallbackPolicyResource;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionOwner;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Callback 当前控制面授权与原 Run/服务凭据归属核验；不签发凭据，不推进 Execution。 */
public final class JdbcCallbackPolicy {
    private final IdentityPolicyPort identities;
    private final ExecutionLedgerPort executions;
    private final SqlSessionFactory sessions;

    public JdbcCallbackPolicy(DataSource dataSource, IdentityPolicyPort identities,
            ExecutionLedgerPort executions) {
        this.identities = Objects.requireNonNull(identities);
        this.executions = Objects.requireNonNull(executions);
        var configuration = new Configuration(new Environment("callback-policy",
                new JdbcTransactionFactory(), Objects.requireNonNull(dataSource)));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/AgentRuntimeCallbackPolicyMapper.xml")) {
            try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (xml == null) throw new IllegalStateException("callback_policy_mapper_missing");
                new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (Exception failure) { throw new IllegalStateException("callback_policy_initialization_failure", failure); }
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 本次 verified snapshot/IAM 先授权，再交叉核验真实原 Run、凭据主人及精确挂起 Execution。 */
    public boolean authorizeRegister(RegisterCallback command) {
        Objects.requireNonNull(command);
        var draft = command.registration();
        if (!allowed(identities.authorize(new IdentityPolicyPort.AuthorizationRequest(command.requester(),
                "callback.register", CallbackPolicyResource.registration(command),
                command.trace().traceId(), command.trace().spanId())))) return false;
        var key = CallbackPolicyResource.credentialPrincipal(draft.signingCredentialRef());
        if (key.isEmpty()) return false;
        try (var session = sessions.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            var mapper = session.getMapper(CallbackPolicyMapper.class);
            mapper.bindTenant(draft.callbackRef().tenantId().value());
            if (command.owningRun().isPresent()) {
                var expected = command.owningRun().orElseThrow();
                var row = mapper.findRun(expected.runRef().tenantId().value(), expected.runRef().runId());
                if (row == null || !row.identity().equals(expected)
                        || !expected.causationIdentityRef().equals(command.causationIdentityRef())) return false;
            } else if (command.requester().principal() instanceof PrincipalRef.SystemPrincipal
                    || !command.causationIdentityRef().equals(command.requester().verificationRef())) return false;

            PrincipalRef.ServicePrincipal credentialOwner;
            if (command.requester().principal() instanceof PrincipalRef.ServicePrincipal service) {
                credentialOwner = service;
            } else if (command.requester().principal() instanceof PrincipalRef.SystemPrincipal) {
                // System 不能挑选任意同租户密钥，只能延续真实 owning Run 原服务认证的版本化凭据。
                var origin = mapper.originalService(draft.owner().sessionRef().tenantId().value(),
                        command.causationIdentityRef().value(), draft.owner());
                if (origin == null) return false;
                credentialOwner = new PrincipalRef.ServicePrincipal(new TenantId(origin.tenantId),
                        origin.principalId, origin.integrationId, origin.credentialVersion);
            } else return false;
            if (!key.orElseThrow().tenantId().equals(credentialOwner.tenantId())
                    || !key.orElseThrow().principalId().equals(credentialOwner.principalId())
                    || key.orElseThrow().credentialVersion() != credentialOwner.credentialVersion()
                    || !mapper.serviceCurrent(credentialOwner)) return false;
            if (draft.target() instanceof CallbackTarget.ExecutionTarget target) {
                var execution = executions.find(new ExecutionLedgerPort.ExecutionLookup(command.requester(), target.executionRef()));
                if (execution.isEmpty()) return false;
                var record = execution.orElseThrow();
                if (!record.executionRef().equals(target.executionRef())
                        || !record.owner().equals(command.owningRun().orElseThrow())
                        || !record.owner().runRef().equals(target.owningRunRef())
                        || !record.state().isWaiting()
                        || record.executionRevision() != target.expectedExecutionRevision()
                        || record.waiting().isEmpty()
                        || !record.waiting().orElseThrow().actionKey().equals(target.actionKey())) return false;
            }
            session.commit();
            return true;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("callback_policy_read_failure", failure);
        }
    }

    /** 状态控制只使用当前 requester；历史原因果或已结束的 Execution 不恢复控制面权限。 */
    public boolean authorizeStatus(CallbackRegistration registration, ChangeCallbackStatus command) {
        Objects.requireNonNull(registration); Objects.requireNonNull(command);
        if (!registration.draft().callbackRef().equals(command.callbackRef())
                || !registration.draft().owner().equals(command.requester().owner())) return false;
        return allowed(identities.authorize(new IdentityPolicyPort.AuthorizationRequest(command.requester(),
                "callback.status", CallbackPolicyResource.status(registration, command),
                command.trace().traceId(), command.trace().spanId())));
    }

    private static boolean allowed(IdentityPolicyPort.AuthorizationResult result) {
        if (result instanceof IdentityPolicyPort.AuthorizationResult.Failed) throw new IllegalStateException("callback_policy_unavailable");
        return result instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
    }
}

interface CallbackPolicyMapper {
    String bindTenant(@Param("tenant") String tenant);
    java.time.Instant now();
    CallbackRunOwnerRow findRun(@Param("tenantId") String tenantId, @Param("runId") String runId);
    CallbackServiceOrigin originalService(@Param("tenant") String tenant, @Param("reference") String reference,
            @Param("owner") SessionOwner owner);
    boolean serviceCurrent(@Param("principal") PrincipalRef.ServicePrincipal principal);
}

/** callback只核原typed Run身份闭包，不反序列化旧Run记录或加载执行context。 */
final class CallbackRunOwnerRow {
    public String tenantId,targetKind,targetId,agentId,sessionId,runId,inputId,traceId,spanId,rootInputId,causedByEventId,identityRef;
    public long endUserId;
    public String requesterKind, requesterSystemPrincipalId, sourceSessionId, sourceMessageId, originRef;
    public Long requesterEndUserId;
    RunIdentity identity() {
        var tenant = new TenantId(tenantId);
        var owner = SessionOwner.user(new SessionRef(tenant,sessionId),new SessionTarget(SessionTarget.Kind.valueOf(targetKind),targetId),new EndUserId(endUserId));
        RunRequester requester = switch (requesterKind) {
            case "END_USER" -> new RunRequester.EndUser(new EndUserId(requesterEndUserId));
            case "SYSTEM" -> new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant,requesterSystemPrincipalId));
            default -> throw new IllegalStateException("callback_run_requester_kind_invalid");
        };
        return new RunIdentity(owner,new AgentId(agentId),new InputId(inputId),new RunRef(tenant,runId),
                new TraceEnvelope(traceId,spanId,new InputId(rootInputId),Optional.ofNullable(causedByEventId).map(EventId::new)),new ResultRef(identityRef), requester,
                sourceMessageId == null ? Optional.empty() : Optional.of(new SourceMessageRef(new SessionRef(tenant,sourceSessionId),sourceMessageId)),
                new RunOriginRef(tenant,originRef));
    }
}

final class CallbackServiceOrigin {
    public String tenantId;
    public String principalId;
    public String integrationId;
    public long credentialVersion;
}
