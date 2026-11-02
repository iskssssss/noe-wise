package com.skillforge.core.capability;

/** 类型化能力描述的封闭联合。 */
public sealed interface CapabilityDescriptor permits
        SkillDescriptor, ToolVersion, WorkflowDescriptor, KnowledgeSourceDescriptor {

    CapabilityMetadata metadata();
}
