package com.skillforge.core.agent.deployment;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 平台直授与机构部署的公开协议，请求不形成所有权或认证身份。 */
public final class AgentDeploymentContracts {
    private AgentDeploymentContracts() { }
    /** 显式选择源和授权；integrationId 仅原生部署可空，外部入口必须匹配认证域。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Deploy(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) String integrationId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(minLength=1) @Size(min=1,max=128) String externalAgentRef,@Size(max=128) String name,
            @Valid List<ResourceBinding> resourceBindings,@Valid List<DelegationBinding> delegationBindings,
            Map<String,Object> parameters,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"},pattern="[1-9][0-9]*") @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tenantId) {
        public Deploy(Long definitionId,Long grantId,String integrationId,String externalAgentRef,String name,List<ResourceBinding> resourceBindings,List<DelegationBinding> delegationBindings,Map<String,Object> parameters) {
            this(definitionId,grantId,integrationId,externalAgentRef,name,resourceBindings,delegationBindings,parameters,null);
        }
        public Deploy { resourceBindings=copy(resourceBindings);delegationBindings=copy(delegationBindings);parameters=map(parameters); }
    }
    /** 全量替换本地绑定，不改变源或授权；CAS 防止覆盖其他管理员修订。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Bindings(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long deploymentId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictLongDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) @Min(1) long expectedRevision,
            @Valid List<ResourceBinding> resourceBindings,@Valid List<DelegationBinding> delegationBindings,
            Map<String,Object> parameters,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"},pattern="[1-9][0-9]*") @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tenantId) {
        public Bindings(Long deploymentId,long expectedRevision,List<ResourceBinding> resourceBindings,List<DelegationBinding> delegationBindings,Map<String,Object> parameters) {
            this(deploymentId,expectedRevision,resourceBindings,delegationBindings,parameters,null);
        }
        public Bindings { resourceBindings=copy(resourceBindings);delegationBindings=copy(delegationBindings);parameters=map(parameters); }
    }
    /** 目标机构本地资源槽，仍经正式资源权限校验。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResourceBinding(@NotBlank String slotKey,@NotNull @Valid CapabilityMountRequest mount) { }
    /** 委派槽仅引用目标机构已发布实例。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DelegationBinding(@NotBlank String slotKey,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) @NotBlank String targetAgentId) { }
    /** 面向管理员的安全未就绪原因。 */
    public record Issue(String path,String code,String message) { }
    /** 稳定实例及精确部署/授权修订；本地资料不回写源。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long deploymentId,@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tenantId,String integrationId,@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long agentId,String externalAgentRef,
            @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantId,long grantRevision,String definitionName,String definitionVersion,
            String mode,long deploymentRevision,boolean ready,List<Issue> issues,List<AgentTemplateDefinition.ResourceSlot> resourceSlots,
            List<AgentTemplateDefinition.DelegationSlot> delegationSlots,List<ResourceBinding> resourceBindings,
            List<DelegationBinding> delegationBindings,Map<String,Object> parameters,GrantChain grantChain) { }
    /** 当前范围内的部署列表。 */
    public record Listing(int total,List<View> items) { }
    /** 未找到只表示当前没有可见的已提交回执，不代表原请求失败。 */
    public record Receipt<T>(String status,T result) { }
    /** 原授或显式父授权的转授；所有边界由可信当前主体和直属关系核验。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Grant(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long targetTenantId,
            @NotEmpty List<@NotBlank String> actions,Instant expiresAt,boolean allowRegrant,@Min(0) int remainingDepth,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"},pattern="[1-9][0-9]*") @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long parentGrantId) {
        public Grant { actions=copy(actions); }
    }
    /** 撤销精确授权修订，重复命令只能读回原撤销事实。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Revoke(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictLongDeserializer.class) @Min(1) long expectedRevision) { }
    /** 可重读的授权事实，撤销后不删除。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GrantView(@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantId,@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long targetTenantId,List<String> actions,
            Instant expiresAt,String status,long revision,String definitionName,String definitionVersion,String ownerKind,
            @io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long ownerTenantId,
            String grantorKind,@io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantorTenantId,
            @io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long parentGrantId,@io.swagger.v3.oas.annotations.media.Schema(types={"integer","null"}) Long parentGrantRevision,
            @io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long relationRef,@io.swagger.v3.oas.annotations.media.Schema(types={"integer","null"}) Long relationRevision,boolean allowRegrant,int remainingDepth) { }
    /** CAS 只收窄动作、期限和转授边界，不替换源、目标、父链或关系。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Narrow(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictLongDeserializer.class) @Min(1) long expectedRevision,
            @NotEmpty List<@NotBlank String> actions,Instant expiresAt,boolean allowRegrant,@Min(0) int remainingDepth) {
        public Narrow { actions=copy(actions); }
    }
    /** 同源显式换授；旧 Run 继续使用原冻结链。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SwitchAuthorization(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long deploymentId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictLongDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) @Min(1) long expectedRevision,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*") @NotNull @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantId,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"},pattern="[1-9][0-9]*") @Positive @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tenantId) {
        public SwitchAuthorization(Long deploymentId,long expectedRevision,Long grantId){this(deploymentId,expectedRevision,grantId,null);}
    }
    /** 原受理引用，顺序固定为原授到末端；当前核验只沿这些身份检查。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GrantChain(@io.swagger.v3.oas.annotations.media.Schema(type="string") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,String ownerKind,@io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long ownerTenantId,List<GrantHop> hops) {
        public GrantChain { hops=copy(hops); }
    }
    /** 仅治理元数据，历史修订与当前范围分开核验。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GrantHop(@io.swagger.v3.oas.annotations.media.Schema(type="string") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantId,long revision,String grantorKind,@io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long grantorTenantId,
            @io.swagger.v3.oas.annotations.media.Schema(type="string") @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long targetTenantId,@io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long parentGrantId,
            @io.swagger.v3.oas.annotations.media.Schema(types={"integer","null"}) Long parentGrantRevision,@io.swagger.v3.oas.annotations.media.Schema(type="string",types={"string","null"}) @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long relationRef,
            @io.swagger.v3.oas.annotations.media.Schema(types={"integer","null"}) Long relationRevision) { }
    /** 单一目标机构的授权列表。 */
    public record Grants(int total,List<GrantView> items) { }
    /** 声明槽位的类型化引用及开放提示词标量参数。 */
    public record LocalBindings(List<ResourceBinding> resourceBindings,List<DelegationBinding> delegationBindings,
            Map<String,Object> parameters) {
        public LocalBindings { resourceBindings=copy(resourceBindings);delegationBindings=copy(delegationBindings);parameters=map(parameters); }
    }
    private static <T> List<T> copy(List<T> value) { return value==null?List.of():List.copyOf(value); }
    private static Map<String,Object> map(Map<String,Object> value) { return value==null?Map.of():Map.copyOf(value); }
}
