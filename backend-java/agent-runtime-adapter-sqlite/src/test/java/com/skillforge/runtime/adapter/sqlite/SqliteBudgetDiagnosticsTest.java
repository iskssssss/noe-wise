package com.skillforge.runtime.adapter.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 实际SQLite预留事务报告资源维度，不通过后读快照猜测拒绝原因。 */
class SqliteBudgetDiagnosticsTest {
    @TempDir Path directory;

    @Test void resourceDenialsKeepRootRunScopeAndExactDimensionWithoutMutation() {
        try (var store = new SqliteExecutionStore(directory.resolve("budget-diagnostics.db"), Clock.systemUTC())) {
            var budgets = store.budgets();
            var generous = new Resources(8, 8, 8, 8, 100, 100, 200);
            for (boolean rootLimited : List.of(true, false)) for (var dimension : Dimension.values()) {
                var run = new RunRef(new TenantId("desktop-local"), "precise-" + rootLimited + "-" + dimension);
                long[] values = {8, 8, 8, 8, 100, 100, 200};
                values[dimension.ordinal()] = dimension == Dimension.TOTAL_TOKENS ? 1 : 0;
                if (dimension == Dimension.TOTAL_TOKENS) { values[4] = 1; values[5] = 1; }
                var limited = Resources.from(values);
                budgets.register(new Registration(run, run, Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.STANDARD,
                        rootLimited ? limited : generous, limited, limited, 3600, 3600, "diagnostics-v1"))));
                var category = switch (dimension) {
                    case AUXILIARY_MODELS -> Category.AUXILIARY_MODEL;
                    case TOOLS -> Category.TOOL;
                    case KNOWLEDGE -> Category.KNOWLEDGE;
                    default -> Category.BUSINESS_MODEL;
                };
                var before = budgets.snapshot(run);
                var demand = new Demand(run, "attempt-" + run.runId(), "fp-" + run.runId(), category,
                        Resources.call(category, 1, 1), false);
                var denied = assertInstanceOf(Reservation.Denied.class, budgets.reserve(demand));
                assertEquals(rootLimited ? Refusal.ROOT_LIMIT : Refusal.RUN_LIMIT, denied.reason());
                assertEquals(Optional.of(dimension), denied.exhaustedDimension());
                assertEquals(before, budgets.snapshot(run));
            }
        }
    }
}
