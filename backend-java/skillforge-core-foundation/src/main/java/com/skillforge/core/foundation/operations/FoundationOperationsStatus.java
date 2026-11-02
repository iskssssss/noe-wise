package com.skillforge.core.foundation.operations;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.OperationsContracts.Probe;
import com.skillforge.core.foundation.operations.OperationsContracts.RuntimeStatus;
import com.skillforge.core.foundation.operations.persistence.OperationsMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 真实数据库/Redis探测与JVM采样；无法测量明确标记unavailable，不生成静态成功状态。 */
@Service
public class FoundationOperationsStatus {
    /** 运维状态读取。 */ public static final String READ = "foundation.operations.status_read";
    private final FoundationAccess access;
    private final DataSource database;
    private final OperationsMapper mapper;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectProvider<MeterRegistry> registry;

    /** 注入生产依赖；可选指标和Redis配置缺失按unavailable报告。 */
    public FoundationOperationsStatus(FoundationAccess access, DataSource database, OperationsMapper mapper,
            ObjectProvider<StringRedisTemplate> redis, ObjectProvider<MeterRegistry> registry) {
        this.access = access; this.database = database; this.mapper = mapper; this.redis = redis; this.registry = registry;
    }

    /** 授权后读取当前宿主真实依赖和内存；探测失败不暴露连接串或认证材料。 */
    public RuntimeStatus status() {
        access.require(READ);
        Instant now = Instant.now();
        List<Probe> probes = new ArrayList<>();
        probes.add(probeDatabase()); probes.add(probeRedis());
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Integer active = null; Integer idle = null;
        if (database instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) { active = pool.getActiveConnections(); idle = pool.getIdleConnections(); }
        }
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) probes.add(new Probe("metrics", "unavailable", 0, now, "未装配指标注册器"));
        else probes.add(new Probe("metrics", "up", 0, now, "已装配注册器，Prometheus抓取另由/actuator/prometheus验证"));
        return new RuntimeStatus(now, ManagementFactory.getRuntimeMXBean().getUptime(), memory.getUsed(), memory.getMax(),
                ManagementFactory.getThreadMXBean().getThreadCount(), active, idle, List.copyOf(probes));
    }

    /** 执行只读SELECT 1；SQL超时2秒，连接取得失败按down返回。 */
    private Probe probeDatabase() {
        long begin = System.nanoTime();
        try {
            boolean ok = Integer.valueOf(1).equals(mapper.probeDatabase());
            return observed("postgresql", ok ? "up" : "down", begin, ok ? "只读探测成功" : "探测结果异常");
        } catch (RuntimeException failure) { return observed("postgresql", "down", begin, "数据库连接或只读探测失败"); }
    }

    /** 使用已配置Redis连接执行PING，缺少依赖与连接失败分别报告。 */
    private Probe probeRedis() {
        long begin = System.nanoTime();
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) return observed("redis", "unavailable", begin, "未配置Redis连接");
        try {
            String answer = template.execute((RedisCallback<String>) connection -> connection.ping());
            boolean ok = "PONG".equals(answer);
            return observed("redis", ok ? "up" : "down", begin, ok ? "PING成功" : "PING结果异常");
        } catch (RuntimeException failure) { return observed("redis", "down", begin, "Redis连接或PING失败"); }
    }

    /** 每个探测记录自身采样时刻和实测耗时。 */
    private static Probe observed(String name, String state, long begin, String detail) {
        return new Probe(name, state, (System.nanoTime() - begin) / 1000000, Instant.now(), detail);
    }
}
