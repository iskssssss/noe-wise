package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import java.time.Instant;
import java.util.*;

/** 准入前冻结 Skill、Workflow 与 Knowledge；Tool 由每次模型准备读取当前目录。 */
public interface SessionCapabilities {
    /** 非 Tool 调用配置只作为准入候选，必须由原挂载与发布 binding 全值证明。 */
    record Targets(long generation,List<FrozenWorkflowTarget> workflows,List<FrozenKnowledgeTarget> knowledge) {
        public Targets {
            if(generation<=0)throw invalid();workflows=List.copyOf(workflows);knowledge=List.copyOf(knowledge);
        }
    }
    record Skill(String activationMode,long tokenBudget) {
        public Skill { if(!Set.of("ALWAYS","ON_DEMAND").contains(activationMode)||tokenBudget<=0)throw invalid(); }
    }
    /** 非 Tool 挂载版本与类型化执行目标一起冻结；Knowledge 实际版本可由 FOLLOW_ACTIVE 解析。 */
    record Mount(String mountId,long revision,int priority,CapabilityRef capability,TypedPayload descriptor,
                 Optional<Skill> skill,Optional<FrozenWorkflowTarget> workflow,
                 Optional<FrozenKnowledgeTarget> knowledge,Optional<String> knowledgeRevisionId) {
        public Mount {
            Objects.requireNonNull(capability);Objects.requireNonNull(descriptor);Objects.requireNonNull(skill);
            Objects.requireNonNull(workflow);Objects.requireNonNull(knowledge);Objects.requireNonNull(knowledgeRevisionId);
            if(mountId==null||!mountId.matches("[1-9][0-9]*")||revision<=0
                    ||(skill.isPresent()?1:0)+(workflow.isPresent()?1:0)+(knowledge.isPresent()?1:0)!=1)throw invalid();
            boolean valid=switch(capability.type()) {
                case SKILL -> skill.isPresent()&&knowledgeRevisionId.isEmpty();
                case TOOL -> false;
                case WORKFLOW -> workflow.filter(t->t.capability().equals(capability)
                        &&t.handle().value().startsWith("workflow:"+mountId+":")
                        &&t.handle().value().length()>("workflow:"+mountId+":").length()).isPresent()&&knowledgeRevisionId.isEmpty();
                case KNOWLEDGE_SOURCE -> knowledge.filter(t->t.capability().equals(capability)&&t.handle().value().equals("knowledge:"+mountId)).isPresent()
                        &&knowledgeRevisionId.filter(v->v.matches("[1-9][0-9]*")).isPresent();
            };
            if(!valid)throw invalid();
        }
        public String handle(){return switch(capability.type()){case SKILL->"skill:"+mountId;case TOOL->throw invalid();case WORKFLOW->workflow.orElseThrow().handle().value();case KNOWLEDGE_SOURCE->knowledge.orElseThrow().handle().value();};}
    }
    record Snapshot(SessionOwner owner,CapabilitySnapshotRef reference,VerifiedIdentity originalIdentity,
                    ResultRef authorizationRef,List<Mount> mounts,Instant frozenAt) {
        public Snapshot {
            Objects.requireNonNull(owner);Objects.requireNonNull(reference);Objects.requireNonNull(originalIdentity);
            Objects.requireNonNull(authorizationRef);Objects.requireNonNull(frozenAt);mounts=List.copyOf(mounts);
            if(!reference.sessionRef().equals(owner.sessionRef())||!originalIdentity.owner().equals(owner)
                    ||!reference.snapshotId().equals(reference.inputId().value()+":capability-mount-snapshot")
                    ||mounts.stream().map(Mount::mountId).distinct().count()!=mounts.size())throw invalid();
            // Workflow binding 按准入输入冻结；同一挂载的其他 Input 不能进入当前快照。
            if(mounts.stream().anyMatch(mount->mount.workflow().isPresent()
                    &&!mount.workflow().orElseThrow().handle().value().equals(
                            "workflow:"+mount.mountId()+":"+reference.inputId().value())))throw invalid();
        }
        public List<String> handles(){return mounts.stream().map(Mount::handle).toList();}
        @Override public String toString(){return "SessionCapabilitySnapshot[REDACTED]";}
    }
    Snapshot freeze(VerifiedIdentity current,AgentId actualAgentId,InputId input,Targets candidates);
    Optional<Snapshot> read(SessionOwner owner,InputId input);
    default Snapshot require(SessionOwner owner,CapabilitySnapshotRef reference) {
        var snapshot=read(owner,reference.inputId()).orElseThrow(SessionCapabilities::invalid);
        if(!snapshot.reference().equals(reference))throw invalid();return snapshot;
    }
    default void requireWorkflow(SessionOwner owner,CapabilitySnapshotRef reference,FrozenWorkflowTarget target){
        if(require(owner,reference).mounts().stream().noneMatch(m->m.workflow().equals(Optional.of(target))))throw invalid();
    }
    default void requireKnowledge(SessionOwner owner,CapabilitySnapshotRef reference,FrozenKnowledgeTarget target){
        if(require(owner,reference).mounts().stream().noneMatch(m->m.knowledge().equals(Optional.of(target))))throw invalid();
    }
    private static String text(String value){
        if(value==null||value.isBlank()||!value.equals(value.trim()))throw invalid();return value;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("session_capability_snapshot_rejected");}
}
