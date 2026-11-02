package com.skillforge.institution.workflowtest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Builder 只创建隔离资源；初始输入和 Pending 回复仍使用正式 /chat。 */
public final class WorkflowTestContracts {
    private WorkflowTestContracts() {}
    public record Source(String kind, @JsonProperty("draft_revision") Long draftRevision,
            @JsonProperty("definition_version_id") String definitionVersionId) {}
    public record SourceReceipt(String kind, @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("draft_revision") Long draftRevision,
            @JsonProperty("definition_version_id") String definitionVersionId, String hash) {}
    public record Budget(@JsonProperty("max_business_model_calls") int maxBusinessModelCalls,
            @JsonProperty("max_tool_calls") int maxToolCalls,
            @JsonProperty("max_knowledge_calls") int maxKnowledgeCalls,
            @JsonProperty("max_total_tokens") long maxTotalTokens,
            @JsonProperty("max_wall_time_seconds") long maxWallTimeSeconds) {}
    public record Create(@JsonProperty("request_id") String requestId,
            @JsonProperty("workflow_id") String workflowId, Source source, Map<String, Object> config,
            Bindings bindings, @JsonProperty("user_input") String userInput,
            Map<String, Object> parameters, Budget budget) {}
    public record Close(@JsonProperty("test_session_id") String testSessionId) {}
    public record CapabilityIdentity(String type, @JsonProperty("capabilityId") String capabilityId,
            @JsonProperty("versionId") String versionId) {
        public com.skillforge.core.capability.CapabilityRef domain() {
            return new com.skillforge.core.capability.CapabilityRef(com.skillforge.core.capability.CapabilityType.valueOf(type),capabilityId,versionId);
        }
    }
    public record Binding(String slot, CapabilityIdentity capability,
            @JsonProperty("tool_deployment_id") String toolDeploymentId,
            @JsonProperty("tool_deployment_revision") Long toolDeploymentRevision,
            @JsonProperty("knowledge_revision") Long knowledgeRevision) {
        public WorkflowInstallationBindings.CapabilityBinding domain() {
            return new WorkflowInstallationBindings.CapabilityBinding(slot,capability.domain(),toolDeploymentId,toolDeploymentRevision,knowledgeRevision);
        }
    }
    public record Bindings(List<Binding> capabilities, List<WorkflowInstallationBindings.WorkflowBinding> workflows,
            List<WorkflowInstallationBindings.AgentBinding> agents) {
        public WorkflowInstallationBindings domain() {
            return new WorkflowInstallationBindings(capabilities==null?List.of():capabilities.stream().map(Binding::domain).toList(),workflows,agents);
        }
    }
    public record Candidate(CapabilityIdentity capability, @JsonProperty("tool_deployment_id") String toolDeploymentId,
            @JsonProperty("tool_deployment_revision") Long toolDeploymentRevision,
            @JsonProperty("knowledge_revision") Long knowledgeRevision, String label) {}
    public record Slot(String slot, boolean required, String type, List<Candidate> candidates) {}
    public record BudgetOptions(Budget recommended, Budget maximum) {}
    public record Options(SourceReceipt source, @JsonProperty("input_schema") Map<String, Object> inputSchema,
            @JsonProperty("config_schema") Object configSchema,
            @JsonProperty("capability_slots") List<Slot> capabilitySlots, BudgetOptions budget,
            List<String> restrictions, @JsonProperty("cost_notice") String costNotice) {}
    public record Receipt(@JsonProperty("test_session_id") String testSessionId,
            @JsonProperty("request_id") String requestId, String status, SourceReceipt source,
            @JsonProperty("session_id") String sessionId, @JsonProperty("agent_id") String agentId,
            @JsonProperty("end_user_id") String endUserId, @JsonProperty("initial_input_id") String initialInputId,
            @JsonProperty("initial_message") String initialMessage, @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("run_id") String runId, @JsonProperty("run_status") String runStatus,
            @JsonProperty("close_status") String closeStatus, @JsonProperty("blocking_reason") String blockingReason) {}
}
