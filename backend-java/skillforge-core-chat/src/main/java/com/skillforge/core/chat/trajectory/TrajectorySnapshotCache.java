package com.skillforge.core.chat.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 有界TTL查询快照；只保存安全来源引用和计量，不保存正文、执行许可或可变业务状态。 */
@Component
public final class TrajectorySnapshotCache {
    private static final String PREFIX = "trajectory:snapshot:";
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            local clock = redis.call('TIME')
            local now = tonumber(clock[1])*1000000 + tonumber(clock[2])
            local tail = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            local order = now
            if #tail == 2 and order <= tonumber(tail[2]) then order = tonumber(tail[2])+1 end
            local ttl = tonumber(ARGV[2])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ttl)
            redis.call('ZADD', KEYS[1], order, KEYS[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now-ttl*1000)
            local count = redis.call('ZCARD', KEYS[1])
            local excess = count-tonumber(ARGV[3])
            if excess > 0 then
              local retired = redis.call('ZRANGE', KEYS[1], 0, excess-1)
              for _,key in ipairs(retired) do redis.call('DEL', key) end
              redis.call('ZREMRANGEBYRANK', KEYS[1], 0, excess-1)
            end
            redis.call('PEXPIRE', KEYS[1], ttl)
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;
    private final int capacity;

    public TrajectorySnapshotCache(StringRedisTemplate redis, ObjectMapper json,
            @Value("${skillforge.trajectory.snapshot-ttl:PT10M}") Duration ttl,
            @Value("${skillforge.trajectory.snapshots-per-actor:32}") int capacity) {
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.toMillis() < 1 || capacity < 1)
            throw new IllegalArgumentException("trajectory_snapshot_cache_configuration_invalid");
        this.redis = redis; this.json = json; this.ttl = ttl; this.capacity = capacity;
    }

    String save(TrajectoryProjectionService.Snapshot snapshot) {
        String id = UUID.randomUUID().toString();
        String owner = owner(snapshot.scope(), snapshot.actor());
        try {
            Long saved = redis.execute(SAVE, List.of(index(owner), key(owner, id)), json.writeValueAsString(snapshot),
                    Long.toString(ttl.toMillis()), Integer.toString(capacity));
            if (!Long.valueOf(1).equals(saved)) throw new IllegalStateException("trajectory_snapshot_cache_write_failed");
            return id;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("trajectory_snapshot_encoding_failed", invalid);
        }
    }

    TrajectoryProjectionService.Snapshot read(String scope, String actor, String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) return null;
        String value = redis.opsForValue().get(key(owner(scope, actor), id));
        if (value == null) return null;
        try { return json.readValue(value, TrajectoryProjectionService.Snapshot.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("trajectory_snapshot_cache_content_invalid", invalid);
        }
    }

    private static String owner(String scope, String actor) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((scope + "\0" + actor).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String index(String owner) { return PREFIX + "{" + owner + "}:index"; }
    private static String key(String owner, String id) { return PREFIX + "{" + owner + "}:" + id; }
}
