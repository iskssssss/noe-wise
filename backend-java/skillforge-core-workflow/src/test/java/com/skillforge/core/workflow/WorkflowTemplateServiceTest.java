package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.workflow.dto.query.MarketplaceSearchQuery;
import com.skillforge.core.workflow.mapper.WorkflowTemplateMapper;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorkflowTemplateServiceTest {
    @BeforeAll static void metadata() {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), WorkflowTemplateDO.class);
    }

    @Test void searchSelectsOnlyTenResponseColumnsWhileDetailKeepsFullRead() {
        var mapper = mock(WorkflowTemplateMapper.class);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(call -> {
            var page = (Page<WorkflowTemplateDO>) call.getArgument(0);
            var query = (Wrapper<WorkflowTemplateDO>) call.getArgument(1);
            assertEquals(Set.of("workflow_id", "name", "version", "type", "description", "author", "category", "tags", "install_count", "price_type"),
                    Set.of(query.getSqlSelect().split(",")));
            assertTrue(query.getSqlSegment().contains("tenant_id IS NULL"));
            assertTrue(query.getSqlSegment().contains("published_at DESC"));
            assertEquals(2, page.getCurrent()); assertEquals(3, page.getSize());
            page.setTotal(7); page.setRecords(List.of()); return page;
        });
        var service = new WorkflowTemplateService(mapper);
        var result = service.search(new MarketplaceSearchQuery("业务", "rag", "test", "published_at", 2, 3));
        assertEquals(7, result.total()); assertEquals(2, result.page()); assertEquals(3, result.pageSize());
        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(call -> {
            assertNull(((Wrapper<?>) call.getArgument(0)).getSqlSelect());
            var row = new WorkflowTemplateDO(); row.setWorkflowId("flow");
            row.setConfigSchema(List.of(java.util.Map.of("name", "完整配置")));
            row.setRequiredPermissions(List.of("tool.execute")); return row;
        });
        assertEquals("完整配置", service.detail("flow").configSchema().getFirst().get("name"));
        assertEquals(List.of("tool.execute"), service.detail("flow").requiredPermissions());
    }
}
