package com.skillforge.institution.artifact;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 到期判定在读取路径立即生效，物理清理失败继续保持禁读并在下周期重试。 */
@Component
public class ArtifactCleanup {
    private static final Logger LOG=LoggerFactory.getLogger(ArtifactCleanup.class);
    private final ArtifactStorage storage;private final TenantMapper tenants;
    public ArtifactCleanup(ArtifactStorage storage,TenantMapper tenants){this.storage=storage;this.tenants=tenants;}
    @Scheduled(fixedDelay=3_600_000,initialDelay=3_600_000)
    public void clean(){
        for(var tenant:tenants.selectList(null))try {
            TenantContext.setTenantId(tenant.getId().toString());storage.cleanup(tenant.getId().toString());storage.cleanupOrphans(tenant.getId().toString());
        }catch(Exception failure){io.micrometer.core.instrument.Metrics.counter("skillforge.deliverable.cleanup.failures").increment();LOG.error("产物清理失败 tenant={}",tenant.getId(),failure);}finally{TenantContext.clear();}
    }
}
