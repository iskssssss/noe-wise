package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.application.EndUserService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import org.junit.jupiter.api.Test;

/** 外部 identity 并发创建只登记一次；停用记录不能通过 ensure 重建。 */
class ExternalEndUserServiceTest {
    @Test void registrationReadsCommittedWinnerAfterConcurrentConflict() {
        var mapper=mock(EndUserMapper.class);var winner=new EndUserDO();winner.setId(42L);winner.setStatus("active");
        when(mapper.selectOne(any())).thenReturn(null,winner);when(mapper.insertExternal(any())).thenReturn(0);
        assertSame(winner,new EndUserService(mapper,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class)).ensureExternal("7","crm","user","用户"));
        verify(mapper).insertExternal(argThat(row->row.getTenantId()==7L&&"crm".equals(row.getExternalIntegrationId())
                &&"user".equals(row.getExternalUserRef())&&"active".equals(row.getStatus())));
    }
    @Test void existingRegistrationPreservesStableIdentity() {
        var mapper=mock(EndUserMapper.class);var existing=new EndUserDO();existing.setId(42L);existing.setStatus("active");
        when(mapper.selectOne(any())).thenReturn(existing);
        assertEquals(42L,new EndUserService(mapper,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class)).ensureExternal("7","crm","user","新名字").getId());
        verify(mapper,never()).insertExternal(any());
    }
    @Test void deletedIdentityIsNotRecreated() {
        var mapper=mock(EndUserMapper.class);var existing=new EndUserDO();existing.setStatus("deleted");
        when(mapper.selectOne(any())).thenReturn(existing);
        assertThrows(RuntimeException.class,()->new EndUserService(mapper,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class)).ensureExternal("7","crm","user","用户"));
        verify(mapper,never()).insertExternal(any());
    }
}
