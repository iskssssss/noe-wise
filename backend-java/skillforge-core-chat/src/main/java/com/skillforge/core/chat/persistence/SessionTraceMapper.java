package com.skillforge.core.chat.persistence;

import com.skillforge.core.chat.tracing.SessionTraceEventRow;
import org.apache.ibatis.annotations.Param;

/** 只按完整租户与 Session 身份读取已提交事件的公开内容来源。 */
public interface SessionTraceMapper {
    SessionTraceEventRow findEvent(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("eventId") String eventId);
}
