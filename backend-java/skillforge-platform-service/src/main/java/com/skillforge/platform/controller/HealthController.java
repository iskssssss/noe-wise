package com.skillforge.platform.controller;

import com.skillforge.core.shared.constant.HealthConstants;
import com.skillforge.core.shared.dto.health.DependencyHealth;
import com.skillforge.core.shared.dto.health.HealthReadyResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台 Dashboard 的公开依赖诊断；只探测本平台宿主数据源与 Redis，不借用机构健康状态。 */
@RestController
public final class HealthController {
    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);
    private final HealthMapper database;
    private final StringRedisTemplate redis;

    /** 注入平台当前实际使用的数据源和 Redis 模板，探测不读取租户业务数据。 */
    public HealthController(HealthMapper database, StringRedisTemplate redis) {
        this.database = database;
        this.redis = redis;
    }

    /** PostgreSQL SELECT 1 与 Redis PING 均成功才返回 ok/200，否则逐项标记降级并返回 503。 */
    @GetMapping("/health/deps")
    public ResponseEntity<HealthReadyResponse> dependencies() {
        Map<String, DependencyHealth> dependencies = new LinkedHashMap<>();
        try {
            boolean ok = database.ping() == 1;
            dependencies.put(HealthConstants.DEP_POSTGRES, new DependencyHealth(ok, ok ? null : "postgres_unavailable"));
        } catch (Exception failure) {
            LOG.warn("平台健康依赖不可用 dependency={} errorType={}", HealthConstants.DEP_POSTGRES, failure.getClass().getSimpleName());
            dependencies.put(HealthConstants.DEP_POSTGRES, new DependencyHealth(false, "postgres_unavailable"));
        }
        try {
            var factory = redis.getConnectionFactory();
            if (factory == null) throw new IllegalStateException("redis_connection_factory_missing");
            // 探测拥有本次连接，成功或异常都释放；公开响应不包含连接异常的端点或凭据。
            try (var connection = factory.getConnection()) {
                boolean ok = "PONG".equals(connection.ping());
                dependencies.put(HealthConstants.DEP_REDIS, new DependencyHealth(ok, ok ? null : "redis_unavailable"));
            }
        } catch (Exception failure) {
            LOG.warn("平台健康依赖不可用 dependency={} errorType={}", HealthConstants.DEP_REDIS, failure.getClass().getSimpleName());
            dependencies.put(HealthConstants.DEP_REDIS, new DependencyHealth(false, "redis_unavailable"));
        }
        boolean ok = dependencies.values().stream().allMatch(DependencyHealth::ok);
        var body = new HealthReadyResponse(ok ? HealthConstants.STATUS_OK : HealthConstants.STATUS_DEGRADED, dependencies);
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
