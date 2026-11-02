package com.skillforge.core.channel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/**
 * 渠道实体，对应数据库表 {@code channels}。
 *
 * <p>表示租户下可接入的对话渠道（web_widget / openapi 等），保存渠道令牌哈希
 * 与 UI/业务配置；令牌明文仅在创建或轮换时返回一次。
 */
@Data
@TableName(value = "nw_channels", autoResultMap = true)
public class ChannelDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 渠道类型：web_widget / openapi。 */
    private String channelType;

    /** 渠道显示名称。 */
    private String name;

    /** 渠道业务配置，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> config;

    /** 渠道 UI 配置，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> uiConfig;

    /** 渠道状态：active / disabled。 */
    private String status;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;

    /** 渠道访问令牌 SHA-256（明文仅创建/轮换时返回）。 */
    private String channelTokenHash;

    /** 令牌前缀，用于列表展示。 */
    private String channelTokenPrefix;

    /** OpenAPI 凭据独立于业务配置；吊销时两列同时清空。 */
    private String apiKeyHash;

    /** AES-GCM 加密签名密钥，只由凭据入口写入、认证边界读取。 */
    private String apiSecretEncrypted;
}
