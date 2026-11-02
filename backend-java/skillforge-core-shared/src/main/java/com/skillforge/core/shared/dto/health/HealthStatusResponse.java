package com.skillforge.core.shared.dto.health;

/**
 * 存活探针响应 DTO。
 *
 * @param status  健康状态
 * @param service 服务名称
 * @param version 服务版本号
 */
public record HealthStatusResponse(
        String status,
        String service,
        String version) {
}
