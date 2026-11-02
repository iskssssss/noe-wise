package com.skillforge.core.capability.connector;

import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 将平台默认工具发布为 platform-scope 不可变 TOOL 版本。
 */
@Component
public class PlatformToolDefinitionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformToolDefinitionInitializer.class);

    private final CapabilityProducerPublicationBridge publicationBridge;
    private final PlatformToolCatalog catalog;

    public PlatformToolDefinitionInitializer(CapabilityProducerPublicationBridge publicationBridge, PlatformToolCatalog catalog) {
        this.publicationBridge = publicationBridge;
        this.catalog = catalog;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        for (PlatformToolCatalog.Entry entry : catalog.entries()) {
            CapabilityProducerPublicationRequest publication = new CapabilityProducerPublicationRequest(
                    CapabilityCatalogScope.platform(),
                    CapabilityType.TOOL,
                    "tool:" + entry.name(),
                    entry.publishedVersionKey(),
                    entry.name(),
                    entry.description(),
                    entry.contentHash(),
                    entry.descriptor());
            publishOnceAfterCatalogRace(publication);
            log.info("平台默认工具已发布 name={}", entry.name());
        }
    }

    /**
     * 两个宿主首次并发启动时可能同时观察到目录项不存在，其中一个事务会以稳定的
     * DUPLICATE/CONFLICT 回滚。事务代理返回后仅重试一次相同发布，由桥接层重新读取
     * 已提交版本并严格校验状态与 contentHash；第二次失败原样传播。
     */
    private void publishOnceAfterCatalogRace(CapabilityProducerPublicationRequest publication) {
        try {
            publicationBridge.publish(publication);
        } catch (ApiException exception) {
            if (!isCatalogRace(exception)) {
                throw exception;
            }
            publicationBridge.publish(publication);
        }
    }

    private boolean isCatalogRace(ApiException exception) {
        return CapabilityCatalogErrors.DUPLICATE.equals(exception.getCode())
                || CapabilityCatalogErrors.CONFLICT.equals(exception.getCode());
    }
}
