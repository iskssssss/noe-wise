package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.TenantId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowRecoveryTasksTest {
    @Test void slowRecoveryDoesNotBlockAnotherTenantOrClaimQueuedWorkAndCloseClearsContext() throws Exception {
        var started = new CountDownLatch(2);
        var cleaned = new AtomicInteger();
        var context = new ThreadLocal<TenantId>();
        var tasks = new WorkflowRecoveryTasks(2, (tenant, work) -> {
            assertNull(context.get()); context.set(tenant);
            try { work.run(); }
            finally { context.remove(); cleaned.incrementAndGet(); }
        });
        var alpha = new ExecutionRef(new TenantId("1"), "alpha");
        var beta = new ExecutionRef(new TenantId("2"), "beta");
        var sideEffects = new AtomicInteger();
        try {
            assertTrue(tasks.submit(alpha, () -> waitForStop(alpha, context, started)));
            assertFalse(tasks.submit(alpha, sideEffects::incrementAndGet), "同一个Execution不能本机并发推进");
            assertTrue(tasks.submit(beta, () -> waitForStop(beta, context, started)));
            assertTrue(started.await(5, TimeUnit.SECONDS), "甲的恢复阻塞不应挡住乙的执行");
            assertFalse(tasks.submit(new ExecutionRef(new TenantId("3"), "overflow"), sideEffects::incrementAndGet));
        } finally { tasks.close(); }
        assertEquals(0, sideEffects.get(), "没有槽位的恢复不得提前领取租约或启动调用");
        assertEquals(2, cleaned.get());
        assertTrue(tasks.stopped());
        assertFalse(tasks.submit(alpha, sideEffects::incrementAndGet));
    }

    private static void waitForStop(ExecutionRef ref, ThreadLocal<TenantId> context, CountDownLatch started) {
        assertEquals(ref.tenantId(), context.get()); started.countDown();
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
}
