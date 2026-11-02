package com.skillforge.core.workflow.runtime;

import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import java.util.List;
import java.util.Objects;

/** Runtime 准入时解析的完整安装目标；执行器只按槽读取原快照。 */
public record WorkflowRuntimeBindings(List<ToolSlot> tools,List<KnowledgeSlot> knowledge,
        List<WorkflowSlot> workflows,List<AgentSlot> agents) {
    public WorkflowRuntimeBindings {
        tools=List.copyOf(tools);knowledge=List.copyOf(knowledge);workflows=List.copyOf(workflows);agents=List.copyOf(agents);
        var slots=new java.util.HashSet<String>();
        tools.forEach(value->{if(!slots.add(value.slot()))throw invalid();});
        knowledge.forEach(value->{if(!slots.add(value.slot()))throw invalid();});
        if(workflows.stream().map(WorkflowSlot::workflow).distinct().count()!=workflows.size()
                ||agents.stream().map(AgentSlot::slot).distinct().count()!=agents.size())throw invalid();
    }
    public record ToolSlot(String slot,FrozenToolTarget target,ToolBinding binding) {
        public ToolSlot {text(slot);Objects.requireNonNull(target);Objects.requireNonNull(binding);}
    }
    public record KnowledgeSlot(String slot,FrozenKnowledgeTarget target) {
        public KnowledgeSlot {text(slot);Objects.requireNonNull(target);}
    }
    public record WorkflowSlot(String workflow,FrozenWorkflowTarget target) {
        public WorkflowSlot {text(workflow);Objects.requireNonNull(target);}
    }
    public record AgentSlot(String slot,String agentId) {
        public AgentSlot {text(slot);text(agentId);}
    }
    private static void text(String value) {if(value==null||value.isBlank())throw invalid();}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("workflow_frozen_bindings_invalid");}
}
