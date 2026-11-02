package com.skillforge.institution.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 队列指标保留真实未知、严格上限及关闭边界，不从计时值反推执行成功。 */
class AgentRuntimeExecutionQueueObservationTest {
    private static final TenantId TENANT = new TenantId("1");

    /** 重复wake与重复claim保留第一次，其他租户及scope之外的claim不补造receive。 */
    @Test
    void retainsFirstWakeAndFirstRealClaimOnlyForObservedTenant() {
        var run = new RunRef(TENANT, "observed");
        try (var observation = new AgentRuntimeExecutionQueueObservation(TENANT, 2, ignored -> { })) {
            observation.claimed(new RunRef(TENANT, "pre-existing"), 5);
            observation.received(new RunRef(new TenantId("2"), "other"), 7);
            observation.received(run, 10); observation.received(run, 15);
            assertThat(observation.samples()).hasSize(1);
            assertThat(observation.samples().getFirst().claimedNanos()).isEmpty();
            assertThat(observation.samples().getFirst().queueNanos()).isEmpty();
            observation.claimed(run, 25); observation.claimed(run, 30);
            assertThat(observation.samples().getFirst().receivedNanos()).isEqualTo(10);
            assertThat(observation.samples().getFirst().claimedNanos()).hasValue(25);
            assertThat(observation.samples().getFirst().queueNanos()).hasValue(15);
            assertThat(observation.overflowed()).isFalse();
        }
    }

    /** 达到上限仍能补已有Run的claim，新Run只标溢出而不扩内存或伪造样本。 */
    @Test
    void overflowIsExplicitWhileExistingSamplesCanStillReceiveClaims() {
        var first = new RunRef(TENANT, "first"); var second = new RunRef(TENANT, "second");
        try (var observation = new AgentRuntimeExecutionQueueObservation(TENANT, 1, ignored -> { })) {
            observation.received(first, 10); observation.received(second, 20); observation.claimed(second, 30);
            observation.claimed(first, 40);
            assertThat(observation.overflowed()).isTrue();
            assertThat(observation.samples()).hasSize(1);
            assertThat(observation.samples().getFirst().run()).isEqualTo(first);
            assertThat(observation.samples().getFirst().queueNanos()).hasValue(30);
        }
    }

    /** close解除收集一次并保留最终有界快照，迟到回调不改写报告。 */
    @Test
    void closeReleasesOwnerOnceAndFreezesSamplesAgainstLateCallbacks() {
        var released = new AtomicInteger(); var run = new RunRef(TENANT, "closed");
        var observation = new AgentRuntimeExecutionQueueObservation(TENANT, 1, ignored -> released.incrementAndGet());
        observation.received(run, 10); var before = observation.samples(); observation.close(); observation.close();
        observation.claimed(run, 20); observation.received(new RunRef(TENANT, "late"), 30);
        assertThat(released).hasValue(1); assertThat(observation.samples()).isEqualTo(before);
        assertThat(observation.overflowed()).isFalse();
        assertThatThrownBy(() -> before.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 观察上限明确拒绝非法值，不通过夹紧把截断数据当完整计量。 */
    @Test
    void rejectsInvalidBounds() {
        for (int limit : new int[] {0, -1, 10_001})
            assertThatThrownBy(() -> new AgentRuntimeExecutionQueueObservation(TENANT, limit, ignored -> { }))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("execution_queue_sample_limit_invalid");
    }
}
