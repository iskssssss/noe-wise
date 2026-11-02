package com.skillforge.institution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.constant.ConnectorConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.application.ConnectorInstanceService;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 校验机构 ToolDeployment 的 Provider 身份与当前可用状态。 */
@Component
public class TenantToolDeploymentProviderValidator {

    private final ConnectorInstanceService connectorInstanceService;
    private final PlatformToolCatalog platformTools;

    public TenantToolDeploymentProviderValidator(ConnectorInstanceService connectorInstanceService, PlatformToolCatalog platformTools) {
        this.connectorInstanceService = Objects.requireNonNull(
                connectorInstanceService, "connectorInstanceService");
        this.platformTools = Objects.requireNonNull(platformTools);
    }

    /**
     * 校验租户创建 Deployment 时提交的 Provider 引用。
     *
     * <p>生产管理只开放 MCP 与 PLATFORM；测试 Provider 只能通过测试注册表装配。
     */
    public void validate(
            Long tenantId,
            ToolDeployment.ProviderType providerType,
            String providerRef,
            ToolDeployment.Environment environment) {
        if (tenantId == null || tenantId <= 0 || providerType == null || environment == null
                || providerRef == null || providerRef.isBlank()) {
            throw invalidProvider();
        }

        String normalizedRef = providerRef.trim();
        switch (providerType) {
            case PLATFORM -> validatePlatform(normalizedRef);
            case CONNECTOR, TEST_MOCK -> throw invalidProvider();
            case MCP -> validateConnector(
                    tenantId, normalizedRef, ConnectorConstants.EXECUTOR_TYPE_MCP);
        }
    }

    private void validatePlatform(String providerRef) {
        if (!platformTools.names().contains(providerRef)) {
            throw invalidProvider();
        }
    }

    private void validateConnector(Long tenantId, String providerRef, String executorType) {
        ConnectorInstanceDO instance = connectorInstanceService.getOne(
                new LambdaQueryWrapper<ConnectorInstanceDO>()
                        .eq(ConnectorInstanceDO::getTenantId, tenantId)
                        .eq(ConnectorInstanceDO::getInstanceId, providerRef)
                        .eq(ConnectorInstanceDO::getStatus, ConnectorConstants.STATUS_ACTIVE)
                        .eq(ConnectorInstanceDO::getExecutorType, executorType)
                        .last("LIMIT 1"));
        if (instance == null) {
            throw invalidProvider();
        }
    }

    private ApiException invalidProvider() {
        return CapabilityCatalogErrors.exception(
                HttpStatus.UNPROCESSABLE_ENTITY,
                CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                "ToolDeployment provider 无效或不可用");
    }
}
