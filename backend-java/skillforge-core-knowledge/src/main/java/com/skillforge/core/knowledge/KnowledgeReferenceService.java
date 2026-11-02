package com.skillforge.core.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.knowledge.constant.KnowledgeConstants;
import java.util.List;
import org.springframework.stereotype.Service;

/** 知识挂载的租户隔离查验视图；发布正文与存储实体由知识域拥有。 */
@Service
public class KnowledgeReferenceService {
    private final KnowledgeBaseMapper bases;
    private final KnowledgeBaseRevisionMapper revisions;

    public KnowledgeReferenceService(KnowledgeBaseMapper bases, KnowledgeBaseRevisionMapper revisions) {
        this.bases = bases;
        this.revisions = revisions;
    }

    public Base base(long tenantId, long id) {
        return view(bases.selectOne(new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getTenantId, tenantId).eq(KnowledgeBaseDO::getId, id)));
    }

    public Revision publishedRevision(long tenantId, long id) {
        return view(revisions.selectOne(published(tenantId).eq(KnowledgeBaseRevisionDO::getId, id)));
    }

    public List<Revision> publishedForCapability(long tenantId, long capabilityVersionId) {
        return revisions.selectList(published(tenantId)
                .eq(KnowledgeBaseRevisionDO::getCapabilityVersionId, capabilityVersionId)).stream().map(KnowledgeReferenceService::view).toList();
    }

    /** 仅解析指定能力版本当前所属知识库的活动发布修订，不扫描租户其他知识库。 */
    public List<Revision> activePublishedForCapabilityVersions(long tenantId, List<Long> capabilityVersionIds) {
        if (capabilityVersionIds.isEmpty()) return List.of();
        return revisions.activePublishedForCapabilityVersions(tenantId, capabilityVersionIds)
                .stream().map(KnowledgeReferenceService::view).toList();
    }

    private static LambdaQueryWrapper<KnowledgeBaseRevisionDO> published(long tenantId) {
        return new LambdaQueryWrapper<KnowledgeBaseRevisionDO>()
                .select(KnowledgeBaseRevisionDO::getId, KnowledgeBaseRevisionDO::getTenantId,
                        KnowledgeBaseRevisionDO::getKbId, KnowledgeBaseRevisionDO::getStatus,
                        KnowledgeBaseRevisionDO::getCapabilityVersionId)
                .eq(KnowledgeBaseRevisionDO::getTenantId, tenantId)
                .eq(KnowledgeBaseRevisionDO::getStatus, KnowledgeConstants.REVISION_STATUS_PUBLISHED);
    }
    private static Base view(KnowledgeBaseDO row) {
        return row == null ? null : new Base(row.getId(), row.getTenantId(), row.getStatus(), row.getActiveRevisionId());
    }
    private static Revision view(KnowledgeBaseRevisionDO row) {
        return row == null ? null : new Revision(row.getId(), row.getTenantId(), row.getKbId(), row.getStatus(), row.getCapabilityVersionId());
    }
    public record Base(Long id, Long tenantId, String status, Long activeRevisionId) {}
    public record Revision(Long id, Long tenantId, Long kbId, String status, Long capabilityVersionId) {}
}
