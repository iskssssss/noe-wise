package com.skillforge.core.foundation.catalog.entitlements;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;

/** 功能授权和配额的完整传输契约；null 套餐限额不限，null 租户覆盖继承。 */
public final class EntitlementContracts {
    private EntitlementContracts() { }
    /** @param code 稳定功能编码 @param name 名称 @param description 简短说明 @param enabled 全局可授权开关 @param revision CAS 修订 */
    public record Feature(String code,String name,String description,boolean enabled,long revision) { }
    /** @param code 功能身份 @param name 名称 @param description 说明 @param enabled 全局开关 @param expectedRevision 0 创建，正数 CAS */
    public record SaveFeature(String code,String name,String description,@JsonProperty(value="enabled",required=true) @JsonSetter(nulls=Nulls.FAIL) boolean enabled,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param id 字符串套餐身份 @param code 套餐编码 @param name 名称 @param enabled 套餐启用状态 @param revision 配额 CAS 修订 */
    public record Plan(String id,String code,String name,boolean enabled,long revision) { }
    /** @param featureCode 功能编码 @param enabled 计划授权或明确覆盖 @param revision 当前授权 CAS */
    public record Grant(String featureCode,boolean enabled,long revision) { }
    /** @param planId 现有套餐 ID @param featureCode 目录编码 @param enabled 授权开关 @param expectedRevision 0 新增，正数 CAS */
    public record SavePlanGrant(String planId,String featureCode,@JsonProperty(value="enabled",required=true) @JsonSetter(nulls=Nulls.FAIL) boolean enabled,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param tenantId 受控目标机构 @param featureCode 功能编码 @param enabled null 明确删除覆盖恢复继承 @param expectedRevision 0 新增，正数 CAS */
    public record SaveTenantGrant(String tenantId,String featureCode,@JsonProperty(value="enabled",required=true) Boolean enabled,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param agents 智能体数量 @param workflows Builder 工作流数 @param knowledgeBases 知识库数 @param members 成员数 @param channels 渠道数 @param fileBytes 文件字节，精确字符串 @param adminTasks 并行任务数 @param subInstitutions 当前直属子机构数；套餐null不限、覆盖null继承、0禁止新增 */
    public record Limits(@JsonProperty(value="agents",required=true) Long agents,@JsonProperty(value="workflows",required=true) Long workflows,@JsonProperty(value="knowledge_bases",required=true) Long knowledgeBases,@JsonProperty(value="members",required=true) Long members,@JsonProperty(value="channels",required=true) Long channels,@JsonProperty(value="file_bytes",required=true) String fileBytes,@JsonProperty(value="admin_tasks",required=true) Long adminTasks,@JsonProperty(value="sub_institutions",required=true) Long subInstitutions) { }
    /** @param planId 套餐 ID @param limits null 明确设置对应项不限 @param expectedRevision 当前配额修订 */
    public record SavePlanQuota(String planId,Limits limits,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param planId 套餐ID @param limits 同一锁下读取的完整限额 @param revision 对应限额的精确CAS修订 */
    public record PlanQuota(String planId,Limits limits,long revision) { }
    /** @param tenantId 受控机构 ID @param limits null 对应项明确恢复继承 @param expectedRevision 机构配额当前修订 */
    public record SaveTenantQuota(String tenantId,Limits limits,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param tenantId 机构 ID @param planId 当前套餐 ID @param tenantStatus 当前机构状态 @param planEnabled 当前套餐启用 @param agents 有效上限 @param workflows 有效上限 @param knowledgeBases 有效上限 @param members 有效上限 @param channels 有效上限 @param fileBytes 有效字节上限 @param adminTasks 有效任务上限 @param subInstitutions 当前直属子机构有效数量上限，null不限 @param revision 租户覆盖 CAS */
    public record EffectiveQuota(String tenantId,String planId,String tenantStatus,boolean planEnabled,Long agents,Long workflows,Long knowledgeBases,Long members,Long channels,Long fileBytes,Long adminTasks,Long subInstitutions,long revision) {
        /** 读取封闭配额项，null 表示当前套餐/覆盖明确不限。 */
        public Long limit(ResourceKind kind) { return switch(kind) {case AGENT->agents;case WORKFLOW->workflows;case KNOWLEDGE_BASE->knowledgeBases;case MEMBER->members;case CHANNEL->channels;case FILE_BYTES->fileBytes;case ADMIN_TASK->adminTasks;case SUB_INSTITUTION->subInstitutions;}; }
    }
    /** @param kind 资源种类 @param limit 可空精确上限字符串 @param used 精确用量字符串 */
    public record Usage(ResourceKind kind,String limit,String used) { }
    /** @param tenantId 机构 ID @param limits 当前有效限额 @param overrides 当前覆盖，null 继承 @param revision 机构覆盖 CAS @param usage 真实资源统计 @param features 当前有效功能编码 */
    public record TenantEntitlements(String tenantId,Limits limits,Limits overrides,long revision,List<Usage> usage,List<String> features) { }
}
