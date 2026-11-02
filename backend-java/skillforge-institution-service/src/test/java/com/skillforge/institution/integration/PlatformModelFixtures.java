package com.skillforge.institution.integration;

import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;

/** 集成测试的平台模型目录夹具。 */
final class PlatformModelFixtures {

    private PlatformModelFixtures() {
    }

    static String selectLlm(
            PlatformModelCatalogService catalog,
            String tenantId,
            String endpoint,
            String modelId,
            String apiKey,
            String providerId,
            String serviceId,
            String profileVersion) {
        var created = catalog.create(new ModelWrite(
                null, "Runtime fixture", "LLM", true,
                providerId, serviceId, profileVersion,
                "openai-chat-completions", "bearer-api-key",
                endpoint, modelId, apiKey,
                4096, 32768, 1024, 0.2, "DISABLED", 30,
                null, null, null, null, null, "CNY"), "integration-fixture");
        catalog.publish(created.id(), created.revision(), "integration-fixture");
        catalog.select(tenantId, new ModelSelections(created.id(), null, null));
        return created.id();
    }
}
