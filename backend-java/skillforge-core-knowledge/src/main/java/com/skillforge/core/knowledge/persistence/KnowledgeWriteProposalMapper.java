package com.skillforge.core.knowledge.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.knowledge.persistence.KnowledgeWriteProposalDO;
import org.apache.ibatis.annotations.Mapper;

/** Knowledge Runtime 写回提案映射器。 */
@Mapper
public interface KnowledgeWriteProposalMapper extends BaseMapper<KnowledgeWriteProposalDO> {
}
