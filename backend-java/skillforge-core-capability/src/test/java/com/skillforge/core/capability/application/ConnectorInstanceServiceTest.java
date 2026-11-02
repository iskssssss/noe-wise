package com.skillforge.core.capability.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ConnectorInstanceMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/** 删除规则单测；事务、真实外键与并发引用由 PostgreSQL 集成测试验证。 */
class ConnectorInstanceServiceTest {
    private final ConnectorInstanceMapper instances = mock(ConnectorInstanceMapper.class);
    private final ToolDeploymentRecordMapper deployments = mock(ToolDeploymentRecordMapper.class);
    private final ConnectorInstanceService service = new ConnectorInstanceService(instances, deployments);

    @BeforeAll
    static void initializeMetadata() {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        var assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ConnectorInstanceDO.class);
        TableInfoHelper.initTableInfo(assistant, ToolDeploymentRecordDO.class);
    }

    @BeforeEach
    void setupInstance() {
        var instance = new ConnectorInstanceDO();
        instance.setId(21L);
        instance.setTenantId(7L);
        instance.setInstanceId("inst-1");
        when(instances.selectOne(any(Wrapper.class), eq(true))).thenReturn(instance);
        when(deployments.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(instances.deleteById(21L)).thenReturn(1);
    }

    @Test
    void unusedInstanceDeletesOnlyAfterTenantScopedLockAndReferenceCount() {
        service.deleteUnused(7L, "inst-1");

        ArgumentCaptor<Wrapper<ConnectorInstanceDO>> instanceQuery = wrapperCaptor();
        verify(instances).selectOne(instanceQuery.capture(), eq(true));
        assertThat(instanceQuery.getValue().getSqlSegment())
                .contains("tenant_id", "instance_id", "LIMIT 1 FOR UPDATE");
        assertThat(parameters(instanceQuery.getValue()))
                .containsExactlyInAnyOrder(7L, "inst-1");
        ArgumentCaptor<Wrapper<ToolDeploymentRecordDO>> deploymentQuery = wrapperCaptor();
        verify(deployments).selectCount(deploymentQuery.capture());
        assertThat(deploymentQuery.getValue().getSqlSegment())
                .contains("tenant_id", "provider_ref", "provider_type");
        assertThat(parameters(deploymentQuery.getValue()))
                .containsExactlyInAnyOrder(7L, "inst-1", "MCP", "CONNECTOR");
        verify(instances).deleteById(21L);
    }

    @Test
    void disabledDeploymentStillBlocksDeletion() {
        when(deployments.selectCount(any(Wrapper.class))).thenReturn(1L);

        var failure = assertThrows(ApiException.class, () -> service.deleteUnused(7L, "inst-1"));

        assertReferenced(failure);
        ArgumentCaptor<Wrapper<ToolDeploymentRecordDO>> query = wrapperCaptor();
        verify(deployments).selectCount(query.capture());
        assertThat(query.getValue().getSqlSegment()).doesNotContain("status");
        verify(instances, never()).deleteById(anyLong());
    }

    @Test
    void racingForeignKeyReferenceReturnsStableConflictWithoutLeakingDatabaseError() {
        when(instances.deleteById(21L))
                .thenThrow(new DataIntegrityViolationException("private database detail"));

        var failure = assertThrows(ApiException.class, () -> service.deleteUnused(7L, "inst-1"));

        assertReferenced(failure);
        assertThat(failure.getMessage()).doesNotContain("private database detail");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void foreignTenantInstanceIsNotFoundBeforeAnyReferenceQueryOrDelete() {
        when(instances.selectOne(any(Wrapper.class), eq(true))).thenReturn(null);

        var failure = assertThrows(ApiException.class, () -> service.deleteUnused(8L, "inst-1"));

        assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        ArgumentCaptor<Wrapper<ConnectorInstanceDO>> query = wrapperCaptor();
        verify(instances).selectOne(query.capture(), eq(true));
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id", "instance_id");
        assertThat(parameters(query.getValue())).containsExactlyInAnyOrder(8L, "inst-1");
        verifyNoInteractions(deployments);
        verify(instances, never()).deleteById(anyLong());
    }

    private void assertReferenced(ApiException failure) {
        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure.getCode()).isEqualTo("CONNECTOR_REFERENCED");
        assertThat(failure.isRetryable()).isFalse();
    }

    private java.util.Collection<Object> parameters(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) Wrapper.class);
    }
}
