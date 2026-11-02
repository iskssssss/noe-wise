package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.core.AgentExecutionLoop.ToolSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentToolProgressTest {
    private static AgentToolProgress.Sample sample(int id, int signature, boolean write) {
        var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:t" + signature), new CapabilityRef(CapabilityType.TOOL, "t" + signature, "v1"),
                "deployment", 1, new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"),
                write ? ToolEffect.IDEMPOTENT_WRITE : ToolEffect.READ_ONLY, true, Duration.ofSeconds(1), true,
                ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), false);
        var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.TEST_MOCK, "server", "tool");
        var sig = new AgentToolProgress.Signature(target, binding, new TypedPayload("application/json", "{}"),
                new AgentToolProgress.Success(new TypedPayload("application/json", "{\"value\":1}")), List.of());
        var source = new ToolSource(new ActionRef(SessionFixtures.TENANT, "action-" + id), "attempt-" + id,
                new ResultRef("request-" + id), new ResultRef("result-" + id));
        return new AgentToolProgress.Sample(sig, source, new ResultRef("model-" + id), id + 1L, 1, !write);
    }
    @Test void allPeriodsWarnAtThreeAndStopAtFiveWithinWindow() {
        for (int period = 1; period <= 4; period++) {
            var samples = new ArrayList<AgentToolProgress.Sample>();
            for (int i = 0; i < period * 5; i++) {
                samples.add(sample(i, i % period, false));
                if (i == period * 3 - 1) {
                    var warning = AgentToolProgress.evaluate(samples, 0);
                    assertEquals(AgentToolProgress.Level.WARN, warning.level()); assertEquals(period, warning.period()); assertEquals(3, warning.repetitions());
                }
            }
            var stop = AgentToolProgress.evaluate(samples, 0);
            assertEquals(AgentToolProgress.Level.STOP, stop.level()); assertEquals(period, stop.period()); assertEquals(5, stop.repetitions());
        }
    }
    @Test void longerStoppingPeriodTakesPrecedenceOverShorterWarning() {
        var samples = new ArrayList<AgentToolProgress.Sample>();
        for (int i = 0; i < 20; i++) samples.add(sample(i, i % 4 == 0 ? 0 : 1, false));
        var decision = AgentToolProgress.evaluate(samples, 0);
        assertEquals(AgentToolProgress.Level.STOP, decision.level()); assertEquals(4, decision.period());
    }
    @Test void privateFailureEvidenceIsNotRenderedInLogs() {
        var failure = new AgentToolProgress.Failure("reason", com.skillforge.runtime.api.port.RetryMode.NONE,
                java.util.Optional.of(new TypedPayload("fixture.failure", "{\"secret\":\"PRIVATE-VALUE\"}")), java.util.Optional.empty());
        assertFalse(failure.toString().contains("PRIVATE-VALUE"));
    }
    @Test void successfulWritesWarnWithoutStoppingAndChangedResultsBreakEvidence() {
        var samples = new ArrayList<AgentToolProgress.Sample>();
        for (int i = 0; i < 5; i++) samples.add(sample(i, 0, true));
        assertEquals(AgentToolProgress.Level.WARN, AgentToolProgress.evaluate(samples, 0).level());
        samples.add(sample(5, 1, true));
        assertEquals(AgentToolProgress.Level.CLEAR, AgentToolProgress.evaluate(samples, 0).level());
    }
}
