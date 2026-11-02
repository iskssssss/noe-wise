package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.workflow.mapper.WorkflowAssetMapper;
import com.skillforge.core.workflow.model.WorkflowAssetDO;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 资源修订与审核互斥，并直接读取数据库最新内容。 */
class WorkflowAssetServiceTest {

    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), WorkflowAssetDO.class);
    }

    @Test
    void upsertReadsCachesListsAndDeletesAssets() {
        WorkflowAssetMapper mapper = mock(WorkflowAssetMapper.class);
        WorkflowAssetService service = new WorkflowAssetService(mapper);
        when(mapper.lockTemplate("flow")).thenReturn(draft(7L));
        byte[] first = "name: first".getBytes();
        byte[] second = "name: second".getBytes();

        when(mapper.selectOne(any())).thenReturn(null);
        service.upsertAssets("7", "flow", Map.of("workflow.yaml", first));
        verify(mapper).upsertBatch(any());

        WorkflowAssetDO stored = new WorkflowAssetDO();
        stored.setWorkflowId("flow");
        stored.setAssetName("workflow.yaml");
        stored.setContent(first);
        when(mapper.selectOne(any())).thenReturn(stored);
        service.upsertAssets("7", "flow", Map.of("workflow.yaml", second));
        verify(mapper, org.mockito.Mockito.times(2)).upsertBatch(any());
        stored.setContent(second);

        when(mapper.selectOne(any())).thenReturn(stored);
        assertArrayEquals(second, service.readAsset("flow", "workflow.yaml"));
        clearInvocations(mapper);
        assertArrayEquals(second, service.readAsset("flow", "workflow.yaml"));
        verify(mapper).selectOne(any());
        assertEquals("name: second", service.readTextAsset("flow", "workflow.yaml"));

        when(mapper.selectList(any())).thenReturn(List.of(stored));
        assertEquals(1, service.listByWorkflow("flow").size());
        clearInvocations(mapper);
        service.deleteByWorkflow("flow");
        verify(mapper, never()).selectList(any());
        verify(mapper).delete(any());
    }

    @Test
    void emptyAndMissingAssetsFailClosedWithoutWriting() {
        WorkflowAssetMapper mapper = mock(WorkflowAssetMapper.class);
        WorkflowAssetService service = new WorkflowAssetService(mapper);

        service.upsertAssets("7", "flow", Map.of());
        service.upsertAssets("7", "flow", null);
        when(mapper.selectOne(any())).thenReturn(null);

        assertNull(service.readAsset("flow", "missing.txt"));
        assertNull(service.readTextAsset("flow", "missing.txt"));
        verify(mapper, never()).upsertBatch(any());
        verify(mapper, never()).updateById(any(WorkflowAssetDO.class));
    }

    @Test
    void platformAssetsAllowANullTenant() {
        WorkflowAssetMapper mapper = mock(WorkflowAssetMapper.class);
        WorkflowAssetService service = new WorkflowAssetService(mapper);
        when(mapper.lockTemplate("platform-flow")).thenReturn(draft(null));
        when(mapper.selectOne(any())).thenReturn(null);

        service.upsertAssets(null, "platform-flow", Map.of("workflow.yaml", "name: platform".getBytes()));

        verify(mapper).upsertBatch(any());
    }

    @Test
    void reviewAndPublishedAssetsCannotChangeUnderTheReviewer() {
        WorkflowAssetMapper mapper = mock(WorkflowAssetMapper.class);
        WorkflowAssetService service = new WorkflowAssetService(mapper);
        for (String status : List.of("review", "published")) {
            WorkflowTemplateDO template = draft(null); template.setStatus(status);
            when(mapper.lockTemplate("flow")).thenReturn(template);
            assertThrows(ApiException.class, () -> service.upsertAssets(null, "flow", Map.of("workflow.yaml", new byte[0])));
            assertThrows(ApiException.class, () -> service.deleteByWorkflow("flow"));
        }
        verify(mapper, never()).upsertBatch(any());
        verify(mapper, never()).delete(any());
    }

    @Test
    void writesAllAssetsWithOneStatementAndRejectsWrongTenantBeforeAnyAssetIo() {
        WorkflowAssetMapper mapper = mock(WorkflowAssetMapper.class);
        WorkflowAssetService service = new WorkflowAssetService(mapper);
        when(mapper.lockTemplate("flow")).thenReturn(draft(7L));
        service.upsertAssets("7", "flow", Map.of("a", new byte[]{1}, "b", new byte[]{2}));
        org.mockito.ArgumentCaptor<List<WorkflowAssetDO>> rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mapper).upsertBatch(rows.capture());
        assertEquals(2, rows.getValue().size());
        assertEquals(List.of("a", "b"), rows.getValue().stream().map(WorkflowAssetDO::getAssetName).sorted().toList());
        verify(mapper, never()).selectOne(any());
        verify(mapper, never()).insert(any(WorkflowAssetDO.class));
        verify(mapper, never()).updateById(any(WorkflowAssetDO.class));
        clearInvocations(mapper);
        assertThrows(ApiException.class, () -> service.upsertAssets("8", "flow", Map.of("a", new byte[]{1})));
        verify(mapper, never()).upsertBatch(any());
    }

    private static WorkflowTemplateDO draft(Long tenantId) {
        WorkflowTemplateDO row = new WorkflowTemplateDO(); row.setId(1L); row.setTenantId(tenantId); row.setStatus("draft"); return row;
    }

}
