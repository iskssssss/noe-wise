package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.application.TenantDirectoryService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.profile.UserProfileService;
import com.skillforge.core.chat.profile.UserProfileSummarizer;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立真实 Redis 验证多实例额度仲裁、原子 TTL、拒绝与取消归还，不接触开发实例。 */
@Testcontainers(disabledWithoutDocker = false)
class UserProfileSummaryRedisConcurrencyTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private final List<UserProfileSummarizer> instances = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private static final String KEY = "7:session:s-7:non_workflow_count";

    @BeforeEach
    void setup() {
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        redis.delete(KEY);
    }
    @AfterEach
    void cleanup() {
        Thread.interrupted();
        releases.forEach(CountDownLatch::countDown);
        try { instances.forEach(UserProfileSummarizer::close); }
        finally { connections.destroy(); }
    }

    @Test
    void twoInstancesClaimOneQuotaAndKeepConcurrentNewMessages() throws Exception {
        redis.opsForValue().set(KEY, "99", Duration.ofSeconds(20));
        var barrier = new CyclicBarrier(2);
        var controlledRedis = spy(redis);
        // 两个真实 INCRBY 均提交后才继续竞争；同步点不代替真实 Redis 原子判定。
        doAnswer(invocation -> {
            Object count = invocation.callRealMethod();
            barrier.await(10, TimeUnit.SECONDS);
            return count;
        }).when(controlledRedis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), eq("1"), anyString());
        var first = service(controlledRedis, 100);
        var second = service(controlledRedis, 100);
        var firstPool = blockWorker(first, false);
        var secondPool = blockWorker(second, false);
        var callers = Executors.newFixedThreadPool(2);
        try {
            var a = callers.submit(() -> first.submitCasualSummary("7", "s-7"));
            var b = callers.submit(() -> second.submitCasualSummary("7", "s-7"));
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertEquals("1", redis.opsForValue().get(KEY));
            assertEquals(1, firstPool.getQueue().size() + secondPool.getQueue().size());
            assertTrue(redis.getExpire(KEY) > 0 && redis.getExpire(KEY) <= 20);
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void firstIncrementAlwaysHasTtlAndRejectedTaskReturnsQuota() throws Exception {
        var service = service(redis, 2);
        var executor = blockWorker(service, false);
        int capacity = executor.getQueue().remainingCapacity();
        for (int index = 0; index < capacity; index++) executor.execute(() -> { });
        service.submitCasualSummary("7", "s-7");
        assertEquals("1", redis.opsForValue().get(KEY));
        assertTrue(redis.getExpire(KEY) > 0);
        service.submitCasualSummary("7", "s-7");
        assertEquals("2", redis.opsForValue().get(KEY));
        assertTrue(redis.getExpire(KEY) > 0);
        assertEquals(capacity, executor.getQueue().size());
    }

    @Test
    void cancelledQueuedTaskReturnsQuotaWithoutOverwritingNewMessages() throws Exception {
        var service = service(redis, 2);
        var executor = blockWorker(service, false);
        service.submitCasualSummary("7", "s-7");
        service.submitCasualSummary("7", "s-7");
        assertEquals("0", redis.opsForValue().get(KEY));
        service.submitCasualSummary("7", "s-7");
        assertEquals("1", redis.opsForValue().get(KEY));
        Thread.currentThread().interrupt();
        try {
            var failure = assertThrows(IllegalStateException.class, service::close);
            assertEquals("profile_summary_shutdown_interrupted", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals("3", redis.opsForValue().get(KEY));
        assertTrue(redis.getExpire(KEY) > 0);
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void unresponsiveTaskCannotReportSuccessfulClose() throws Exception {
        var service = service(redis, 2);
        blockWorker(service, true);
        var failure = assertThrows(IllegalStateException.class, service::close);
        assertEquals("profile_summary_shutdown_incomplete", failure.getMessage());
    }

    private UserProfileSummarizer service(StringRedisTemplate template, int threshold) {
        var settings = mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(new OperationalSettingsService.Snapshot(1,
                Map.of("casual_threshold", threshold, "casual_counter_ttl_days", 7)));
        var instance = new UserProfileSummarizer(mock(TenantDirectoryService.class), mock(SessionMapper.class),
                mock(ChatMessageMapper.class), mock(UserProfileService.class), mock(SessionService.class),
                mock(LlmService.class), new ObjectMapper(), settings, template);
        instances.add(instance);
        return instance;
    }
    private ThreadPoolExecutor blockWorker(UserProfileSummarizer instance, boolean ignoreInterrupt) throws Exception {
        var field = UserProfileSummarizer.class.getDeclaredField("executor");
        field.setAccessible(true);
        var executor = (ThreadPoolExecutor) field.get(instance);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        releases.add(release);
        executor.execute(() -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); }
                catch (InterruptedException exception) { if (!ignoreInterrupt) return; }
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        return executor;
    }
}
