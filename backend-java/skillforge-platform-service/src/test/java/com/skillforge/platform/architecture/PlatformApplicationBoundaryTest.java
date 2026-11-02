package com.skillforge.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.SkillPackageCatalogService;
import com.skillforge.core.shared.config.AuditMetaObjectHandler;
import com.skillforge.platform.dto.tenant.CreateTenantRequest;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.workflow.WorkflowTemplateService;
import com.skillforge.platform.PlatformApplication;
import com.skillforge.platform.config.PlatformCoreConfiguration;
import com.skillforge.platform.service.PlatformTenantAccountService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** 验证平台运营服务不会把 Agent Runtime 误装配为本地运行时。 */
class PlatformApplicationBoundaryTest {

    @Test
    void scansOnlyPlatformOwnedComponents() {
        SpringBootApplication application = PlatformApplication.class
                .getAnnotation(SpringBootApplication.class);

        assertArrayEquals(new String[] {"com.skillforge.platform"}, application.scanBasePackages());
    }

    @Test
    void importsOnlyExplicitPlatformCoreServices() {
        Import imports = PlatformCoreConfiguration.class.getAnnotation(Import.class);
        Set<Class<?>> imported = Set.of(imports.value());

        assertTrue(imported.contains(AgentTemplateService.class));
        assertTrue(imported.contains(CapabilityCatalogService.class));
        assertTrue(imported.contains(SkillPackageCatalogService.class));
        assertTrue(imported.contains(AuditMetaObjectHandler.class));
        assertTrue(imported.contains(AuditService.class));
        assertTrue(imported.contains(WorkflowTemplateService.class));
    }

    @Test
    void tenantProvisioningIsAtomic() throws NoSuchMethodException {
        Transactional transactional = PlatformTenantAccountService.class
                .getMethod("createTenant", CreateTenantRequest.class)
                .getAnnotation(Transactional.class);

        assertTrue(transactional != null);
    }
}
