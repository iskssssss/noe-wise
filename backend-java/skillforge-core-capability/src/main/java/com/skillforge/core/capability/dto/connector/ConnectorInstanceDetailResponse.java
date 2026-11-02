package com.skillforge.core.capability.dto.connector;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 连接器实例详情响应 DTO。
 *
 * <p>凭证不回传明文：authCredential 恒为 {@code ***}（脱敏值）；
 * 供编辑表单回填全部可编辑字段。
 *
 * @param id                    实例主键
 * @param instanceId            实例业务 ID（不可改）
 * @param name                  实例名称
 * @param baseUrl               服务地址
 * @param executorType          执行策略（mock / mcp / http）
 * @param capabilities          能力清单
 * @param authType              认证类型
 * @param authCredential        认证凭证（恒为脱敏值 ***）
 * @param vaultSecretPath       Vault 密钥路径，可为空
 * @param capabilityMapping     capability 到路径的映射
 * @param headers               公开非敏感附加请求头；历史认证头不会回传
 * @param status                实例状态（active / disabled）
 * @param visibility            可见性（tenant / platform）
 * @param lastHealthCheckAt     最近健康检查时间，可为空
 * @param healthCheckResult     健康检查结果，可为空
 * @param createdAt             创建时间
 * @param updatedAt             更新时间
 */
public record ConnectorInstanceDetailResponse(
        Long id,
        String instanceId,
        String name,
        String baseUrl,
        String executorType,
        List<String> capabilities,
        String authType,
        String authCredential,
        String vaultSecretPath,
        Map<String, Object> capabilityMapping,
        Map<String, Object> headers,
        String status,
        String visibility,
        Instant lastHealthCheckAt,
        Map<String, Object> healthCheckResult,
        Instant createdAt,
        Instant updatedAt) {
}
