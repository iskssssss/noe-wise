package com.skillforge.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateListResponse;
import com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.foundation.constant.SecurityConstants;
import com.skillforge.core.foundation.dto.audit.AuditLogQuery;
import com.skillforge.platform.dto.tenant.TenantMemberQuery;
import com.skillforge.platform.dto.tenant.TenantQuery;
import com.skillforge.platform.dto.auth.PlatformLoginRequest;
import com.skillforge.core.foundation.dto.auth.RefreshRequest;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import com.skillforge.core.foundation.application.AuditLogService;
import com.skillforge.core.billing.application.BillingStateService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.platform.service.AgentTemplateAdminService;
import com.skillforge.platform.service.PlatformAuthService;
import com.skillforge.platform.service.PlatformTenantAccountService;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 平台控制面薄适配器覆盖：路由只编排服务，不在控制器复制状态机。 */
class PlatformControllerCoverageTest {

    @BeforeAll
    static void initMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AuditLogDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), CostEventDO.class);
    }

    @Test
    void agentTemplateControllerDelegatesLifecycle() {
        AgentTemplateAdminService service = mock(AgentTemplateAdminService.class);
        var impacts=mock(com.skillforge.platform.service.AgentDefinitionImpactService.class);
        PlatformAgentTemplateController controller = new PlatformAgentTemplateController(service,impacts);
        AgentTemplateQuery query = new AgentTemplateQuery(1, 20, "draft", "support", "助手", null);
        AdminAgentTemplateListResponse list = mock(AdminAgentTemplateListResponse.class);
        AgentTemplateValidationResponse validation = new AgentTemplateValidationResponse(true, List.of());
        when(service.list(query)).thenReturn(list);
        when(service.validate(1L,1L)).thenReturn(validation);
        assertThat(controller.listTemplates(query).data()).isSameAs(list);
        controller.getTemplate(1L);
        assertThat(controller.validateTemplate(new com.skillforge.core.agent.dto.request.AgentDefinitionCommands.DefinitionCommand(1L,1L)).data()).isSameAs(validation);
        controller.createTemplate(null,"create");
        controller.updateTemplateEndpoint(new com.skillforge.core.agent.dto.request.AgentDefinitionCommands.UpdateDefinition(1L,1L,null),"update");
        controller.publishTemplateEndpoint(new com.skillforge.core.agent.dto.request.AgentDefinitionCommands.DefinitionCommand(1L,1L),"publish");
        controller.deprecateTemplateEndpoint(new com.skillforge.core.agent.dto.request.AgentDefinitionCommands.DefinitionCommand(1L,1L),"deprecate");
        controller.restore(new com.skillforge.core.agent.dto.request.AgentDefinitionCommands.RestoreDefinition(1L,1L,"1.0.0"),"restore");
        controller.impact(1L,1L);
        verify(service).detail(1L);verify(service).validate(1L,1L);verify(service).create(null,"create");
        verify(service).update(1L,1L,null,"update");verify(service).publish(1L,1L,"publish");verify(service).deprecate(1L,1L,"deprecate");
        verify(service).restore(1L,1L,"1.0.0","restore");verify(impacts).impact(1L,1L);
    }

    @Test
    void tenantControllerDelegatesAllTenantAndMemberOperations() {
        PlatformTenantAccountService service = mock(PlatformTenantAccountService.class);
        PlatformTenantController controller = new PlatformTenantController(service);
        TenantQuery tenants = new TenantQuery(1, 20, "active", "demo");
        TenantMemberQuery members = new TenantMemberQuery(1, 20, "admin", "active");

        controller.createTenant(null);
        controller.listTenants(tenants);
        controller.getTenant(7L);
        controller.updateTenant(7L, null);
        controller.suspend(7L);
        controller.activate(7L);
        controller.listMembers(7L, members);
        controller.createMember(7L, null);
        controller.updateMember(7L, 8L, null);
        controller.resetPassword(7L, 8L, null);

        verify(service).createTenant(null);
        verify(service).listTenants(tenants);
        verify(service).getTenant(7L);
        verify(service).updateTenant(7L, null);
        verify(service).setTenantStatus(7L, "suspended");
        verify(service).setTenantStatus(7L, "active");
        verify(service).listMembers(7L, members);
        verify(service).createMember(7L, null);
        verify(service).updateMember(7L, 8L, null);
        verify(service).resetPassword(7L, 8L, null);
    }

    @Test
    void authAndBillingControllersDelegateAndNormalizeNumbers() {
        PlatformAuthService auth = mock(PlatformAuthService.class);
        PlatformAuthController authController = new PlatformAuthController(auth,mock(com.skillforge.core.foundation.security.FoundationSecurityService.class));
        authController.login(new PlatformLoginRequest("admin", "secret"));
        authController.refresh(new RefreshRequest("refresh-token"));
        verify(auth).login("admin", "secret");
        verify(auth).refresh("refresh-token");

        var scans = mock(com.skillforge.platform.billing.PlatformBillingScanService.class);
        String operation = java.util.UUID.randomUUID().toString();
        var receipt = new com.skillforge.platform.billing.BillingScanReceipt(operation, 3, 1, 2, java.time.Instant.now());
        when(scans.process(19L, operation)).thenReturn(receipt);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("19");
            var result = new PlatformBillingController(scans).processStates(new PlatformBillingController.ScanCommand(operation));
            assertThat(result.data()).isEqualTo(receipt);
        }
    }

    @Test
    void systemSettingsControllerDelegatesVersionedOperationsWithAuthenticatedActor() {
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        SystemSettingsController controller = new SystemSettingsController(settings);
        Map<String, Object> values = Map.of("standard_root_total_tokens", 100_000_000L);

        try (var stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("19");

            controller.list();
            controller.updateEndpoint(new SystemSettingsController.UpdateCommand("task_budget", new SystemSettingsController.UpdateRequest(3, values)));
            controller.history("task_budget");
            controller.rollbackEndpoint(new SystemSettingsController.RollbackCommand("task_budget", new SystemSettingsController.RollbackRequest(4, 2)));
        }

        verify(settings).list();
        verify(settings).update("task_budget", 3, values, 19);
        verify(settings).history("task_budget");
        verify(settings).rollback("task_budget", 4, 2, 19);
    }

    @Test
    void authControllerReturnsCurrentDatabaseAuthorization() {
        var auth=mock(PlatformAuthService.class);
        var current=new com.skillforge.core.foundation.dto.auth.MeResponse("19",null,"operator",List.of("platform_admin"),List.of("foundation.access.read"),3,"full","71");
        when(auth.me()).thenReturn(current);
        var response=new PlatformAuthController(auth,mock(com.skillforge.core.foundation.security.FoundationSecurityService.class)).me().data();
        assertThat(response).isEqualTo(current);
    }

    @Test
    void auditControllerMapsLogsAndValidatesTenantFilter() {
        var mapper = mock(com.skillforge.core.foundation.persistence.AuditLogMapper.class);
        AuditLogService service = new AuditLogService(mapper);
        PlatformAuditController controller = new PlatformAuditController(service);
        AuditLogDO log = new AuditLogDO();
        log.setId(1L); log.setAction("tenant.create"); log.setMessage("created");
        Page<AuditLogDO> result = new Page<>(2, 20);
        result.setTotal(21); result.setRecords(List.of(log));
        when(mapper.selectLogs(any(), any(), any())).thenReturn(result);
        assertThat(controller.listLogs(new AuditLogQuery("tenant.create", "7", 20, 2, null, null, null, null)).data().total()).isEqualTo(21);
        assertThatThrownBy(() -> controller.listLogs(new AuditLogQuery(null, "bad", null, null, null, null, null, null)))
                .hasMessageContaining("tenant_id");
        com.skillforge.common.context.TenantContext.setTenantId("8");
        try {
            assertThatThrownBy(() -> controller.listLogs(new AuditLogQuery(null, "7", 20, 1, null, null, null, null)))
                    .hasMessageContaining("其他机构");
        } finally { com.skillforge.common.context.TenantContext.clear(); }
    }

    @Test
    void marketplaceControllerDelegatesAuthenticatedDecision() {
        var reviews = mock(com.skillforge.core.workflow.WorkflowReviewService.class);
        var controller = new PlatformMarketplaceController(reviews);
        var revision = java.time.Instant.parse("2026-09-26T00:00:00Z");
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("operator");
            controller.reject(new PlatformMarketplaceController.RejectCommand("wf-1", revision, "缺少说明"));
            verify(reviews).decide("wf-1", revision, false, "缺少说明", "operator");
        }
    }
}
