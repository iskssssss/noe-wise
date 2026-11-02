package com.skillforge.institution.artifact;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.institution.runtime.InstitutionOutputContract;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 恢复因进程中断遗留的成果发布。多实例可重复扫描，实际写入由 {@link DeliverableService} 的行锁和事务保证幂等。
 */
@Component
public class DeliverablePublicationJob {
    private static final Logger LOG = LoggerFactory.getLogger(DeliverablePublicationJob.class);
    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private final TenantMapper tenants;
    private final DeliverableService deliverables;
    private final InstitutionOutputContract outputs;
    private final ConcurrentMap<String, Long> lastWarnings = new ConcurrentHashMap<>();

    public DeliverablePublicationJob(TenantMapper tenants, DeliverableService deliverables,
            InstitutionOutputContract outputs) {
        this.tenants = tenants;
        this.deliverables = deliverables;
        this.outputs = outputs;
    }

    @Scheduled(fixedDelayString = "${skillforge.deliverable-publication.poll-ms:5000}")
    public void replayPendingPublications() {
        List<TenantDO> all;
        try {
            all = tenants.selectList(null);
        } catch (RuntimeException failure) {
            warnOnce("tenant-scan", "成果待发布租户扫描异常 failureType={}", failure.getClass().getSimpleName(), safeFailure(failure, 0));
            return;
        }
        if (all == null) {
            return;
        }
        for (TenantDO tenant : all) {
            if (tenant == null || tenant.getId() == null) {
                continue;
            }
            String tenantId = tenant.getId().toString();
            TenantContext.setTenantId(tenantId);
            try {
                recover(new TenantId(tenantId),100);
            } catch (RuntimeException failure) {
                warnOnce("tenant:" + tenantId,
                        "成果待发布扫描异常 tenant={} failureType={}", tenantId, failure.getClass().getSimpleName(), safeFailure(failure, 0));
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 定时扫描与正式Worker恢复共用同一原任务发布入口；嵌套调用恢复原租户上下文。 */
    public void recover(TenantId tenant,int limit) {
        String tenantId=tenant.value();
        try(var ignored=DeliverableTenantScope.open(tenantId)) {
            for (RunIdentity run : deliverables.pending(tenantId,limit)) {
                try {
                    outputs.reconcile(run);
                } catch (RuntimeException failure) {
                    // 不记录异常消息或 Run 全量对象，防止异常链意外包含用户原文。
                    warnOnce("run:" + tenantId + ":" + run.runRef().runId(),
                            "成果待发布重放异常 tenant={} executionTag={} failureType={}", tenantId, run.runRef().runId().hashCode(),
                            failure.getClass().getSimpleName(), safeFailure(failure, 0));
                }
            }
        }
    }

    private void warnOnce(String key, String message, Object... arguments) {
        long now = System.currentTimeMillis();
        Long previous = lastWarnings.putIfAbsent(key, now);
        if (previous == null || now - previous >= WARN_INTERVAL_MILLIS && lastWarnings.replace(key, previous, now)) {
            LOG.warn(message, arguments);
        }
    }

    /** 保留调用栈与原因类型，不把文件正文、授权或模型输入带进恢复日志。 */
    private static Throwable safeFailure(Throwable failure, int depth) {
        var safe = new RuntimeException(failure.getClass().getName());
        safe.setStackTrace(failure.getStackTrace());
        if (failure.getCause() != null && depth < 8)
            safe.initCause(safeFailure(failure.getCause(), depth + 1));
        return safe;
    }
}
