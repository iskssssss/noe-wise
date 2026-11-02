package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.session.SessionDirectoryItem;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 会话 MyBatis-Plus 映射接口。 */
public interface SessionMapper extends BaseMapper<SessionDO> {
    /** 同事务锁住外部会话与 Agent 的稳定映射，串行化首次创建。 */
    void lockOpenPlatform(@Param("mappingKey") String mappingKey);
    /** 创建外部映射前检查目标真实机构归属，避免只在首次执行时才发现越界。 */
    boolean ownsAgent(@Param("tenantId") Long tenantId,@Param("agentId") Long agentId);
    /** 按最新消息降序查询，用户与 Agent 筛选可空，渠道隔离仍生效。 */
    List<SessionDirectoryItem> listByLatestMessage(
            @Param("tenantId") Long tenantId,
            @Param("endUserId") Long endUserId,
            @Param("agentId") Long agentId,
            @Param("teamId") String teamId,
            @Param("channelId") Long channelId,
            @Param("visibleSessionIds") List<String> visibleSessionIds);
}
