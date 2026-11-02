package com.skillforge.institution.memory;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 领域内部持久化入口；租户由拦截器在当前事务连接上绑定。 */
@Mapper
public interface MemoryProposalSourceMapper {
    /** 读取已提交事件的事实来源身份；调用方要求结果唯一。 */
    List<Source> sources(@Param("tenant") String tenant, @Param("event") String event);
    record Source(String agentId, long endUserId, String sessionId, String executionId) { }
}
