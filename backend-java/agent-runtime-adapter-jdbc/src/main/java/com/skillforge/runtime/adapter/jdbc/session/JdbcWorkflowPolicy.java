package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 当前 Workflow IAM 与真实 Execution owner 屏障；执行许可由唯一 ExecutionStore 核验。 */
public final class JdbcWorkflowPolicy {
    private final JdbcIdentityTransactions identities;
    private final SqlSessionFactory sessions;

    /** 只装配当前身份与真实 Execution 查询，不装配旧 Run 或已消费许可。 */
    public JdbcWorkflowPolicy(DataSource source, JdbcIdentityTransactions identities) {
        this.identities = Objects.requireNonNull(identities);
        var configuration = new Configuration(new Environment("workflow-policy", new JdbcTransactionFactory(), Objects.requireNonNull(source)));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/AgentRuntimeWorkflowPolicyMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("workflow_policy_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("workflow_policy_initialization_failure", failure); }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 已提交typed父取消的当前控制授权；SDK另核真实Action/Execution来源，不消费执行permit或读取旧Run。 */
    public boolean authorizeCommittedCancel(VerifiedIdentity actor,com.skillforge.runtime.session.spi.ExecutionStore.Run parent,TypedPayload resource) {
        Objects.requireNonNull(actor);Objects.requireNonNull(parent);Objects.requireNonNull(resource);
        if(!parent.cancelled()||parent.status()!=RunStatus.CANCELLED||parent.phase()!=com.skillforge.runtime.session.spi.ExecutionStore.Phase.FINISHED
                ||!actor.owner().equals(parent.identity().owner())
                ||WorkflowPolicyResource.owner("workflow.cancel",resource).filter(parent.identity().owner()::equals).isEmpty())return false;
        if (WorkflowPolicyResource.runIdentity("workflow.cancel", resource)
                .filter(parent.identity()::equals).isEmpty()) return false;
        return authorized(actor,"workflow.cancel",resource,parent.identity().trace().traceId(),parent.identity().trace().spanId());
    }

    /** 只读查询无需Provider permit，但必须是原Execution完整owner当前仍授权的身份。 */
    public boolean authorizeQuery(WorkflowQuery query) {
        return authorizeExecutionOwner(query, "workflow.query");
    }
    /** Engine每个节点的当前写权限与完整Execution owner，不能用已结束Provider permit替代。 */
    public boolean authorizeExecution(WorkflowQuery query) {
        return authorizeExecutionOwner(query, "workflow.execute");
    }
    /** 新Store依赖恢复的当前权限证明；调用者已锁真实Execution，不再打开独立连接或查旧Run。 */
    public boolean authorizeExecutionBorrowed(SqlSession session,VerifiedIdentity identity,ExecutionLedgerPort.ExecutionRecord execution) {
        if(!identity.owner().equals(execution.owner().owner()))return false;
        var query=new WorkflowQuery(identity,execution.executionRef());
        return identities.authorize(session,new IdentityPolicyPort.AuthorizationRequest(identity,"workflow.execute",WorkflowPolicyResource.execution(query,execution.owner().agentId()),
                execution.owner().trace().traceId(),execution.owner().trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
    }
    private boolean authorizeExecutionOwner(WorkflowQuery query, String action) {
        Objects.requireNonNull(query);
        try (var session = sessions.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            var mapper = session.getMapper(WorkflowPolicyMapper.class); mapper.bindTenant(query.executionRef().tenantId().value());
            var row = mapper.executionOwner(query.executionRef().tenantId().value(), query.executionRef().executionId());
            if (row == null || !row.owner().equals(query.identity().owner())) return false;
            var resource = action.equals("workflow.query")
                    ? WorkflowPolicyResource.query(query, row.agentId())
                    : WorkflowPolicyResource.execution(query, row.agentId());
            return authorized(query.identity(), action, resource, "workflow-execution", action);
        } catch (RuntimeException failure) { throw new IllegalStateException("workflow_policy_read_failure"); }
    }

    private boolean authorized(VerifiedIdentity identity, String action, TypedPayload resource, String trace, String span) {
        var authorization = identities.authorize(new IdentityPolicyPort.AuthorizationRequest(identity, action, resource, trace, span));
        if (authorization instanceof IdentityPolicyPort.AuthorizationResult.Failed) throw new IllegalStateException("workflow_policy_unavailable");
        return authorization instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
    }

}

interface WorkflowPolicyMapper {
    String bindTenant(@Param("tenant") String tenant);
    WorkflowExecutionOwnerRow executionOwner(@Param("tenant") String tenant, @Param("execution") String execution);
}
final class WorkflowExecutionOwnerRow {
    public String tenantId, sessionId, targetKind, targetId, agentId;
    public Long endUserId;
    SessionOwner owner() {
        var session=new SessionRef(new TenantId(tenantId),sessionId);
        var target=new SessionTarget(SessionTarget.Kind.valueOf(targetKind),targetId);
        if(target.kind()==SessionTarget.Kind.GROUP) {
            if(endUserId!=null)throw new IllegalStateException("workflow_group_owner_user_mismatch");
            return new SessionOwner(session,SessionSubject.group(new GroupBindingRef(session.tenantId(),targetId)),target);
        }
        return SessionOwner.user(session,target,new EndUserId(Objects.requireNonNull(endUserId)));
    }
    AgentId agentId() { return new AgentId(agentId); }
}
