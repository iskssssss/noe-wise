package com.skillforge.institution.memory;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.memory.MemoryDecayMapper.Due;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 重建可解释的时间衰减投影，并以 Runtime 状态迁移隐藏已衰减事实。 */
@Service
public final class MemoryDecayWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryDecayWorker.class);
    /** 每页投影及迁移上限；后续轮次继续积压，避免单页事务无限增长。 */
    static final int BATCH_SIZE = 200;
    /** 单租户单轮总工作量上限；每页独立事务，积压下一轮沿PK位置继续。 */
    private static final int MAX_PAGES = 10;
    /** 扫描游标只控制轮转；重启安全重扫，由权威状态及 revision CAS 保证幂等。 */
    private final Map<String, String> dueCursors = new HashMap<>();
    /** 活跃投影独立主键游标；分值不变仍推进位置，不能反复停在已满分记录上。 */
    private final Map<String, String> projectionCursors = new HashMap<>();
    private final MemoryDecayMapper mapper;
    private final TransactionTemplate transactions;
    private final MemoryGovernanceService governance;
    private final ActiveMemoryTenantScan tenants;

    public MemoryDecayWorker(MemoryDecayMapper mapper, PlatformTransactionManager transactionManager,
            MemoryGovernanceService governance, ActiveMemoryTenantMapper tenantMapper) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.governance = governance;
        this.tenants = new ActiveMemoryTenantScan(tenantMapper);
    }

    @Scheduled(fixedDelayString = "${skillforge.memory.decay-poll-interval:3600000}")
    public void poll() {
        for (String tenant : tenants.next()) {
            String previous = TenantContext.getTenantId();
            TenantContext.setTenantId(tenant);
            try {
                boolean projectionDone = false;
                boolean dueDone = false;
                for (int page = 0; page < MAX_PAGES; page++) {
                    DecayBatch batch = project(tenant, !projectionDone, !dueDone);
                    for (Due due : batch.due()) {
                        try {
                            // 阈值重验及 projection 锁由装配的同连接 Guard 在 Runtime head 锁之后执行。
                            governance.transition(tenant, due.factId(), due.revision(),
                                    com.skillforge.runtime.api.FactStatus.DECAYED,
                                    MemoryDecayTransitionGuard.TIME_DECAY_REASON, "memory-decay:" + due.factId()
                                            + ":" + due.revision());
                        } catch (RuntimeException failure) {
                            // 失败事实下一圈重试；本轮扫描完成后不再次绕回失败项。
                            log.warn("记忆到期迁移失败 tenant={} revision={} type={}", tenant,
                                    due.revision(), failure.getClass().getSimpleName());
                        }
                    }
                    projectionDone |= !batch.projectionMore();
                    dueDone |= !batch.dueMore();
                    if (projectionDone && dueDone) break;
                }
            } catch (RuntimeException failure) {
                log.warn("记忆衰减调度失败 tenant={} type={}", tenant,
                        failure.getClass().getSimpleName());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
            }
        }
    }

    /** 两个扫描各自只在本轮绕一圈；游标仅在事务成功提交后推进。 */
    private DecayBatch project(String tenant, boolean project, boolean expire) {
        DecayBatch result = transactions.execute(ignored -> {
            // 投影与到期选择共享事务，事实迁移仍通过治理入口执行。
            log.debug("记忆衰减投影开始 tenant={}", tenant);
            String projectionAfter = projectionCursors.getOrDefault(tenant, "");
            List<String> active = project ? mapper.active(tenant, projectionAfter, BATCH_SIZE) : List.of();
            int updated = active.isEmpty() ? 0 : mapper.project(tenant, active);
            String after = dueCursors.getOrDefault(tenant, "");
            List<Due> due = expire ? mapper.due(tenant, after, BATCH_SIZE) : List.of();
            // 游标不承担恢复事实；失败项绕一圈重试，终态已从 SQL 候选退出。
            return new DecayBatch(due, active.size() == BATCH_SIZE, due.size() == BATCH_SIZE,
                    active.size(), updated,
                    project ? (active.size() < BATCH_SIZE ? "" : active.getLast()) : projectionAfter,
                    expire ? (due.size() < BATCH_SIZE ? "" : due.getLast().factId()) : after);
        });
        projectionCursors.put(tenant, result.projectionCursor());
        dueCursors.put(tenant, result.dueCursor());
        log.debug("记忆衰减投影完成 tenant={} scanned={} updated={} due={}",
                tenant, result.scanned(), result.updated(), result.due().size());
        return result;
    }

    /** 当前页待迁移身份与是否还有有界扫描工作；不将候选数当成实际UPDATE行数。 */
    private record DecayBatch(List<Due> due, boolean projectionMore, boolean dueMore,
            int scanned, int updated, String projectionCursor, String dueCursor) { }
}
