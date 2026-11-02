package com.skillforge.core.workflow.runtime;

import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import java.util.Set;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/** root预算→父Run后，在原事务中锁真实Workflow Execution并核验原活动节点来源。 */
public final class WorkflowNodeAdmissionGuard implements JdbcExecutionStore.ChildAdmissionGuard {
    private final WorkflowParser parser;
    private final RequestVerifier requests;

    /** 宿主按真实节点组装冻结请求；该验证器只能使用传入SqlSession及已重建源。 */
    @FunctionalInterface public interface RequestVerifier {
        void require(SqlSession session, ExecutionStore.Run parent, WorkflowNodeSource.Source source, ExecutionStore.Admission child);
    }
    public WorkflowNodeAdmissionGuard(WorkflowParser parser, RequestVerifier requests) {
        this.parser = Objects.requireNonNull(parser); this.requests = Objects.requireNonNull(requests);
    }

    /** 只在Store工厂阶段调用；所有领域映射共用Store业务SqlSession。 */
    public static void configure(Configuration configuration) {
        JdbcExecutionLedgerPort.configure(configuration); JdbcWorkflowBindings.configure(configuration);
    }

    @Override public void require(SqlSession session, ExecutionStore.Run parent,
            ExecutionStore.ChildOrigin origin, ExecutionStore.Admission child) {
        if (!(origin instanceof ExecutionStore.WorkflowNodeChild source)) throw rejected();
        var locked = JdbcExecutionLedgerPort.lockBorrowed(session, source.node().executionRef());
        var execution = locked.execution();
        if (!parent.identity().equals(execution.owner()) || !source.node().owner().equals(execution.owner())
                || !source.lease().executionRef().equals(execution.executionRef())
                || execution.lease().isEmpty() || !source.lease().fence().equals(execution.lease().orElseThrow())
                || !locked.storageNow().isBefore(source.lease().fence().leaseUntil())
                || source.sourceRevision() != execution.executionRevision() || execution.cancelRequestId().isPresent()
                || !Set.of(WorkflowExecutionState.RUNNING, WorkflowExecutionState.RESUMING).contains(execution.state())
                || !source.checkpointFingerprint().equals(WorkflowNodeSource.checkpointFingerprint(execution.checkpoint()))
                || !child.run().equals(WorkflowNodeSource.runIdentity(source.node()))
                || !child.identity().owner().equals(parent.identity().owner())
                || !child.identity().verificationRef().equals(parent.actorVerificationRef())) throw rejected();
        var checkpoint = WorkflowCheckpointCodec.decode(execution.checkpoint());
        var binding = JdbcWorkflowBindings.findBorrowed(session, checkpoint.target()).orElseThrow(WorkflowNodeAdmissionGuard::rejected);
        requests.require(session, parent, WorkflowNodeSource.rebuild(execution, source.node(), binding, parser), child);
    }
    private static ExecutionStore.Rejection rejected() { return new ExecutionStore.Rejection("workflow_node_admission_rejected"); }
}
