package com.skillforge.institution.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.execution.WorkspaceBinding;
import com.skillforge.execution.WorkspacePolicy;
import org.junit.jupiter.api.Test;

/** 工作区归属必须由可信Run固定，删除墓碑和会话关闭不能被延迟创建绕过。 */
class SessionWorkspaceRegistryTest {
    private final WorkspaceBinding binding=new WorkspaceBinding("a".repeat(64),"1","delegation:child",9,"parent",
            new WorkspacePolicy(1,300,24,10));

    @Test void closedProductNeverRegistersWorkspace() {
        var mapper=mock(SessionWorkspaceMapper.class);
        when(mapper.productActive("1","parent",9)).thenReturn(false);
        assertThrows(SecurityException.class,()->new SessionWorkspaceRegistry(mapper).requireActive(binding,"b".repeat(64)));
        verify(mapper,never()).insert(any());
    }

    @Test void deletedWorkspaceTombstoneRejectsLateExecution() {
        var mapper=mock(SessionWorkspaceMapper.class);
        when(mapper.productActive("1","parent",9)).thenReturn(true);
        when(mapper.find("1","delegation:child")).thenReturn(new SessionWorkspaceMapper.Row(binding.workspaceId(),"1","delegation:child","parent",9,"complete","b".repeat(64),true));
        assertThrows(SecurityException.class,()->new SessionWorkspaceRegistry(mapper).requireActive(binding,"b".repeat(64)));
    }

    @Test void mismatchedProductIdentityCannotReuseChildWorkspace() {
        var mapper=mock(SessionWorkspaceMapper.class);
        when(mapper.productActive("1","parent",9)).thenReturn(true);
        when(mapper.find("1","delegation:child")).thenReturn(new SessionWorkspaceMapper.Row(binding.workspaceId(),"1","delegation:child","other-parent",9,"active","b".repeat(64),true));
        assertThrows(SecurityException.class,()->new SessionWorkspaceRegistry(mapper).requireActive(binding,"b".repeat(64)));
    }

    @Test void initializedWorkspaceNeverGetsEmptyCreationPermissionAgain() {
        var mapper=mock(SessionWorkspaceMapper.class);String operation="b".repeat(64);
        when(mapper.productActive("1","parent",9)).thenReturn(true);
        when(mapper.find("1","delegation:child")).thenReturn(new SessionWorkspaceMapper.Row(binding.workspaceId(),"1","delegation:child","parent",9,"active",operation,false));
        var registry=new SessionWorkspaceRegistry(mapper);
        assertTrue(registry.requireActive(binding,operation));
        assertThrows(SecurityException.class,()->registry.requireActive(binding,"c".repeat(64)),"其他调用不能抢占尚未确认的初始化");
        when(mapper.find("1","delegation:child")).thenReturn(new SessionWorkspaceMapper.Row(binding.workspaceId(),"1","delegation:child","parent",9,"active",operation,true));
        registry.confirmInitialization(binding,operation);
        assertFalse(registry.requireActive(binding,operation),"原调用再次授权也不能重新空建，import二次forCode仍可用");
        assertFalse(registry.requireActive(binding,"c".repeat(64)),"新调用同样禁止空建");
    }
}
