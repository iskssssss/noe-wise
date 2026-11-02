package com.skillforge.institution.service;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.application.KnowledgeWriteProposalService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 写回治理按真实目标判权，记忆治理必须完整租户范围；审核身份来自当前账号。 */
@Service
public class KnowledgeProposalManagementService {
    private final KnowledgeWriteProposalService proposals;
    private final KnowledgeManagementService knowledge;
    private final InstitutionResourceAccess resources;
    private final FoundationAccess access;
    public KnowledgeProposalManagementService(KnowledgeWriteProposalService proposals,
            KnowledgeManagementService knowledge, InstitutionResourceAccess resources, FoundationAccess access) {
        this.proposals = proposals; this.knowledge = knowledge; this.resources = resources; this.access = access;
    }

    public List<KnowledgeWriteProposal> pending(String tenantId) {
        var filter = resources.filter("knowledge", "read");
        return proposals.listPending(tenantId, filter.all() ? null : filter.resourceIds().stream().map(Long::valueOf).toList(), access.currentDataScope().all());
    }

    public void require(String tenantId, KnowledgeWriteProposal proposal, String action) {
        if (proposal.target() == KnowledgeWriteProposal.Target.MEMORY) {
            if (!access.currentDataScope().all()) throw new ApiException(HttpStatus.FORBIDDEN, "记忆提案治理需要完整机构范围");
            return;
        }
        if (!(proposal.payload().get("institutional_knowledge") instanceof Map<?, ?> body))
            throw new ApiException(HttpStatus.BAD_REQUEST, "机构知识提案缺少目标知识库");
        Object id = body.get("kb_id");
        knowledge.requireBase(tenantId, id == null ? "" : id.toString(), action);
    }

    public void require(String tenantId, String proposalId, String action) {
        require(tenantId, proposals.get(tenantId, proposalId), action);
    }

    public String reviewer() { return access.currentSubject().memberId(); }
}
