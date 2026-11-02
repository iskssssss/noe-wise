package com.skillforge.institution.memory;

import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.memory.MemoryHistoryRetentionMapper.Revision;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 按已批准30天政策轮转被替代状态历史；保护判断由权威数据库完成，终态不解除幂等引用。 */
@Service
public final class MemoryHistoryRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryHistoryRetentionWorker.class);
    /** 单事务行数和每租户每轮总页数均有上限，扫描游标跨轮继续。 */
    private static final int BATCH_SIZE = 200;
    private static final int MAX_PAGES = 10;
    private final MemoryHistoryRetentionMapper mapper;
    private final TransactionTemplate transactions;
    private final ActiveMemoryTenantScan tenants;
    private final Duration retention;
    /** 只保存PK位置；重启重扫，候选仍须重新通过所有者和引用核验。 */
    private final Map<String, Revision> cursors = new HashMap<>();

    /** 配置可延长保留，不能缩短已批准30天保护；缺省30天且后台任务实际启用。 */
    public MemoryHistoryRetentionWorker(MemoryHistoryRetentionMapper mapper, PlatformTransactionManager manager,
            ActiveMemoryTenantMapper tenantMapper,
            @Value("${skillforge.memory.superseded-history-retention-days:30}") int retentionDays) {
        if (retentionDays < 30) throw new IllegalArgumentException("memory_history_retention_below_approved_days");
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.tenants = new ActiveMemoryTenantScan(tenantMapper);
        this.retention = Duration.ofDays(retentionDays);
    }

    /** 仅当前租户绑定内扫描；错误保留游标以便重试，其他租户继续。 */
    @Scheduled(fixedDelayString = "${skillforge.memory.history-retention-poll-interval:3600000}")
    public void poll() {
        for (String tenant : tenants.next()) {
            String previous = TenantContext.getTenantId();
            TenantContext.setTenantId(tenant);
            try {
                Instant cutoff = transactions.execute(ignored -> mapper.cutoff(retention.toDays()));
                int scanned = 0;
                int deleted = 0;
                for (int page = 0; page < MAX_PAGES; page++) {
                    Revision after = cursors.getOrDefault(tenant, new Revision("", 0));
                    Batch batch = collect(tenant, cutoff, after);
                    scanned += batch.scanned();
                    deleted += batch.deleted();
                    if (batch.last() != null) cursors.put(tenant, batch.last());
                    if (batch.scanned() < BATCH_SIZE) {
                        cursors.remove(tenant);
                        break;
                    }
                }
                log.debug("记忆历史回收完成 tenant={} scanned={} deleted={} continuation={}",
                        tenant, scanned, deleted, cursors.containsKey(tenant));
            } catch (RuntimeException failure) {
                log.warn("记忆历史回收失败 tenant={} type={}", tenant, failure.getClass().getSimpleName());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
            }
        }
    }

    /** head与候选状态行在同一租户事务锁定；删除新快照及触发器仍逐项核验真实保护引用。 */
    private Batch collect(String tenant, Instant cutoff, Revision after) {
        return transactions.execute(ignored -> {
            var revisions = mapper.candidates(tenant, cutoff, after.factId(), after.revision(), BATCH_SIZE);
            if (revisions.isEmpty()) return new Batch(0, 0, null);
            mapper.bindCutoff(cutoff);
            int deleted = mapper.deleteUnprotected(tenant, cutoff, revisions);
            return new Batch(revisions.size(), deleted, revisions.getLast());
        });
    }

    /** 数据库实测行数与下一轮位置；不把候选数等同删除行数。 */
    private record Batch(int scanned, int deleted, Revision last) { }
}
