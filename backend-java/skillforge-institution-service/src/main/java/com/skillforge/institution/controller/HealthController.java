package com.skillforge.institution.controller;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.constant.HealthConstants;
import com.skillforge.core.shared.dto.health.DependencyHealth;
import com.skillforge.core.shared.dto.health.HealthReadyResponse;
import com.skillforge.core.shared.dto.health.HealthStatusResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查控制器。
 *
 * <p>提供 {@code /health/live}、{@code /health/ready} 与 {@code /health/deps}
 * 端点，供 Kubernetes 与运维探活使用。
 */
@RestController
public class HealthController {
    private static final org.slf4j.Logger DATABASE_LOG = org.slf4j.LoggerFactory.getLogger(HealthController.class);

    /** MyBatis 探测入口，用于探测 PostgreSQL。 */
    private final HealthMapper mapper;

    /** Redis 操作模板，用于探测 Redis。 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 构造健康检查控制器。
     *
     * @param mapper 数据库探测 Mapper
     * @param redisTemplate  Redis 操作模板

     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param mapper 领域持久化入口，使用调用方事务
     * @param redisTemplate Redis 健康探测依赖
     */
    public HealthController(HealthMapper mapper, StringRedisTemplate redisTemplate) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "HealthController", "HealthController", TenantContext.getTenantId());
        try {
            this.mapper = mapper;
            this.redisTemplate = redisTemplate;

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "HealthController", "HealthController", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "HealthController", "HealthController", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 存活探针：进程可响应即返回 ok。
     *
     * @return 存活状态响应

     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @GetMapping({"/health", "/health/live"})
    public HealthStatusResponse live() {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "HealthController", "live", TenantContext.getTenantId());
        try {
            return new HealthStatusResponse(
                    HealthConstants.STATUS_OK,
                    HealthConstants.SERVICE_NAME,
                    HealthConstants.VERSION);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "HealthController", "live", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "HealthController", "live", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 就绪探针：真实探测 PostgreSQL 与 Redis。
     *
     * @return 依赖状态响应；任一依赖异常时返回 503

     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @GetMapping({"/health/ready", "/health/deps"})
    public ResponseEntity<HealthReadyResponse> ready() {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "HealthController", "ready", TenantContext.getTenantId());
        try {
            Map<String, DependencyHealth> deps = new LinkedHashMap<>();
            boolean ok = true;
            // 探测 PostgreSQL：执行 SELECT 1
            try {
                mapper.ping();
                deps.put(HealthConstants.DEP_POSTGRES, new DependencyHealth(true, null));
            } catch (Exception ex) {
                ok = false;
                // 公开探针只返回固定分类；数据库异常可能包含 SQL、端点和连接参数。
                DATABASE_LOG.warn("健康依赖不可用 dependency={} errorType={}",
                        HealthConstants.DEP_POSTGRES, ex.getClass().getSimpleName());
                deps.put(HealthConstants.DEP_POSTGRES,
                        new DependencyHealth(false, "postgres_unavailable"));
            }
            // 探测 Redis：执行 PING
            try {
                redisTemplate.getConnectionFactory().getConnection().ping();
                deps.put(HealthConstants.DEP_REDIS, new DependencyHealth(true, null));
            } catch (Exception ex) {
                ok = false;
                DATABASE_LOG.warn("健康依赖不可用 dependency={} errorType={}",
                        HealthConstants.DEP_REDIS, ex.getClass().getSimpleName());
                deps.put(HealthConstants.DEP_REDIS,
                        new DependencyHealth(false, "redis_unavailable"));
            }
            HealthReadyResponse body = new HealthReadyResponse(
                    ok ? HealthConstants.STATUS_OK : HealthConstants.STATUS_DEGRADED,
                    deps);
            return ok ? ResponseEntity.ok(body)
                    : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "HealthController", "ready", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "HealthController", "ready", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
}
