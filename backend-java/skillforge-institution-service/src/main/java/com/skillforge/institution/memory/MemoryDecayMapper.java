package com.skillforge.institution.memory;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 领域内部持久化入口；租户由拦截器在当前事务连接上绑定。 */
@Mapper
public interface MemoryDecayMapper {
    /** 在Runtime已锁head的当前会话中锁投影并重验时间阈值，缺失或NULL由调用方拒绝。 */
    Boolean decayReady(@Param("tenant") String tenant, @Param("fact") String fact);
    /** 按稳定主键读取最多limit个ACTIVE投影身份；正文与衰减计算不进入全量扫描。 */
    List<String> active(@Param("tenant") String tenant, @Param("after") String after,
            @Param("limit") int limit);
    /** 仅重算已受界限的身份页，分值实际变化才更新；空页由调用方跳过。 */
    int project(@Param("tenant") String tenant, @Param("facts") List<String> facts);
    /** 按稳定 factId 游标读取有界 ACTIVE 到期事实，状态迁移仍由治理入口执行。 */
    List<Due> due(@Param("tenant") String tenant, @Param("after") String after,
            @Param("limit") int limit);
    /** 到期事实的精确 CAS 身份；revision 变化时不得迁移旧读结果。 */
    record Due(String factId, long revision) { }
}
