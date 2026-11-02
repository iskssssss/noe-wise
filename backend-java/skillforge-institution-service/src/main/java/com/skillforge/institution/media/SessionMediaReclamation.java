package com.skillforge.institution.media;

import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.attachment.SessionAttachmentBodyMapper;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未使用上传图片的资源退役所有者。每个 root 独立事务先锁 Runtime owner，再锁租户和媒体闭包；
 * pin、Run、有效后代及真实恢复引用继续保护，原 Runtime 回执/日志不参与删除。
 */
@Service
public class SessionMediaReclamation {
    static final int PAGE_ROOTS = 16;
    static final int MAX_CLOSURE_ROWS = 100;
    private final SessionMediaMapper mapper;
    private final SessionAttachmentBodyStore bodies;
    private final SessionAttachmentBodyMapper bodyMapper;

    /** 复用正式正文锁与 GC；不另建 owner 权威或图片保留策略。 */
    public SessionMediaReclamation(SessionMediaMapper mapper, SessionAttachmentBodyStore bodies,
            SessionAttachmentBodyMapper bodyMapper) {
        this.mapper = mapper;
        this.bodies = bodies;
        this.bodyMapper = bodyMapper;
    }

    /** 有界扫描持久候选；进程游标只影响公平性，重启从头不会跳过尚有正文的材料。 */
    public List<SessionMediaMapper.UnusedUpload> page(String tenant, String after) {
        requireTenant(tenant);
        return mapper.unusedUploadPage(tenant, after, PAGE_ROOTS);
    }

    /**
     * 此方法必须经 Spring 事务代理调用。owner gate 与 tenant/metadata 重查共享同一连接和事务；
     * 门禁只授予该候选的未用资源退役，不要求无关历史回执消失。任何闭包异常整批保留。
     */
    @Transactional
    public Retirement retire(String tenant, SessionMediaMapper.UnusedUpload candidate) {
        requireTenant(tenant);
        Objects.requireNonNull(candidate);
        // Runtime intake/关闭先锁 owner；不能先持 tenant 后反向等待 owner。
        if (!mapper.unusedMediaOwnerGate(tenant, candidate.productSessionId(), candidate.runtimeSessionId(), candidate.id()))
            return new Retirement(0, 0, "runtime_protected");
        bodies.lockTenant(tenant);
        var closure = mapper.lockUnusedUploadClosure(tenant, candidate.runtimeSessionId(), candidate.id(), MAX_CLOSURE_ROWS + 1);
        if (closure.isEmpty() || closure.size() > MAX_CLOSURE_ROWS || !releasable(candidate, closure))
            return new Retirement(0, 0, "media_protected");
        var ids = closure.stream().filter(row -> row.bodyFormat() != null).map(SessionMediaMapper.UnusedMediaRow::id).toList();
        if (ids.isEmpty()) return new Retirement(0, 0, "already_retired");
        int released = mapper.releaseUnusedUploadClosure(tenant, candidate.productSessionId(), candidate.runtimeSessionId(), candidate.id(), ids);
        if (released != ids.size()) throw new IllegalStateException("unused_media_retirement_conflict");
        // GC 仍核对全部 metadata FK；同租户其它会话共享的正文不会因本 root 退役而删除。
        int reclaimed = bodyMapper.reclaimUnreferenced(tenant, MAX_CLOSURE_ROWS);
        return new Retirement(released, reclaimed, "retired");
    }

    /** 来源及有效后代必须同产品/Runtime owner，且仅为原图或未被读取的规范化图。 */
    private static boolean releasable(SessionMediaMapper.UnusedUpload candidate, List<SessionMediaMapper.UnusedMediaRow> closure) {
        boolean foundRoot = false;
        for (var row : closure) {
            if (!candidate.productSessionId().equals(row.productSessionId())
                    || !candidate.runtimeSessionId().equals(row.runtimeSessionId())) return false;
            if (row.id().equals(candidate.id())) {
                if (row.sourceId() != null || !"source-v1".equals(row.transform())) return false;
                foundRoot = true;
            } else if (row.bodyFormat() != null
                    && (!candidate.id().equals(row.sourceId()) || !"normalize-v1".equals(row.transform()))) return false;
            if (row.bodyFormat() != null && (row.referencedAt() != null || row.runId() != null
                    || !SessionAttachmentBodyStore.FORMAT.equals(row.bodyFormat()))) return false;
        }
        return foundRoot;
    }

    /** 调度使用可信租户上下文；请求参数不能借此扩大机构范围。 */
    private static void requireTenant(String tenant) {
        if (!Objects.equals(tenant, TenantContext.getTenantId())) throw new SecurityException("unused_media_tenant_mismatch");
    }

    /** 实际影响行数与保护结果；0 不表示 owner 整体退役或未知引用已消失。 */
    public record Retirement(int releasedRows, int reclaimedBodies, String result) { }
}
