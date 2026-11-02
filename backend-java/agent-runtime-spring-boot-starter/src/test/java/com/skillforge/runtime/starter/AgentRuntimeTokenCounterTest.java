package com.skillforge.runtime.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.SessionTokenCounter.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 只验证装配缓存的工厂、归属和并发；实际编码精度由 JTokkit 适配器测试覆盖。 */
class AgentRuntimeTokenCounterTest {
    private static final ResultRef PROFILE = new ResultRef("profile");
    private static final List<ModelMessage> MESSAGES = List.of(new ModelMessage("message", ModelRole.USER, "hello world"));

    @Test void retainsOnly32TenantsAndEvictsLeastRecentlyUsedWithoutCachingMeasurements() {
        var created = new ConcurrentHashMap<TenantId, AtomicInteger>();
        var measured = new AtomicInteger();
        var rejected = new AtomicBoolean();
        var counter = new AgentRuntimeTokenCounter(tenant -> {
            increment(created, tenant);
            return (profile, target, messages, tools) -> {
                assertEquals(tenant, target.binding().tenantId());
                measured.incrementAndGet();
                if (rejected.get()) throw new IllegalStateException("current_profile_rejected");
                return measurement(profile, target, messages, tools);
            };
        });
        for (int i = 0; i < 32; i++) measure(counter, i);
        var original = measure(counter, 0);
        measure(counter, 32);
        assertEquals(original, measure(counter, 0));
        assertEquals(1, created.get(tenant(0)).get(), "命中刷新 LRU 顺序，最近使用的租户不被淘汰");
        measure(counter, 1);
        assertEquals(2, created.get(tenant(1)).get(), "第 33 个租户淘汰最久未使用的计数器");
        assertEquals(34, created.values().stream().mapToInt(AtomicInteger::get).sum());
        rejected.set(true);
        var failure = assertThrows(IllegalStateException.class, () -> measure(counter, 0));
        assertEquals("current_profile_rejected", failure.getMessage());
        assertEquals(37, measured.get(), "缓存只复用计数器，每次仍调用受信计量路径");
    }

    @Test void factoryFailureDoesNotEvictAValidTenantOrCacheTheFailure() {
        var created = new ConcurrentHashMap<TenantId, AtomicInteger>();
        var counter = new AgentRuntimeTokenCounter(tenant -> {
            int generation = increment(created, tenant);
            if (tenant.equals(tenant(32)) && generation == 1) throw new IllegalStateException("factory_unavailable");
            return AgentRuntimeTokenCounterTest::measurement;
        });
        for (int i = 0; i < 32; i++) measure(counter, i);
        assertThrows(IllegalStateException.class, () -> measure(counter, 32));
        measure(counter, 0);
        assertEquals(1, created.get(tenant(0)).get(), "新工厂失败不应提前驱逐旧缓存");
        measure(counter, 32);
        assertEquals(2, created.get(tenant(32)).get());
        measure(counter, 1);
        assertEquals(2, created.get(tenant(1)).get());
    }

    @Test void concurrentSameTenantSharesOneCounterAndDoesNotBlockAnotherTenantMeasurement() throws Exception {
        var created = new ConcurrentHashMap<TenantId, AtomicInteger>();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var counter = new AgentRuntimeTokenCounter(tenant -> {
            increment(created, tenant);
            return (profile, target, messages, tools) -> {
                if (tenant.equals(tenant(0))) {
                    entered.countDown();
                    await(release);
                }
                return measurement(profile, target, messages, tools);
            };
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> measure(counter, 0));
            var second = workers.submit(() -> measure(counter, 0));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "同一计数器允许两个在途计量，不在缓存锁内执行");
                assertEquals(1, created.get(tenant(0)).get());
                var other = workers.submit(() -> measure(counter, 1));
                assertEquals(tenant(1), other.get(5, TimeUnit.SECONDS).profile().target().binding().tenantId());
            } finally { release.countDown(); }
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void evictedCounterFinishesAnInflightMeasurementWithItsOriginalReference() throws Exception {
        var created = new ConcurrentHashMap<TenantId, AtomicInteger>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var counter = new AgentRuntimeTokenCounter(tenant -> {
            int generation = increment(created, tenant);
            return (profile, target, messages, tools) -> {
                if (tenant.equals(tenant(0)) && generation == 1) {
                    entered.countDown();
                    await(release);
                }
                return measurement(profile, target, messages, tools);
            };
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = workers.submit(() -> measure(counter, 0));
            Measurement recreated;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int i = 1; i <= 32; i++) measure(counter, i);
                recreated = measure(counter, 0);
                assertEquals(2, created.get(tenant(0)).get());
            } finally { release.countDown(); }
            assertEquals(recreated, pending.get(5, TimeUnit.SECONDS), "淘汰只丢失复用，不取消或更改已取得的计数器");
        }
    }

    private static int increment(ConcurrentHashMap<TenantId, AtomicInteger> counts, TenantId tenant) {
        return counts.computeIfAbsent(tenant, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static Measurement measure(AgentRuntimeTokenCounter counter, int tenantNumber) {
        var target = new FrozenModelTarget(new ModelBindingRef(tenant(tenantNumber), "binding", 1), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capabilities")),
                Duration.ofSeconds(5), new ResultRef("resolution"));
        return counter.measure(PROFILE, target, MESSAGES, List.of());
    }

    private static TenantId tenant(int number) { return new TenantId("tenant-" + number); }

    private static Measurement measurement(ResultRef profileRef, FrozenModelTarget target, List<ModelMessage> messages, List<ModelTool> tools) {
        assertEquals(PROFILE, profileRef);
        assertEquals(MESSAGES, messages);
        assertTrue(tools.isEmpty());
        var profile = new Profile(profileRef, "fixture", "fixture", "fixture", target,
                new RoleCosts(1, 1, 1), 3, 4, 10, 4096);
        return new Measurement(profile, messages, List.of(2L), 2, 1, 3);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
