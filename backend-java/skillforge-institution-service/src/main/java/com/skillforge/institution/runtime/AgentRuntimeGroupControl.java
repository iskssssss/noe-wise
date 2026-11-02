package com.skillforge.institution.runtime;

import com.skillforge.institution.group.GroupControlService;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import org.apache.ibatis.session.SqlSession;

/** 在唯一Store新派发事务核群控制；具体工具的业务写范围取正式冻结effect。 */
final class AgentRuntimeGroupControl implements JdbcExecutionStore.DispatchGate {
    private final GroupControlService controls;
    private final AgentExecutionCodec codec;

    AgentRuntimeGroupControl(GroupControlService controls, AgentExecutionCodec codec) {
        this.controls = Objects.requireNonNull(controls);
        this.codec = Objects.requireNonNull(codec);
    }

    @Override public boolean allowed(SqlSession session, ExecutionStore.Run run, ExecutionStore.Prepared prepared) {
        if (run.identity().owner().target().kind() != SessionTarget.Kind.GROUP) return true;
        // Workflow/委派是任务派发；后续每个真实节点及程序子工具仍在本Store按自己的effect取资格。
        boolean businessWrite = prepared.kind() == ExecutionStore.Kind.TOOL
                && !WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(prepared.request().content().schemaId())
                && codec.decodeTool(prepared.request().content()).target().effect() != ToolEffect.READ_ONLY;
        return controls.allowDispatch(session, run.identity(), businessWrite);
    }
}
