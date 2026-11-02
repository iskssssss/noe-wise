package com.skillforge.institution.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** 本次浏览器夹具的进程树所有权；记录已确认后代，关闭时等待全部静止。 */
final class MemoryBrowserProcesses implements AutoCloseable {
    private final Set<ProcessHandle> owned = new LinkedHashSet<>();
    private final Set<Long> snapshotWarnings = new HashSet<>();

    /** 获取根进程后立即登记，只清理此次创建的进程树。 */
    MemoryBrowserProcesses(ProcessHandle root) {
        owned.add(root);
    }

    /** 父进程退出之前保存 Vite、esbuild 和浏览器后代身份。 */
    synchronized void capture() {
        for (ProcessHandle handle : List.copyOf(owned)) {
            if (!handle.isAlive()) continue;
            try (var descendants = handle.descendants()) {
                descendants.forEach(owned::add);
            } catch (RuntimeException failure) {
                if (snapshotWarnings.add(handle.pid())) {
                    System.err.println("记忆验收进程快照暂不可用 PID=" + handle.pid() + "：" + failure);
                }
            }
        }
    }

    /** 停止自有进程并等待静止；清理异常保留给 try-with-resources，不掩盖原始测试失败。 */
    @Override
    public synchronized void close() {
        List<RuntimeException> failures = new ArrayList<>();
        capture();
        destroy(false, failures);
        awaitExit(5);
        destroy(true, failures);
        awaitExit(10);
        List<Long> alive = owned.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
        if (!alive.isEmpty()) {
            AssertionError failure = new AssertionError("记忆浏览器验收自有进程未静止：" + alive);
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        if (!failures.isEmpty()) {
            RuntimeException failure = new IllegalStateException("记忆浏览器验收清理发生错误");
            failures.stream().distinct().forEach(failure::addSuppressed);
            throw failure;
        }
    }

    /** 先请求结束，仍存活的进程随后强制结束；禁止按全局名称清理。 */
    private void destroy(boolean force, List<RuntimeException> failures) {
        for (ProcessHandle handle : List.copyOf(owned).reversed()) {
            if (!handle.isAlive()) continue;
            try {
                if (force) handle.destroyForcibly(); else handle.destroy();
            } catch (RuntimeException failure) {
                if (failures.stream().noneMatch(previous -> previous.getClass() == failure.getClass()
                        && Objects.equals(previous.getMessage(), failure.getMessage()))) failures.add(failure);
            }
        }
    }

    /** 以可观察进程退出为完成信号，时间仅限定清理上限。 */
    private void awaitExit(int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            capture();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
    }
}
