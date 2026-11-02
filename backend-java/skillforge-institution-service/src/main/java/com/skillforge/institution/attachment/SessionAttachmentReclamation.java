package com.skillforge.institution.attachment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每批在租户主行锁内先解除可回收附件引用，再删除无引用正文。批次提交后可从尚有正文的
 * 最旧记录续接；失败整批回滚，不推进持久记录，不依赖易丢失的内存游标跳过材料。
 */
@Service
public class SessionAttachmentReclamation {
    static final int BATCH_ROWS = 100;
    private final SessionAttachmentMapper attachments;
    private final SessionAttachmentBodyMapper mapper;
    private final SessionAttachmentBodyStore bodies;

    public SessionAttachmentReclamation(SessionAttachmentMapper attachments, SessionAttachmentBodyMapper mapper,
            SessionAttachmentBodyStore bodies) {
        this.attachments = attachments;
        this.mapper = mapper;
        this.bodies = bodies;
    }

    @Transactional
    public BatchResult expiredBatch(String tenant) {
        bodies.lockTenant(tenant);
        int released = attachments.clearExpiredContent(tenant, BATCH_ROWS);
        int reclaimed = mapper.reclaimUnreferenced(tenant, BATCH_ROWS);
        var backlog = mapper.backlog(tenant, BATCH_ROWS + 1);
        return new BatchResult(released, reclaimed, backlog);
    }

    /** released/reclaimed 是实际影响行数；sampledRows 只表示有界样本，不能作为完整积压数。 */
    public record BatchResult(int released, int reclaimed, SessionAttachmentBodyMapper.Backlog backlog) {
        public boolean moreWork() {
            return released == BATCH_ROWS || reclaimed == BATCH_ROWS || backlog.sampledRows() > 0;
        }
    }
}
