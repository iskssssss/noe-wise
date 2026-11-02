package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 定向通知保护读页到等待的竞争，连接关闭后不保留第二份待投递状态。 */
class SessionOutputNotificationsTest {
    @Test void commitBetweenPageReadAndAwaitIsObservedImmediately() throws Exception {
        var notifications = new SessionOutputNotifications();
        try (var subscription = notifications.observe("7", "session")) {
            long beforeRead = subscription.version();
            notifications.committed("7", "session");
            assertTrue(subscription.awaitChange(beforeRead, Duration.ofNanos(1)));
            assertFalse(subscription.awaitChange(subscription.version(), Duration.ofNanos(1)));
        }
    }

    @Test void tenantsAndSessionsCannotWakeEachOtherAndAllObserversSeeTheirCommit() throws Exception {
        var notifications = new SessionOutputNotifications();
        try (var first = notifications.observe("7", "session");
             var second = notifications.observe("7", "session");
             var otherTenant = notifications.observe("8", "session");
             var otherSession = notifications.observe("7", "other")) {
            notifications.committed("7", "session");
            assertTrue(first.awaitChange(0, Duration.ofNanos(1)));
            assertTrue(second.awaitChange(0, Duration.ofNanos(1)));
            assertFalse(otherTenant.awaitChange(0, Duration.ofNanos(1)));
            assertFalse(otherSession.awaitChange(0, Duration.ofNanos(1)));
        }
    }

    @Test void closingConnectionReleasesItsWaitAndNewConnectionStartsWithoutRetainedNotifications() throws Exception {
        var notifications = new SessionOutputNotifications();
        var subscription = notifications.observe("7", "session");
        var entering = new CountDownLatch(1);
        var done = new CompletableFuture<Boolean>();
        Thread.ofPlatform().start(() -> {
            entering.countDown();
            try { done.complete(subscription.awaitChange(0, Duration.ofSeconds(30))); }
            catch (InterruptedException interrupted) { done.completeExceptionally(interrupted); }
        });
        assertTrue(entering.await(2, TimeUnit.SECONDS));
        subscription.close();
        assertFalse(done.get(2, TimeUnit.SECONDS));
        notifications.committed("7", "session");
        try (var replacement = notifications.observe("7", "session")) {
            subscription.close();
            assertEquals(0, replacement.version());
            notifications.committed("7", "session");
            assertTrue(replacement.awaitChange(0, Duration.ofNanos(1)));
        }
    }

    /** 超过当前JDK21默认载体上限的订阅仍须全部启动；版本已提交与关闭分别产生真实完成信号。 */
    @Test void virtualSubscriptionsAllStartAndWakeWithoutOccupyingOneCarrierPerWaiter() throws Exception {
        var notifications = new SessionOutputNotifications();
        int count = 512;
        var ready = new CountDownLatch(count);
        var subscriptions = new ArrayList<SessionOutputNotifications.Subscription>();
        var results = new ArrayList<CompletableFuture<Boolean>>();
        var waiters = new ArrayList<Thread>();
        try {
            for (int index = 0; index < count; index++) {
                var subscription = notifications.observe("7", "virtual-session-" + index);
                var result = new CompletableFuture<Boolean>();
                subscriptions.add(subscription); results.add(result);
                waiters.add(Thread.ofVirtual().name("session-notification-waiter-" + index).start(() -> {
                    try {
                        long beforeRead = subscription.version();
                        ready.countDown();
                        result.complete(subscription.awaitChange(beforeRead, Duration.ofSeconds(30)));
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                }));
            }
            // ready在捕获版本后发出，不靠sleep猜测已等待；最后一条尚未await时提交也必须立即可见。
            assertTrue(ready.await(5, TimeUnit.SECONDS), "512个虚拟订阅必须全部启动并捕获版本，不能被原生等待占满载体");
            for (int index = 0; index < count; index++) {
                if (index < count / 2) notifications.committed("7", "virtual-session-" + index);
                else subscriptions.get(index).close();
            }
            CompletableFuture.allOf(results.toArray(CompletableFuture<?>[]::new)).get(5, TimeUnit.SECONDS);
            for (int index = 0; index < count; index++) assertEquals(index < count / 2, results.get(index).join().booleanValue());
        } finally {
            // 负控即使在ready处失败，也由平台测试线程关闭全部订阅，释放已占用载体并停止尚未启动的任务。
            subscriptions.forEach(SessionOutputNotifications.Subscription::close);
            waiters.forEach(Thread::interrupt);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            for (Thread waiter : waiters) waiter.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            assertTrue(waiters.stream().noneMatch(Thread::isAlive), "所有虚拟等待者必须在本用例退出前静止");
        }
    }

    /** 虚拟订阅中断仍抛InterruptedException并清除中断标志，之后版本读取和提交不能遗留锁。 */
    @Test void interruptedVirtualSubscriptionReleasesLockAndCanObserveTheNextCommit() throws Exception {
        var notifications = new SessionOutputNotifications();
        var subscription = notifications.observe("7", "interrupted-session");
        var ready = new CountDownLatch(1);
        var interrupted = new CompletableFuture<Boolean>();
        Thread waiter = Thread.ofVirtual().name("interrupted-session-notification-waiter").start(() -> {
            try {
                long beforeRead = subscription.version();
                ready.countDown();
                subscription.awaitChange(beforeRead, Duration.ofSeconds(30));
                interrupted.complete(false);
            } catch (InterruptedException expected) {
                interrupted.complete(!Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                interrupted.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            waiter.interrupt();
            assertTrue(interrupted.get(2, TimeUnit.SECONDS), "等待中断必须抛异常并遵循清除中断标志的契约");
            long beforeCommit = subscription.version();
            notifications.committed("7", "interrupted-session");
            assertTrue(subscription.awaitChange(beforeCommit, Duration.ofNanos(1)));
        } finally {
            subscription.close(); waiter.interrupt(); waiter.join(Duration.ofSeconds(2));
            assertFalse(waiter.isAlive(), "中断的虚拟等待者必须停止");
        }
    }
}
