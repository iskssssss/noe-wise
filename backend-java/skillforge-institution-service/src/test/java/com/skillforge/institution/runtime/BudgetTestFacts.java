package com.skillforge.institution.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 测试所属的显式预算事实；只登记给定或测试实际 register 的 Run，不隐式授权未知任务。 */
public final class BudgetTestFacts {
    private BudgetTestFacts() { }

    public static Snapshot settings(long revision) {
        var values = new LinkedHashMap<String, Object>();
        for (var profile : ChatBudgetSettings.Profile.values()) {
            for (var dimension : ChatBudgetSettings.editableDimensions())
                values.put(profile.field(dimension), profile.defaults().value(dimension));
            if (profile.activeSeconds() > 0) values.put(profile.activeKey(), profile.activeSeconds());
        }
        return new Snapshot(revision, values);
    }

    public static ExecutionBudgetPort.Registration registration(RunRef run) {
        return new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(ChatBudgetSettings.freeze(settings(1), ExecutionBudgetPort.Scenario.STANDARD,
                        ExecutionBudgetPort.Role.PRIMARY, AgentExecutionConfig.inherited())));
    }

    public static ExecutionBudgetPort ledger(ExecutionBudgetPort.Registration... initial) {
        var facts = new ConcurrentHashMap<RunRef, ExecutionBudgetPort.Registration>();
        for (var value : initial) facts.put(value.run(), value);
        var ledger = mock(ExecutionBudgetPort.class);
        when(ledger.find(any())).thenAnswer(call -> Optional.ofNullable(facts.get(call.getArgument(0, RunRef.class))));
        when(ledger.register(any())).thenAnswer(call -> {
            var next = call.getArgument(0, ExecutionBudgetPort.Registration.class);
            var previous = facts.putIfAbsent(next.run(), next);
            if (previous != null && !previous.equals(next)) throw new IllegalStateException("budget_test_registration_conflict");
            return previous == null ? next : previous;
        });
        when(ledger.snapshot(any())).thenAnswer(call -> {
            var run = call.getArgument(0, RunRef.class);
            var registration = Optional.ofNullable(facts.get(run)).orElseThrow();
            return new ExecutionBudgetPort.Snapshot(registration, ExecutionBudgetPort.Resources.ZERO,
                    ExecutionBudgetPort.Resources.ZERO, ExecutionBudgetPort.Resources.ZERO, ExecutionBudgetPort.Resources.ZERO,
                    ExecutionBudgetPort.Closing.ZERO, ExecutionBudgetPort.Phase.BUSINESS, 0, 0, 0);
        });
        return ledger;
    }
}
