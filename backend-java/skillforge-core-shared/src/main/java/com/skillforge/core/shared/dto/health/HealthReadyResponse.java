package com.skillforge.core.shared.dto.health;

import java.util.Map;

/**
 * 就绪探针响应 DTO。
 *
 * @param status 整体健康状态
 * @param deps   依赖健康状态映射：依赖名 → 状态
 */
public record HealthReadyResponse(
        String status,
        Map<String, DependencyHealth> deps) {
}
