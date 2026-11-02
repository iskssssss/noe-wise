package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * 一次显式开启的有界观察，记录准入开始和实际claim回调的单调时钟，包含准入耗时。
 * 此对象不调度任务、不拥有写权；关闭后脱离Worker并保留最终样本供报告读取。
 */
public final class AgentRuntimeExecutionQueueObservation implements AutoCloseable {
    private final TenantId tenant;
    private final int maxSamples;
    private final Consumer<AgentRuntimeExecutionQueueObservation> released;
    /** 显式上限内的计时值，不保存请求、能力、身份或待执行Runnable。 */
    private final Map<RunRef, Sample> samples = new LinkedHashMap<>();
    private boolean closed;
    private boolean overflowed;

    /** 仅由Worker建立观察作用域；上限由调用方指定，溢出必须在验收报告中保留。 */
    AgentRuntimeExecutionQueueObservation(TenantId tenant, int maxSamples,
            Consumer<AgentRuntimeExecutionQueueObservation> released) {
        this.tenant = Objects.requireNonNull(tenant); this.released = Objects.requireNonNull(released);
        if (maxSamples < 1 || maxSamples > 10_000) throw new IllegalArgumentException("execution_queue_sample_limit_invalid");
        this.maxSamples = maxSamples;
    }

    /** 记录本作用域内同租户Run的首次接收；重复wake和槽位饱和均不改写第一次时点。 */
    synchronized void received(RunRef run, long nanos) {
        if (closed || !tenant.equals(run.tenantId()) || samples.containsKey(run)) return;
        if (samples.size() >= maxSamples) { overflowed = true; return; }
        samples.put(run, new Sample(run, nanos, OptionalLong.empty()));
    }

    /** 只采纳Store.claim成功后Controls.open的第一次真实回调，没有receive的Run不补造时点。 */
    synchronized void claimed(RunRef run, long nanos) {
        if (closed) return;
        var current = samples.get(run);
        if (current == null || current.claimedNanos().isPresent()) return;
        samples.put(run, new Sample(run, current.receivedNanos(), OptionalLong.of(nanos)));
    }

    /** 当前不可变快照；未claim样本保留OptionalLong.empty，不估算为零或从Run时间反推。 */
    public synchronized List<Sample> samples() { return List.copyOf(samples.values()); }

    /** 达到上限后收到其他Run时返回true；不能把截断数据报告为完整队列观测。 */
    public synchronized boolean overflowed() { return overflowed; }

    /** 停止收集并解除Worker引用，已有有界快照仍可读取；重复关闭无副作用。 */
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        released.accept(this);
    }

    /**
     * 同一JVM单调时钟中的真实Run接收与领取时点，不是Epoch时间。
     * @param run 观测所属的完整Run
     * @param receivedNanos 本scope内准入开始的System.nanoTime值，槽位满时仍保留
     * @param claimedNanos Store.claim成功后Controls.open的System.nanoTime值，未领取时缺失
     */
    public record Sample(RunRef run, long receivedNanos, OptionalLong claimedNanos) {
        /** 原始单调值可为负，只有同一JVM内两者差值用于耗时。 */
        public Sample { Objects.requireNonNull(run); Objects.requireNonNull(claimedNanos); }

        /** 有真实领取才返回差值，未领取不补零。 */
        public OptionalLong queueNanos() {
            return claimedNanos.isEmpty() ? OptionalLong.empty() : OptionalLong.of(claimedNanos.orElseThrow() - receivedNanos);
        }
    }
}
