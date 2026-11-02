package com.skillforge.institution.group;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅定位同群当前事实；内容与修订仍由正式 MemoryPort 读写。 */
@Mapper
public interface GroupMemoryMapper {
    /** 持有当前资格行读锁至治理事务结束，撤权/退出与写入形成确定先后。 */
    boolean lockEligibility(@Param("tenant") long tenant,@Param("integration") String integration,
            @Param("group") long group,@Param("user") long user,@Param("agent") long agent,
            @Param("principal") String principal,@Param("credential") long credential);
    List<String> facts(@Param("tenant") String tenant, @Param("group") String group,
            @Param("subject") long subject, @Param("scope") String scope,
            @Param("status") String status, @Param("limit") int limit);
}
