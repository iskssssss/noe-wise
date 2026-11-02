package com.skillforge.core.agent.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.Issue;
import com.skillforge.core.agent.model.AgentTemplateDefinition.DelegationSlot;
import com.skillforge.core.agent.model.AgentTemplateDefinition.ResourceSlot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 精确草稿相对当前发布的声明影响；不表示模型、连接或执行权限全部就绪。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentDefinitionImpactResponse(
        @Schema(type="string",pattern="[1-9][0-9]*") @JsonSerialize(using=ToStringSerializer.class) Long definitionId,
        long draftRevision, String currentPublishedRevision, Changes changes,
        int visibleDeploymentCount, List<DeploymentImpact> deployments) {
    public AgentDefinitionImpactResponse { deployments = List.copyOf(deployments); }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Changes(List<ResourceSlot> addedResourceSlots, List<ResourceSlot> changedResourceSlots,
            List<String> removedResourceSlotKeys, List<DelegationSlot> addedDelegationSlots,
            List<String> removedDelegationSlotKeys, List<String> addedParameterKeys, List<String> removedParameterKeys) {
        public Changes {
            addedResourceSlots=List.copyOf(addedResourceSlots); changedResourceSlots=List.copyOf(changedResourceSlots);
            removedResourceSlotKeys=List.copyOf(removedResourceSlotKeys); addedDelegationSlots=List.copyOf(addedDelegationSlots);
            removedDelegationSlotKeys=List.copyOf(removedDelegationSlotKeys); addedParameterKeys=List.copyOf(addedParameterKeys);
            removedParameterKeys=List.copyOf(removedParameterKeys);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeploymentImpact(
            @Schema(type="string",pattern="[1-9][0-9]*") @JsonSerialize(using=ToStringSerializer.class) Long tenantId,
            @Schema(type="string",pattern="[1-9][0-9]*") @JsonSerialize(using=ToStringSerializer.class) Long deploymentId,
            @Schema(type="string",pattern="[1-9][0-9]*") @JsonSerialize(using=ToStringSerializer.class) Long agentId,
            long deploymentRevision, Boolean currentDeclarationsSatisfied, boolean draftDeclarationsSatisfied,
            List<Issue> currentIssues, List<Issue> draftIssues) {
        public DeploymentImpact { currentIssues=List.copyOf(currentIssues); draftIssues=List.copyOf(draftIssues); }
    }
}
