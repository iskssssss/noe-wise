package com.skillforge.core.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import java.util.Map;
import lombok.Data;

/**
 * Agent 实例实体，对应数据库表 {@code agents}。
 *
 * <p>租户基于系统预置模板创建 Agent 实例（模板信息作为初始值写入本体字段），
 * id 与 tenant/agentKey 永久标识同一个智能体；本表保存可反复编辑的工作草稿。
 * 发布配置和能力挂载保存在不可修改的发布历史中，由 publishedRevisionId 指向当前生效版本。
 */
@Data
@TableName(value = "nw_agents", autoResultMap = true)
public class AgentDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** Agent 唯一业务标识。 */
    private String agentKey;

    /** 最近发布的修订号；尚未发布为 0。 */
    private Integer revision = 0;

    /** 当前生效的不可变发布修订 ID；业务绑定始终引用本体 id。 */
    private Long publishedRevisionId;

    /** 工作草稿是否包含尚未发布的配置或能力挂载修改。 */
    private Boolean hasDraftChanges = true;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 来源模板ID；独立安装表示创建来源，公共跟随部署通过其部署关系解析当前定义。 */
    private Long templateId;

    /** 独立实例的创建来源版本；公共部署执行解析时填入本次采用版本并冻结到Run。 */
    private String templateVersion;

    /** Agent 显示名称。 */
    private String name;

    /** system prompt（本体配置；发布/启用前必须非空）。 */
    private String systemPrompt;

    /** 角色描述（本体配置，仅信息展示）。 */
    private String roleDescription;

    /** 头像地址，作为实例身份配置独立保存。 */
    private String iconUrl;

    /** 显式运行预算，空字段继承平台配置；每个 Run 保存有效预算。 */
    @TableField("max_iterations")
    private Integer maxBusinessModelCalls;
    private Integer maxToolCalls;
    private Integer maxKnowledgeCalls;
    private Long maxTotalTokens;
    private Long maxWallTimeSeconds;

    public AgentExecutionConfig getExecutionConfig() {
        return new AgentExecutionConfig(maxBusinessModelCalls, maxToolCalls, maxKnowledgeCalls,
                maxTotalTokens, maxWallTimeSeconds);
    }

    public void setExecutionConfig(AgentExecutionConfig value) {
        AgentExecutionConfig config = value == null ? AgentExecutionConfig.inherited() : value;
        maxBusinessModelCalls = config.maxBusinessModelCalls(); maxToolCalls = config.maxToolCalls();
        maxKnowledgeCalls = config.maxKnowledgeCalls();
        maxTotalTokens = config.maxTotalTokens();
        maxWallTimeSeconds = config.maxWallTimeSeconds();
    }

    /** 平台 LLM 模型引用；null 表示继承机构默认 LLM。 */
    private Long modelConfigId;

    /** 安装配置 JSON，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> config;

    /** Agent 原生委派开关及其允许目标。 */
    @TableField(typeHandler = AgentDelegationConfigJsonbTypeHandler.class)
    private AgentDelegationConfig delegationConfig = AgentDelegationConfig.disabled();

    /** Agent 状态：draft / published / disabled / archived。 */
    private String status;

    /** 发布时间。 */
    private Instant publishedAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
