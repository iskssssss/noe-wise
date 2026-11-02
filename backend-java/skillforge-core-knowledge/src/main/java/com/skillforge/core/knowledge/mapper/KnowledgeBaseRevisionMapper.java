package com.skillforge.core.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Knowledge Base revision 映射器。 */
@Mapper
public interface KnowledgeBaseRevisionMapper extends BaseMapper<KnowledgeBaseRevisionDO> {

    /** 只返回目标能力版本与其真实所属知识库活动指针一致的发布身份。 */
    List<KnowledgeBaseRevisionDO> activePublishedForCapabilityVersions(
            @Param("tenantId") long tenantId, @Param("capabilityVersionIds") List<Long> capabilityVersionIds);


    /** 仅当 revision 仍为 draft 时写入不可变 source-version manifest。 */
    int markReady(@Param("tenantId") long tenantId,
            @Param("kbId") long kbId,
            @Param("revision") long revision,
            @Param("sourceVersions") Object sourceVersions);

    /** 锁定知识库控制行，串行化同一知识库的 active revision 切换。 */
    KnowledgeBaseDO lockBase(@Param("tenantId") long tenantId,
            @Param("kbId") long kbId);

    /** 在持有知识库控制行锁的事务内归档当前 published revision。 */
    int archivePublished(@Param("tenantId") long tenantId,
            @Param("kbId") long kbId,
            @Param("revision") long revision);

    /** 仅当目标 revision 仍为 ready 时推进为 published。 */
    int publishReady(@Param("tenantId") long tenantId,
            @Param("kbId") long kbId,
            @Param("revision") long revision);

    /** 在同一事务内切换 Knowledge Base active 指针。 */
    int activateBase(@Param("tenantId") long tenantId,
            @Param("kbId") long kbId,
            @Param("revisionId") long revisionId);
}
