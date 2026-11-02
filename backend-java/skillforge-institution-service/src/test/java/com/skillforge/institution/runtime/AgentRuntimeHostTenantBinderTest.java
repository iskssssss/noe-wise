package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 生产 worker 必须真正进入租户上下文，不能把 bind 做成空操作。 */
class AgentRuntimeHostTenantBinderTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void bindSetsTenantContextDuringWorkAndClearsAfterwards() {
        var binder = new AgentRuntimeHostConfiguration().agentRuntimeTenantBinder();
        String[] seen = new String[1];
        binder.bind(new TenantId("9"), () -> seen[0] = TenantContext.getTenantId());
        assertEquals("9", seen[0]);
        assertNull(TenantContext.getTenantId());
    }
}
