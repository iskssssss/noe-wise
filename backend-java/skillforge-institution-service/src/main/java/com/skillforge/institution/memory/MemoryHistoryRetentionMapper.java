package com.skillforge.institution.memory;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 被替代事实的状态历史回收；正文首版、当前head及全部回执/来源仍受数据库实际引用保护。 */
@Mapper
public interface MemoryHistoryRetentionMapper {
    /** 使用数据库时钟生成批准的保留截止点，避免宿主时钟偏移提前回收。 */
    Instant cutoff(@Param("days") long days);
    /** 按PK游标锁定已由Memory所有者实际替代且过期的有界状态历史；不接收caller退役声明。 */
    List<Revision> candidates(@Param("tenant") String tenant, @Param("cutoff") Instant cutoff,
            @Param("afterFact") String afterFact, @Param("afterRevision") long afterRevision,
            @Param("limit") int limit);
    /** 在同一事务启用受保护历史DELETE；触发器逐项重复校验退役、时限及实际引用。 */
    String bindCutoff(@Param("cutoff") Instant cutoff);
    /** 候选行仍被锁定，删除前用新语句快照再次检查实际引用；返回真实受影响行数。 */
    int deleteUnprotected(@Param("tenant") String tenant, @Param("cutoff") Instant cutoff,
            @Param("revisions") List<Revision> revisions);
    /** 历史身份既是游标又是候选；不包含正文或来源。 */
    record Revision(String factId, long revision) { }
}
