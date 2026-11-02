package com.skillforge.platform.config;

import com.skillforge.core.billing.export.PlatformUsagePersistenceConfiguration;
import com.skillforge.core.billing.export.PlatformUsagePublisher;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import com.skillforge.core.billing.export.PlatformUsageService;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 平台宿主显式装配共享用量来源与发布业务；机构宿主通过 core 扫描装配。 */
@Configuration
@Import({PlatformUsagePersistenceConfiguration.class,PlatformUsagePublisher.class,
        PlatformUsageRecorder.class,PlatformUsageService.class,IntegrationProvenanceService.class})
public class PlatformUsageConfiguration { }
