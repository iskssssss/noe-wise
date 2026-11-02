package com.skillforge.institution.scheduler;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.institution.service.HandoffReplyService;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 恢复持久人工回复受理；失败仅重试稳定命令，不重建或猜测会话结果。 */
@Component
public class HandoffReplyWorker {
    private static final Logger log=LoggerFactory.getLogger(HandoffReplyWorker.class);
    private final TenantMapper tenants;
    private final HandoffReplyService replies;
    public HandoffReplyWorker(TenantMapper tenants,HandoffReplyService replies){this.tenants=tenants;this.replies=replies;}
    @Scheduled(fixedDelayString="${skillforge.handoff-reply-delay-ms:2000}",initialDelayString="${skillforge.handoff-reply-delay-ms:2000}")
    public void dispatch() {
        var all=tenants.selectList(null);if(all==null)return;
        for(var tenant:all) {
            TenantContext.setTenantId(Long.toString(tenant.getId()));
            try {
                for(var id:replies.pending(tenant.getId())) {
                    try { replies.dispatch(tenant.getId(),id); }
                    catch(RuntimeException error) { log.warn("人工回复提交暂未完成 tenant={} commandTag={} errorType={}",tenant.getId(),Integer.toHexString(id.hashCode()),error.getClass().getSimpleName()); }
                }
            } finally { TenantContext.clear(); }
        }
    }
}
