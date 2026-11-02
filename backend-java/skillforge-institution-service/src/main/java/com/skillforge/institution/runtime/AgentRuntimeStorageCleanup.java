package com.skillforge.institution.runtime;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorageMaintenance;
import com.skillforge.runtime.api.TenantId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 复用机构后台调度及租户目录执行Runtime到期Owner归档、展示历史与孤儿正文回收。
 * 租户与Owner扫描均有界，归档/事件水位在域内退休事实中持久保存；无变化不更新退休事实。
 */
@Component
public final class AgentRuntimeStorageCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntimeStorageCleanup.class);
    private static final int TENANTS_PER_TICK = 8;
    private static final int BATCHES_PER_TENANT = 2;
    private final RuntimeStorageTenants tenants;
    private final JdbcSessionStorageMaintenance maintenance;
    /** 只保留仍有扫描后续的少量控制位置，正文从不放入调度状态；一轮结束即移除。 */
    private final Map<String, ScanState> scans = new HashMap<>();
    private String afterTenant = "0";
    private boolean passHasBacklog;
    private long nextPassNanos;

    public AgentRuntimeStorageCleanup(RuntimeStorageTenants tenants, JdbcSessionStorageMaintenance maintenance) {
        this.tenants = tenants; this.maintenance = maintenance;
    }

    /** 有积压十秒后续批；无积压完成全部租户后十分钟巡视，不重放模型或UNKNOWN。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 600_000)
    public synchronized void clean() {
        if (nextPassNanos != 0 && System.nanoTime() - nextPassNanos < 0) return;
        String previous = TenantContext.getTenantId();
        try {
            var page = tenants.page(afterTenant, TENANTS_PER_TICK);
            for (String tenant : page) {
                var state = scans.computeIfAbsent(tenant, ignored -> new ScanState());
                try {
                    TenantContext.setTenantId(tenant);
                    boolean more = false;
                    for (int batch = 0; batch < BATCHES_PER_TENANT; batch++) {
                        var retired = maintenance.retireOwners(new TenantId(tenant), 2, 8, state.retirement);
                        var history = maintenance.retireHistory(new TenantId(tenant), 4, 8, state.owner);
                        var bodies = maintenance.collectOrphans(new TenantId(tenant), 32, state.checkpoint, state.event);
                        state.retirement = retired.next();
                        state.owner = history.next(); state.checkpoint = bodies.checkpointNext();
                        state.event = bodies.eventNext();
                        more = retired.moreWork() || history.moreWork() || state.checkpoint != null || state.event != null;
                        if (retired.archivedRows() > 0)
                            LOG.info("Runtime到期Owner归档 tenant={} archived_rows={} hot_retired_bytes={} backlog={}",
                                    tenant,retired.archivedRows(),retired.archivedBytes(),retired.moreWork());
                        if (history.rows() > 0 || bodies.checkpointBodyRows() > 0 || bodies.eventBodyRows() > 0)
                            LOG.info("Runtime存储回收 tenant={} history_rows={} history_bytes={} checkpoint_body_rows={} checkpoint_body_bytes={} event_body_rows={} event_body_bytes={} backlog={}",
                                    tenant,history.rows(),history.bytes(),bodies.checkpointBodyRows(),bodies.checkpointBodyBytes(),
                                    bodies.eventBodyRows(),bodies.eventBodyBytes(),more);
                        var reasons = new java.util.LinkedHashSet<>(retired.protectedReasons());
                        reasons.addAll(history.protectedReasons());
                        var protectedReasons = List.copyOf(reasons);
                        if (!protectedReasons.equals(state.protectedReasons)) {
                            LOG.debug("Runtime历史保护 tenant={} reasons={}",tenant,protectedReasons);
                            state.protectedReasons = protectedReasons;
                        }
                        if (!more) break;
                    }
                    passHasBacklog |= more;
                    if (!more) scans.remove(tenant);
                } catch (RuntimeException failure) {
                    passHasBacklog = true;
                    LOG.warn("Runtime存储回收失败 tenant={} reason={}",tenant,failure.getClass().getSimpleName());
                }
            }
            if (page.size() < TENANTS_PER_TICK) {
                afterTenant = "0";
                if (!passHasBacklog) nextPassNanos = System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
                passHasBacklog = false;
            } else afterTenant = page.getLast();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    /** 单租户的有限页续接位置；Owner内事件水位以数据库退休事实为权威。 */
    private static final class ScanState {
        private JdbcSessionStorageMaintenance.OwnerCursor retirement;
        private JdbcSessionStorageMaintenance.OwnerCursor owner;
        private JdbcSessionStorageMaintenance.Cursor checkpoint;
        private JdbcSessionStorageMaintenance.EventCursor event;
        private List<String> protectedReasons = List.of();
    }
}

/** 机构目录是调度租户来源，按稳定ID分页，避免每轮全量载入全部机构。 */
@Mapper
interface RuntimeStorageTenants {
    List<String> page(@Param("after") String after, @Param("limit") int limit);
}
