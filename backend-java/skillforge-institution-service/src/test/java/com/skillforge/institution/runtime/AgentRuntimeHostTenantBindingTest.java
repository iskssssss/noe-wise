package com.skillforge.institution.runtime;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.TenantId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeHostTenantBindingTest {
    @Test
    void nestedBindingRestoresOuterTenantOnSuccessAndFailure() {
        var binder = new AgentRuntimeHostConfiguration().agentRuntimeTenantBinder();
        try {
            TenantContext.setTenantId("outer");
            binder.bind(new TenantId("program"), () -> {
                assertEquals("program", TenantContext.getTenantId());
                assertThrows(IllegalStateException.class, () -> binder.bind(new TenantId("child"), () -> {
                    assertEquals("child", TenantContext.getTenantId());
                    throw new IllegalStateException("child failed");
                }));
                assertEquals("program", TenantContext.getTenantId());
            });
            assertEquals("outer", TenantContext.getTenantId());
        } finally {
            TenantContext.clear();
        }
        binder.bind(new TenantId("program"), () -> assertEquals("program", TenantContext.getTenantId()));
        assertNull(TenantContext.getTenantId());
    }
}
