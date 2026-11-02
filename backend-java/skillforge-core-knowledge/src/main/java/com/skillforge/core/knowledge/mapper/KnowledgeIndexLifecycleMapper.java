package com.skillforge.core.knowledge.mapper;

import com.skillforge.core.knowledge.KnowledgeIndexLifecycleService.Cleanup;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 知识外部索引进行中保护、UNKNOWN引用及精确删除意图的所属持久关系。 */
public interface KnowledgeIndexLifecycleMapper {
    /** 所有索引/回收入口共用所属知识库行锁，避免解除引用和迟到发起竞态。 */
    Long lockBase(@Param("tenant") long tenant,@Param("kb") long kb);
    /** 来源外键保持进行中索引材料，网络开始前必须提交该引用。 */
    int insertHold(@Param("tenant") long tenant,@Param("kb") long kb,@Param("source") String source,@Param("id") long id);
    /** 只释放本次已确认投影持有者，不影响其他未确认操作。 */
    int releaseHold(@Param("tenant") long tenant,@Param("id") long id);
    /** 首次未确认状态变更才UPDATE，既有UNKNOWN不重复更新。 */
    int markUnknown(@Param("tenant") long tenant,@Param("id") long id);
    /** 从锁内真实来源身份登记删除意图，不能由外部任意参数构造。 */
    int enqueue(@Param("tenant") long tenant,@Param("kb") long kb,@Param("version") long version);
    /** 有界领取持久意图并推进attempt fencing，过期lease可以续接。 */
    List<Cleanup> claim(@Param("tenant") long tenant,@Param("limit") int limit);
    /** 确认精确删除后，只删除同领取代次的任务。 */
    int complete(@Param("tenant") long tenant,@Param("kb") long kb,@Param("source") String source,@Param("attempt") long attempt);
    /** 未确认回执保留任务，释放本代lease并设置有界重试间隔。 */
    int defer(@Param("tenant") long tenant,@Param("kb") long kb,@Param("source") String source,@Param("attempt") long attempt);
}
