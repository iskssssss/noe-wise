package com.skillforge.core.chat.session;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 当前进程活跃 Outbox 订阅的定向唤醒；不保存事件、游标或待投递队列。
 *
 * <p>调用方先注册、再读取持久游标；读取前捕获版本，空页才等待版本变化，
 * 因此读取与等待间的提交不会丢失。最后一个订阅关闭即释放会话条目。
 */
public final class SessionOutputNotifications {
    private final ConcurrentHashMap<Scope, Signal> signals = new ConcurrentHashMap<>();

    /** 已授权的订阅入口注册观察；每个回执必须在连接结束时关闭。 */
    public Subscription observe(String tenantId, String sessionId) {
        var scope = new Scope(tenantId, sessionId);
        var signal = signals.compute(scope, (ignored, current) -> {
            var next = current == null ? new Signal() : current;
            next.observers++;
            return next;
        });
        return new Subscription(scope, signal);
    }

    /** 只允许在对应 Outbox 提交成功后调用；没有活跃观察者时不保留任何状态。 */
    public void committed(String tenantId, String sessionId) {
        signals.computeIfPresent(new Scope(tenantId, sessionId), (ignored, signal) -> {
            signal.lock.lock();
            try {
                signal.version++;
                signal.changed.signalAll();
            } finally {
                signal.lock.unlock();
            }
            return signal;
        });
    }

    /** 租户与会话共同限定通知范围，不接受空范围。 */
    private record Scope(String tenantId, String sessionId) {
        /** 校验作用域，避免不同无效身份共享通知。 */
        private Scope {
            if (tenantId == null || tenantId.isBlank() || sessionId == null || sessionId.isBlank())
                throw new IllegalArgumentException("session_output_notification_scope_invalid");
        }
    }

    /**
     * 版本和每个订阅的关闭状态由同一显式锁保护；观察者数量只在 Map.compute 内修改。
     * JDK21的Object.wait会占用虚拟线程载体，Condition等待则允许卸载，不占住执行所需载体。
     */
    private static final class Signal {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private long version;
        private int observers;
    }

    /** 单条连接的观察回执；版本仅用于唤醒，不能作为持久事件的读取游标。 */
    public final class Subscription implements AutoCloseable {
        private final Scope scope;
        private final Signal signal;
        /** 只在signal.lock内读写，容器回调可安全关闭并唤醒阻塞中的读取线程。 */
        private boolean closed;

        /** 仅通知器创建带真实注册责任的回执。 */
        private Subscription(Scope scope, Signal signal) {
            this.scope = scope;
            this.signal = signal;
        }

        /** 每次读取持久 Outbox 前捕获版本；读取期间发生提交也会令后续等待立即返回。 */
        public long version() {
            signal.lock.lock();
            try { return signal.version; }
            finally { signal.lock.unlock(); }
        }

        /**
         * 等待提交、关闭或有界维护间隔，不执行数据库读取。
         *
         * @return true 表示观察到版本改变；false 表示关闭或达到维护间隔
         */
        public boolean awaitChange(long observedVersion, Duration maximumWait) throws InterruptedException {
            Objects.requireNonNull(maximumWait, "maximumWait");
            if (maximumWait.isNegative() || maximumWait.isZero())
                throw new IllegalArgumentException("session_output_notification_wait_invalid");
            long remaining = maximumWait.toNanos();
            long started = System.nanoTime();
            signal.lock.lock();
            try {
                while (!closed && signal.version == observedVersion && remaining > 0) {
                    // 版本检查与原子释放锁后等待共用一把锁；读页后先发生提交也不会丢失唤醒。
                    signal.changed.awaitNanos(remaining);
                    remaining = maximumWait.toNanos() - (System.nanoTime() - started);
                }
                return !closed && signal.version != observedVersion;
            } finally {
                signal.lock.unlock();
            }
        }

        /** 幂等释放注册并唤醒本连接；同会话其他连接继续观察原版本。 */
        @Override public void close() {
            signal.lock.lock();
            try {
                if (closed) return;
                closed = true;
                signal.changed.signalAll();
            } finally {
                signal.lock.unlock();
            }
            // committed按Map→signal锁顺序执行；此处先释放signal锁，再更新Map，不能形成反向锁序。
            signals.computeIfPresent(scope, (ignored, current) -> {
                if (current != signal) throw new IllegalStateException("session_output_notification_registration_lost");
                return --signal.observers == 0 ? null : current;
            });
        }
    }
}
