package com.skillforge.core.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Data;

/**
 * Agent 模板实体，对应数据库表 {@code agent_templates}。
 *
 * <p>模板是 Agent 的发布单元，保存系统提示词、角色描述、平台模型引用、规划规则等
 * 定义快照，以及价格、权限和发布状态；租户基于已发布模板创建 Agent 实例。
 */
@Data
@TableName(value = "nw_agent_templates", autoResultMap = true)
public class AgentTemplateDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 模板唯一业务标识。 */
    private String templateKey;

    /** 模板显示名称。 */
    private String name;

    /** 当前发布版本号（semver），首次发布前为待发布版本号。 */
    private String version;

    /** 模板分类标签。 */
    private String category;

    /** 模板标签数组，JSONB 存储。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> tags;

    /** 模板描述。 */
    private String description;

    /** 图标 URL。 */
    private String iconUrl;

    /** 作者名称。 */
    private String author;

    /** 作者类型：platform / isv / tenant。 */
    private String authorType;

    /** 工作草稿定义；线上安装只读取独立发布快照。 */
    @TableField(typeHandler = AgentTemplateDefinitionJsonbTypeHandler.class)
    private AgentTemplateDefinition definition;

    /** 价格类型：free / paid。 */
    private String priceType;

    /** 价格（分），0 表示免费。 */
    private Integer priceCents;

    /** 累计安装次数。 */
    private Integer installCount;

    /** 模板状态：draft / review / published / deprecated / archived。 */
    private String status;

    /** 服务端分发策略，无默认开放值；首次发布后不可切换。 */
    private AgentDistributionPolicy distributionPolicy;

    /** 可信源所有者域；展示作者名称不参与授权。 */
    private String ownerKind;

    /** 平台源为空，机构源必须引用真实机构。 */
    private Long ownerTenantId;

    /** 工作草稿 CAS 修订；首次创建为 1，内容变化时递增。 */
    private Long draftRevision;

    /** 发布时间。 */
    private Instant publishedAt;

    /** 工作草稿是否存在尚未发布的修改。 */
    private Boolean hasUnpublishedChanges;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
