package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.application.EndUserService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import org.junit.jupiter.api.Test;

/** 群成员使用真实外部映射，退出清理不依赖用户仍处于active。 */
class EndUserIntegrationIdentityTest {
    @Test void activeMembershipRequiresActiveIdentityButRemovalKeepsExistingDisabledIdentity() {
        var mapper = mock(EndUserMapper.class);
        var user = new EndUserDO();
        user.setId(19L); user.setTenantId(7L); user.setExternalIntegrationId("crm"); user.setStatus("disabled");
        when(mapper.selectOne(any())).thenReturn(user);
        var service = new EndUserService(mapper,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class));
        assertSame(user, service.requireExternal("7", "crm", 19L, false));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireExternal("7", "crm", 19L, true)).getStatus().value());
        user.setStatus("active");
        assertSame(user, service.requireExternal("7", "crm", 19L, true));
        verify(mapper, never()).insert(any(EndUserDO.class));
    }

    @Test void missingMappingIsRejectedWithoutCreatingOrSelectingAnotherIdentity() {
        var mapper = mock(EndUserMapper.class);
        var service = new EndUserService(mapper,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireExternal("7", "crm", 19L, false)).getStatus().value());
        assertThrows(ApiException.class, () -> service.requireExternal("7", "crm", 0L, false));
        verify(mapper, never()).insert(any(EndUserDO.class));
    }
}
