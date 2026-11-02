package com.skillforge.institution.attachment;

import com.skillforge.common.context.TenantContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 到期立即禁读；清理有积压时快速续批，空闲时恢复十分钟巡视，租户扫描使用稳定主键水位。 */
@Component
public class SessionAttachmentCleanup {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SessionAttachmentCleanup.class);
    private static final int TENANTS_PER_TICK = 32;
    private static final int BATCHES_PER_TENANT = 8;
    private final SessionAttachmentBodyMapper tenants;
    private final SessionAttachmentReclamation reclamation;
    private String afterTenant = "0";
    private boolean passHasBacklog;
    private long nextPassNanos;
    public SessionAttachmentCleanup(SessionAttachmentBodyMapper tenants, SessionAttachmentReclamation reclamation) {
        this.tenants = tenants; this.reclamation = reclamation;
    }
    @Scheduled(fixedDelay = 10_000, initialDelay = 600_000)
    public synchronized void clean() {
        if (nextPassNanos != 0 && System.nanoTime() - nextPassNanos < 0) return;
        String previous = TenantContext.getTenantId();
        try {
            var page = tenants.tenantPage(afterTenant, TENANTS_PER_TICK);
            for (var tenant : page) {
                long started = System.nanoTime();
                try {
                    TenantContext.setTenantId(tenant);
                    SessionAttachmentReclamation.BatchResult result = null;
                    int released = 0, reclaimed = 0, batches = 0;
                    do {
                        result = reclamation.expiredBatch(tenant);
                        released += result.released(); reclaimed += result.reclaimed(); batches++;
                    } while (batches < BATCHES_PER_TENANT && result.moreWork());
                    boolean remaining = result.moreWork();
                    passHasBacklog |= remaining;
                    if (released > 0 || reclaimed > 0 || remaining)
                        LOG.info("会话附件回收 event=attachment_reclamation result={} duration_ms={} tenant={} batches={} released_rows={} reclaimed_body_rows={} backlog_sample_rows={} backlog_sample_bytes={} backlog_saturated={} oldest_expiry={}",
                                remaining ? "backlogged" : "drained", (System.nanoTime()-started)/1_000_000, tenant, batches, released, reclaimed,
                                result.backlog().sampledRows(), result.backlog().sampledBytes(),
                                result.backlog().saturated(), result.backlog().oldestExpiry());
                } catch (RuntimeException failure) {
                    passHasBacklog = true;
                    LOG.warn("会话附件清理失败 event=attachment_reclamation result=failed duration_ms={} tenant={} type={}", (System.nanoTime()-started)/1_000_000, tenant, failure.getClass().getSimpleName());
                }
                afterTenant = tenant;
            }
            if (page.size() < TENANTS_PER_TICK) {
                afterTenant = "0";
                nextPassNanos = passHasBacklog ? 0 : System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
                passHasBacklog = false;
            }
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }
}
