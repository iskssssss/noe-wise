package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 连接器实例实体，对应数据库表 {@code connector_instances}。
 *
 * <p>表示租户级的具体业务系统端点与凭证配置；凭证使用应用层 AES 加密存储，
 * 禁止以明文保存。
 */
@Data
@TableName(value = "nw_connector_instances", autoResultMap = true)
public class ConnectorInstanceDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 实例业务 ID（对外展示）。 */
    private String instanceId;

    /** 创建请求身份及机构成员，用于只读核对。 */
    private String createRequestId;
    private String createActorId;

    /** 执行策略：mock / mcp / http（显式字段，执行零查询分派）。 */
    private String executorType;

    /** 实例自包含的能力清单；MCP 经 tools/list 自动发现，或手动维护。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> capabilities;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 连接器实例显示名称。 */
    private String name;

    /** 基础 URL。 */
    private String baseUrl;

    /** 认证类型：bearer / basic 等。 */
    private String authType;

    /** 加密后的认证凭证。 */
    private String authCredentialEncrypted;

    /** Vault 密钥路径（可选）。 */
    private String vaultSecretPath;

    /** capability -> 请求路径映射，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> capabilityMapping;

    /** 附加请求头，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> headers;

    /** 实例状态：active / disabled。 */
    private String status;

    /** 最近健康检查时间。 */
    private Instant lastHealthCheckAt;

    /** 健康检查结果，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> healthCheckResult;

    /** 可见性：tenant / platform。 */
    private String visibility;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
