package com.skillforge.core.billing.metering;

import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 异步任务从账本恢复来源，不继承线程残留身份；结束后恢复调用方作用域。 */
class ControlOperationMeteringScopeTest {
    @Test
    void frozenOperationOwnsIsolatedUsageAndClosingRestoresCaller() {
        var operation = new ControlOperation("operation", "root", "7", "integration", "publish",
                ControlOperationStatus.PROCESSING, "idem", "fingerprint", List.of(), Instant.now(), null);
        var caller = CostContext.builder().requestId("unrelated-request").sessionId("unrelated-session").build();
        try (var outer = MeterScope.open(caller)) {
            MeterScope.setLastEventId(42L);
            try (var scope = new ControlOperationMeteringScope().open(operation)) {
                assertEquals("root", MeterScope.current().requestId());
                assertEquals("operation", MeterScope.current().scopeId());
                assertEquals("control_operation", MeterScope.current().scopeType());
                assertEquals("control.operation", MeterScope.current().callPoint());
                assertNull(MeterScope.current().sessionId());
                assertNull(MeterScope.parentEventId());
            }
            assertEquals("unrelated-request", MeterScope.current().requestId());
            assertEquals("unrelated-session", MeterScope.current().sessionId());
        }
        assertNull(MeterScope.current());
    }
}
