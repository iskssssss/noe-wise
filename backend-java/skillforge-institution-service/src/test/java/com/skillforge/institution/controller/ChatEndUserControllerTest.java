package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.application.EndUserService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 对话用户选择只返回当前租户内已有的显式终端用户身份。 */
class ChatEndUserControllerTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listsCurrentTenantEndUsersForExplicitSelection() {
        EndUserService endUsers = mock(EndUserService.class);
        EndUserDO user = new EndUserDO();
        user.setId(91L);
        user.setDisplayName("测试用户");
        user.setStatus("active");
        when(endUsers.listByTenant("7")).thenReturn(List.of(user));
        TenantContext.setTenantId("7");

        var result = new ChatEndUserController(endUsers).endUsers();

        assertEquals(1, result.data().size());
        assertEquals(91L, result.data().getFirst().id());
        assertEquals("测试用户", result.data().getFirst().displayName());
        assertEquals("active", result.data().getFirst().status());
        verify(endUsers).listByTenant("7");
    }

    @Test
    void createsOnlyForAuthenticatedTenant() {
        var endUsers = mock(EndUserService.class);
        var user = new EndUserDO();
        user.setId(92L); user.setDisplayName("测试用户"); user.setStatus("active");
        when(endUsers.create("7", "测试用户")).thenReturn(user);
        TenantContext.setTenantId("7");
        var result = new ChatEndUserController(endUsers).create(
                new ChatEndUserController.CreateEndUserRequest("测试用户"));
        assertEquals(92L, result.data().id());
        verify(endUsers).create("7", "测试用户");
    }
}
