package com.skillforge.institution.runtime;

import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import org.apache.ibatis.session.SqlSession;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;

/** SDK根Execution只从原新Action请求证明，子Workflow来源仍由SDK父节点契约证明。 */
public final class AgentRuntimeWorkflowSourceVerifier implements SdkWorkflowPortal.SourceVerifier {
    private final ExecutionStore store;
    private final SessionExecutionWorkflows codec;
    public AgentRuntimeWorkflowSourceVerifier(ExecutionStore store, SessionExecutionWorkflows codec) {
        this.store=Objects.requireNonNull(store);this.codec=Objects.requireNonNull(codec);
    }
    @Override public void require(SqlSession session, ExecutionRecord execution) {
        if (session == null) { require(execution); return; }
        if (!(store instanceof JdbcExecutionStore jdbc)) throw rejected();
        jdbc.participate(session, execution.executionRef().tenantId(), () -> { require(execution); return null; });
    }
    private void require(ExecutionRecord execution) {
        var reference=execution.causativeActionRef().orElseThrow(AgentRuntimeWorkflowSourceVerifier::rejected);
        var action=store.action(reference).orElseThrow(AgentRuntimeWorkflowSourceVerifier::rejected);
        if(action.kind()!=ExecutionStore.Kind.WORKFLOW||!action.run().equals(execution.owner().runRef()))throw rejected();
        var run=store.load(action.run()).orElseThrow(AgentRuntimeWorkflowSourceVerifier::rejected);
        if(!run.identity().equals(execution.owner()))throw rejected();
        var original=store.material(reference.tenantId(),action.request()).orElseThrow(AgentRuntimeWorkflowSourceVerifier::rejected);
        if(!action.fingerprint().equals(AgentExecutionCodec.digest(original.content().schemaId()+"\0"+original.content().canonicalJson())))throw rejected();
        if(!(codec.decode(original.content()) instanceof SessionExecutionWorkflows.Dispatch dispatch)
                ||!dispatch.executionRef().equals(execution.executionRef()))throw rejected();
        SdkWorkflowPortal.requireInitialSource(execution,dispatch.target(),dispatch.snapshot(),dispatch.parameters(),dispatch.evidenceRefs());
    }
    private static IllegalArgumentException rejected(){return new IllegalArgumentException("workflow_registered_source_rejected");}
}
