package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 工作流模板实体，对应数据库表 {@code workflow_templates}。
 *
 * <p>工作流模板是市场发行单元，包含 workflow.yaml 文件路径（含 meta 段）、配置
 * schema、权限声明与发布状态；租户基于模板创建工作流实例。
 */
@Data
@TableName(value = "nw_workflow_templates", autoResultMap = true)
public class WorkflowTemplateDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 工作流唯一标识，如 customer-service。 */
    private String workflowId;

    /** 所属租户 ID（可空；NULL=平台模板全局可见，非空=租户模板仅该租户可见，V54 起上传写入）。 */
    private Long tenantId;

    /** 工作流显示名称。 */
    private String name;

    /** 版本号（semver）。 */
    private String version;

    /** 工作流类型：state-machine / rag / agentic 等。 */
    private String type;

    /** 工作流分类标签。 */
    private String category;

    /** 工作流标签数组，JSONB 存储；通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> tags;

    /** 工作流描述。 */
    private String description;


    /** 安装配置项 schema，JSONB 存储；通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> configSchema;

    /** 所需权限声明，JSONB 存储；通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> requiredPermissions;

    /** 连接器需求声明（WORKFLOW.md frontmatter connector_requirements），JSONB 存储。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> connectorRequirements;

    /** 工具声明（WORKFLOW.md frontmatter tools），JSONB 存储。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> tools;





    /** 作者名称。 */
    private String author;

    /** 作者类型：platform / isv / tenant。 */
    private String authorType;

    /** 价格类型：free / paid。 */
    private String priceType;

    /** 价格（分），0 表示免费。 */
    private Integer priceCents;

    /** 累计安装次数。 */
    private Integer installCount;


    /** 模板状态：draft / review / published / deprecated / archived。 */
    private String status;

    /** 发布时间。 */
    private Instant publishedAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
