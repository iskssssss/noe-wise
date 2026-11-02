package com.skillforge.core.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.knowledge.model.KnowledgeGovernanceProjectionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Knowledge Governance 投影记录 Mapper。 */
@Mapper
public interface KnowledgeGovernanceProjectionMapper extends BaseMapper<KnowledgeGovernanceProjectionDO> {
    int insertClaim(@Param("row") KnowledgeGovernanceProjectionDO row);
    KnowledgeGovernanceProjectionDO lockByProposal(@Param("tenantId") long tenantId,
            @Param("proposalId") String proposalId);
}
