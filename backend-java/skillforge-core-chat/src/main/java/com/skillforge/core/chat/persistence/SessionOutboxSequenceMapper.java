package com.skillforge.core.chat.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.skillforge.core.chat.session.SessionOutboxProjectionRecord;
import com.skillforge.core.chat.session.SessionOutboxProjectionSource;
import com.skillforge.core.chat.session.SessionOutboxProjectionState;

/** 为每个会话分配严格递增的 Outbox sessionSequence。 */
@Mapper
public interface SessionOutboxSequenceMapper {

    com.skillforge.core.chat.session.SessionOutboxProjectionCheckpoint projectionCheckpoint(
            @Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 在当前事务创建会话序号行；已存在时影响行数为零。 */
    int ensure(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 序号所有者也串行化同一会话的投影去重检查，避免并发唯一冲突使事务失效。 */
    Long lock(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 在同一条序号行FOR UPDATE中取得处理回执和成功水位；调用方持锁完成整批投影。 */
    SessionOutboxProjectionState lockProjection(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 读取指定租户会话的投影回执；无记录返回 null。 */
    SessionOutboxProjectionRecord projectionRecord(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 同一UPDATE保存成功水位，并仅在来源不旧于现有回执时更新处理成功；较新拒绝仍保留。 */
    int recordProjectionCompletion(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId,
            @Param("source") SessionOutboxProjectionSource source, @Param("state") String state);

    /** 原事务已回滚后记录永久拒绝；同来源成功或已有拒绝均不可被迟到失败再次覆盖。 */
    int recordProjectionRejection(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId,
            @Param("source") SessionOutboxProjectionSource source, @Param("errorCode") String errorCode);

    /** 原锁事务一次预留新增输出的连续序号区间，返回区间末端；count 必须为正。 */
    Long reserve(@Param("tenantId") Long tenantId, @Param("sessionId") String sessionId, @Param("count") int count);
}
