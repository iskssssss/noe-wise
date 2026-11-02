package com.skillforge.platform.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.workflow.WorkflowReviewService;
import com.skillforge.core.workflow.WorkflowSecurityScanner;
import com.skillforge.core.workflow.mapper.WorkflowReviewMapper;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import com.skillforge.core.foundation.access.RequirePermission;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowReviewContractTest {
    @Test void pricingCandidatesRequireBillingCreationInsteadOfModelReading() throws Exception {
        var endpoint = PlatformModelController.class.getMethod("pricingOptions");
        assertThat(endpoint.getAnnotation(RequirePermission.class).value()).isEqualTo("foundation.billing.create");
    }
    @Test
    void detailReturnsExactPackageRevisionWithoutScanningOrWriting() throws Exception {
        var mapper = mock(WorkflowReviewMapper.class);
        var scanner = mock(WorkflowSecurityScanner.class);
        var service = new WorkflowReviewService(mapper, scanner);
        var revision = Instant.parse("2026-10-24T01:02:03.123456Z");
        var template = new WorkflowTemplateDO();
        template.setWorkflowId("wf"); template.setName("待审流程"); template.setVersion("1.0.0");
        template.setStatus("review"); template.setUpdatedAt(revision); template.setTenantId(7L);
        when(mapper.detailTemplate("wf")).thenReturn(template);
        when(mapper.detailAssets("wf", 7L)).thenReturn(List.of(
                new WorkflowReviewMapper.ReviewAsset("workflow.yaml", "nodes: []\n".getBytes(StandardCharsets.UTF_8)),
                new WorkflowReviewMapper.ReviewAsset("icon.png", new byte[] {1, 2, 3})));
        var result = service.detail("wf", revision);
        assertThat(result.updatedAt()).isEqualTo(revision);
        assertThat(result.files().getFirst().content()).isEqualTo("nodes: []\n");
        assertThat(result.files().get(1).content()).isNull();
        assertThat(result.files().get(1).sizeBytes()).isEqualTo(3);
        verifyNoInteractions(scanner);
        verify(mapper, never()).decide(any(), any(), any());
        verify(mapper, never()).appendDecision(any(), any(), any(), any(), any(), any());
        var endpoint = PlatformMarketplaceController.class.getMethod("reviewDetail", String.class, Instant.class);
        assertThat(endpoint.getAnnotation(RequirePermission.class).value()).isEqualTo("foundation.marketplace.read");
    }

    @Test
    void staleDetailAndInstitutionContextCannotReadPackage() {
        var mapper = mock(WorkflowReviewMapper.class);
        var service = new WorkflowReviewService(mapper, mock(WorkflowSecurityScanner.class));
        var revision = Instant.parse("2026-10-24T01:02:03Z");
        var template = new WorkflowTemplateDO(); template.setUpdatedAt(revision);
        when(mapper.detailTemplate("wf")).thenReturn(template);
        assertThatThrownBy(() -> service.detail("wf", revision.minusNanos(1))).hasMessageContaining("内容已变化");
        verify(mapper, never()).detailAssets(any(), any());
        clearInvocations(mapper);
        TenantContext.setTenantId("7");
        try {
            assertThatThrownBy(() -> service.detail("wf", revision)).hasMessageContaining("机构上下文");
            verifyNoInteractions(mapper);
        } finally { TenantContext.clear(); }
    }
    @Test
    void staleRevisionAndUnsafePackageDoNotWriteDecision() {
        var mapper = mock(WorkflowReviewMapper.class);
        var scanner = mock(WorkflowSecurityScanner.class);
        var service = new WorkflowReviewService(mapper, scanner);
        Instant revision = Instant.parse("2026-09-26T00:00:00Z");
        when(mapper.lockTemplate("wf")).thenReturn(new WorkflowReviewMapper.TemplateReview(1L, "wf", null, "1.0", "review", revision));
        assertThatThrownBy(() -> service.decide("wf", revision.minusSeconds(1), true, null, "actor")).hasMessageContaining("已变化");
        verifyNoInteractions(scanner);
        when(scanner.scan("wf")).thenReturn(new com.skillforge.core.workflow.WorkflowSecurityScanner.ScanResult(false, List.of("unsafe"), null));
        assertThatThrownBy(() -> service.decide("wf", revision, true, null, "actor")).hasMessageContaining("未通过");
        verify(mapper, never()).decide(any(), any(), any());
        verify(mapper, never()).appendDecision(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectRequiresReasonAndRejectsTenantContextBeforeAccess() {
        var mapper = mock(WorkflowReviewMapper.class);
        var service = new WorkflowReviewService(mapper, mock(WorkflowSecurityScanner.class));
        assertThatThrownBy(() -> service.decide("wf", Instant.now(), false, "  ", "actor")).hasMessageContaining("理由");
        verify(mapper, never()).lockTemplate(any());
        clearInvocations(mapper);
        TenantContext.setTenantId("7");
        try {
            assertThatThrownBy(() -> service.list("pending", "", 1, 20)).hasMessageContaining("机构上下文");
            verifyNoInteractions(mapper);
        } finally { TenantContext.clear(); }
    }
}
