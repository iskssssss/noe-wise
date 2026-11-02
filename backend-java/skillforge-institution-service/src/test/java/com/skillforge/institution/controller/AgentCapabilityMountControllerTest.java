package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.request.SkillMountConfigRequest;
import com.skillforge.core.agent.dto.response.CapabilityMountSetResponse;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.CapabilityMountManagementService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CapabilityMount API 的路径、If-Match 与 typed request 边界测试。 */
class AgentCapabilityMountControllerTest {

    private CapabilityMountManagementService service;
    private AgentCapabilityMountController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        service = mock(CapabilityMountManagementService.class);
        controller = new AgentCapabilityMountController(service);
        when(service.read(7L, 9L)).thenReturn(new CapabilityMountSetResult(7L, 9L, 1L,
                List.of()));
        when(service.create(eq(7L), eq(9L), any(CapabilityMountRequest.class)))
                .thenReturn(new CapabilityMountSetResult(7L, 9L, 1L,
                List.of()));
        when(service.update(anyLong(), anyLong(), anyString(), anyLong(),
                any(CapabilityMountRequest.class))).thenReturn(
                new CapabilityMountSetResult(7L, 9L, 2L, List.of()));
        when(service.delete(any(), any(), anyString(), anyLong())).thenReturn(
                new CapabilityMountSetResult(7L, 9L, 2L, List.of()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void exposesOnlyTheCapabilityMountSetAndUsesParentAgentPath() {
        CapabilityMountSetResponse result = controller.list("9").data();
        assertThat(result.generation()).isEqualTo(1L);
        assertThat(result.mounts()).isEmpty();
        verify(service).read(7L, 9L);
    }

    @Test
    void requiresIfMatchForUpdateAndDeleteAndPassesParsedRevision() {
        CapabilityMountRequest request = new CapabilityMountRequest(
                CapabilityType.SKILL, 100L, 101L,
                new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1000L),
                null, null, null, 0);
        assertThatThrownBy(() -> controller.update("9", "8001", null, request))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> controller.delete("9", "8001", "not-a-revision"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        controller.update("9", "8001", "\"3\"", request);
        verify(service).update(7L, 9L, "8001", 3L, request);
        controller.delete("9", "8001", "W/\"2\"");
        verify(service).delete(7L, 9L, "8001", 2L);
    }

    @Test
    void invalidAgentPathIsNotAResourceAndCreateUsesTypedRequest() {
        CapabilityMountRequest request = new CapabilityMountRequest(
                CapabilityType.SKILL, 100L, 101L,
                new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1000L),
                null, null, null, 0);
        assertThatThrownBy(() -> controller.create("bad", request))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.create("9", request).generation()).isEqualTo(1L);
    }
}
