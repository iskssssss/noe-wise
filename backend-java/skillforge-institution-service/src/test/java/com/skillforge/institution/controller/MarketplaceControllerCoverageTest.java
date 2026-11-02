package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.constant.MarketplaceConstants;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.workflow.WorkflowTemplateService;
import com.skillforge.core.workflow.dto.query.MarketplaceSearchQuery;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 工作流市场覆盖：平台模板筛选、分页归一化和详情。 */
class MarketplaceControllerCoverageTest {

    @BeforeAll
    static void initTableMetadata() {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), WorkflowTemplateDO.class);
    }

    private com.skillforge.core.workflow.mapper.WorkflowTemplateMapper templates;
    private WorkflowTemplateDO template;
    private MarketplaceController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        templates = mock(com.skillforge.core.workflow.mapper.WorkflowTemplateMapper.class);
        template = template();
        when(templates.selectOne(any(Wrapper.class))).thenReturn(template);
        Page<WorkflowTemplateDO> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(template));
        when(templates.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        controller = new MarketplaceController(new WorkflowTemplateService(templates));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void searchNormalizesFiltersSortAndPageAndMapsResults() {
        assertThat(controller.search(new MarketplaceSearchQuery("booking", "state-machine", "travel",
                MarketplaceConstants.SORT_PUBLISHED_AT, 0, 1000)).data().items()).hasSize(1);
        assertThat(controller.search(new MarketplaceSearchQuery(null, null, null, null, null, null)).data().items())
                .hasSize(1);
        assertThat(controller.getWorkflowDetail("booking").data().name()).isEqualTo("Booking");
    }

    @Test
    void missingPublishedTemplateFailsClosed() {
        when(templates.selectOne(any(Wrapper.class))).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.getWorkflowDetail("missing"))
                .isInstanceOf(Exception.class);
    }

    private WorkflowTemplateDO template() {
        WorkflowTemplateDO value = new WorkflowTemplateDO();
        value.setId(55L);
        value.setWorkflowId("booking");
        value.setName("Booking");
        value.setVersion("1.0.0");
        value.setType("state-machine");
        value.setCategory("travel");
        value.setDescription("booking workflow");
        value.setAuthor("platform");
        value.setAuthorType("platform");
        value.setTags(List.of("travel"));
        value.setPriceType("free");
        value.setPriceCents(0);
        value.setInstallCount(2);
        value.setStatus(WorkflowConstants.STATUS_PUBLISHED);
        value.setPublishedAt(Instant.now());
        return value;
    }

}
