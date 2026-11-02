package com.skillforge.institution.media;

import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.attachment.SessionAttachmentBodyMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 未用上传候选按租户/UUID公平续接；每 root 独立事务，避免持 tenant 后再获取下一 owner 锁。 */
@Component
public class SessionMediaCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(SessionMediaCleanup.class);
    private final SessionAttachmentBodyMapper tenants;
    private final SessionMediaReclamation reclamation;
    private String afterTenant = "0";
    private String currentTenant;
    private String afterRoot;
    private long nextPassNanos;

    /** 复用真实租户目录，未启用其它存储设施或新短 TTL。 */
    public SessionMediaCleanup(SessionAttachmentBodyMapper tenants, SessionMediaReclamation reclamation) {
        this.tenants = tenants;
        this.reclamation = reclamation;
    }

    /** 一 tick 只处理一个租户的16个 root；所有新引用与退役的竞争在每 root 事务中解决。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 600_000)
    public synchronized void clean() {
        if (nextPassNanos != 0 && System.nanoTime() - nextPassNanos < 0) return;
        String previous = TenantContext.getTenantId();
        try {
            if (currentTenant == null) {
                var page = tenants.tenantPage(afterTenant, 1);
                if (page.isEmpty()) {
                    afterTenant = "0";
                    nextPassNanos = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                    return;
                }
                currentTenant = page.getFirst();
            }
            TenantContext.setTenantId(currentTenant);
            var page = reclamation.page(currentTenant, afterRoot);
            for (var candidate : page) {
                long started = System.nanoTime();
                try {
                    var retired = reclamation.retire(currentTenant, candidate);
                    if (retired.releasedRows() > 0)
                        LOG.info("未用图片退役 event=unused_media_retirement result={} tenant={} root={} released_rows={} reclaimed_body_rows={} duration_ms={}",
                                retired.result(), currentTenant, candidate.id(), retired.releasedRows(), retired.reclaimedBodies(),
                                (System.nanoTime() - started) / 1_000_000);
                } catch (RuntimeException failure) {
                    LOG.warn("未用图片退役失败 event=unused_media_retirement result=failed tenant={} root={} type={}",
                            currentTenant, candidate.id(), failure.getClass().getSimpleName());
                }
                // 保护/失败的 root 同样推进公平水位，下一完整轮或重启重新核对，不永久跳过。
                afterRoot = candidate.id();
            }
            if (page.size() < SessionMediaReclamation.PAGE_ROOTS) {
                afterTenant = currentTenant;
                currentTenant = null;
                afterRoot = null;
            }
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }
}
