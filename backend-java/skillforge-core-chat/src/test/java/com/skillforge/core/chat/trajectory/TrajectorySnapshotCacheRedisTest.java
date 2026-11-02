package com.skillforge.core.chat.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立Redis证明快照序列化、TTL与容量；不借用开发环境数据。 */
@Testcontainers
class TrajectorySnapshotCacheRedisTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void initialize() {
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet(); connections.start();
        redis = new StringRedisTemplate(connections);
    }
    @AfterAll
    static void close() { if (connections != null) connections.destroy(); }

    @Test
    void roundTripsSafeMetadataAndEnforcesActorIsolationAndCapacity() {
        var cache = new TrajectorySnapshotCache(redis, new ObjectMapper().findAndRegisterModules(), Duration.ofMinutes(1), 2);
        var snapshot = snapshot("reader-capacity");
        String first = cache.save(snapshot), second = cache.save(snapshot), third = cache.save(snapshot);
        long remaining = List.of(first, second, third).stream().filter(id -> cache.read("TENANT", "reader-capacity", id) != null).count();
        assertEquals(2, remaining);
        assertEquals(snapshot, cache.read("TENANT", "reader-capacity", third));
        assertNull(cache.read("TENANT", "another-reader", third));
        assertNull(cache.read("PLATFORM_ADMIN", "reader-capacity", third));
    }

    @Test
    void expiresInsteadOfReconstructingAChangedSnapshot() throws InterruptedException {
        var cache = new TrajectorySnapshotCache(redis, new ObjectMapper().findAndRegisterModules(), Duration.ofMillis(300), 2);
        String id = cache.save(snapshot("reader-ttl"));
        assertNotNull(cache.read("TENANT", "reader-ttl", id));
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (cache.read("TENANT", "reader-ttl", id) != null && System.nanoTime() < deadline) Thread.sleep(20);
        assertNull(cache.read("TENANT", "reader-ttl", id));
        assertTrue(System.nanoTime() < deadline);
    }

    private static TrajectoryProjectionService.Snapshot snapshot(String actor) {
        var run = TrajectoryGraphTest.run("main", "session", "input");
        return new TrajectoryProjectionService.Snapshot("trajectory-snapshot-v3", "TENANT", actor,
                new TrajectoryQuery.Resource(7, "session", "root"), List.of(run), List.of(), List.of(), List.of(),
                List.of(TrajectoryGraphTest.auxiliary(run, "aux", "aux-call", "UNRESOLVED", null, null)),
                java.util.Map.of("aux", new com.skillforge.core.model.invocation.ModelCallRecordStatus(true, false)),
                List.of(new com.skillforge.core.billing.application.BillingTraceFacts.EffectiveCost("cost", "aux", 7, "session", "main", "root",
                        "unresolved", new java.math.BigDecimal("-9007199254740993.125"))), Instant.parse("2026-10-25T01:00:00Z"));
    }
}
