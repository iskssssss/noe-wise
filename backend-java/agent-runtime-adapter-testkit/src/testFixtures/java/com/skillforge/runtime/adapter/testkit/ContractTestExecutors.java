package com.skillforge.runtime.adapter.testkit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 契约竞争场景只提交与参与者数量相同的任务，显式限定线程、排队和拒绝行为。 */
final class ContractTestExecutors {
    private static final AtomicInteger POOLS = new AtomicInteger();

    private ContractTestExecutors() { }

    static ExecutorService fixedPool(int participants) {
        String prefix = "contract-test-" + POOLS.incrementAndGet() + "-worker-";
        AtomicInteger workers = new AtomicInteger();
        return new ThreadPoolExecutor(participants, participants, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(participants), task -> {
                    Thread worker = new Thread(task, prefix + workers.incrementAndGet());
                    worker.setDaemon(false);
                    worker.setPriority(Thread.NORM_PRIORITY);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
