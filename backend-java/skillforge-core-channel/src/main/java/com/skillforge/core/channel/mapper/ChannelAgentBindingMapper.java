package com.skillforge.core.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 渠道-单 Agent 绑定 MyBatis-Plus 映射接口。 */
public interface ChannelAgentBindingMapper extends BaseMapper<ChannelAgentBindingDO> {
    /** 锁住本机构已发布 Agent，校验与写入之间不能被改成非可绑定状态。 */
    List<Long> lockPublishedAgents(@Param("tenantId") long tenantId,
            @Param("agentIds") List<Long> agentIds, @Param("status") String status);

    /** 绑定是当前路由集合；物理替换旧关系，保留数据库唯一约束。 */
    int deleteForChannel(@Param("tenantId") long tenantId, @Param("channelId") long channelId);

    int insertBindings(@Param("bindings") List<ChannelAgentBindingDO> bindings);

    int deleteForAgent(@Param("tenantId") long tenantId, @Param("channelId") long channelId,
            @Param("agentId") long agentId);
}
