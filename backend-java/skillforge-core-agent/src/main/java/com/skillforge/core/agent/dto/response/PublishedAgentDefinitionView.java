package com.skillforge.core.agent.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.agent.model.AgentTemplateDefinition.ResourceSlot;
import com.skillforge.core.agent.model.AgentTemplateDefinition.DelegationSlot;
import java.util.List;

/**
 * 已获有效消费授权后的公开发布投影，不能表示所有者草稿或实际执行配置。
 * @param definitionId 稳定源身份 @param ownerKind 平台/机构源域 @param ownerTenantId 平台为空
 * @param publishedRevision 精确不可变发布版本 @param name 公开名称 @param roleDescription 公开角色说明
 * @param resourceSlots 公开资源声明，仅含key/类型/说明 @param delegationSlots 公开协作角色，不含实际目标Agent
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PublishedAgentDefinitionView(@JsonSerialize(using=ToStringSerializer.class)Long definitionId,String ownerKind,
        @JsonSerialize(using=ToStringSerializer.class)Long ownerTenantId,String publishedRevision,String name,String roleDescription,
        List<ResourceSlot> resourceSlots,List<DelegationSlot> delegationSlots) {
    public PublishedAgentDefinitionView {resourceSlots=List.copyOf(resourceSlots);delegationSlots=List.copyOf(delegationSlots);}
}
