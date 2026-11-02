package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryDO;
import com.skillforge.core.chat.persistence.DeliveryCounts;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/** Session Outbox Mapper；业务代码经 SessionMailboxService 访问。 */
public interface SessionOutboxMapper extends BaseMapper<SessionOutboxEntryDO> {

    /** 根输入轨迹只读取摘要字段；完整传输正文仍由回放入口拥有。 */
    List<TraceEntry> traceEntries(@Param("tenantId") long tenantId, @Param("rootInputId") String rootInputId);

    record TraceEntry(String eventId, String eventType, String traceId, Instant createdAt,
                      Long sessionSequence, String sourceEventId) {}


    /** 调用方已持有会话序号锁；一次读取本批稳定身份对应的原持久输出。 */
    List<SessionOutboxEntryDO> findEvents(@Param("tenantId") long tenantId, @Param("eventIds") String[] eventIds);

    /** 同一会话原事务内一次写入已分配序号的新输出；返回真实写入行数。 */
    int insertOutputs(@Param("rows") List<SessionOutboxEntryDO> rows);

    /** 单次读取本会话已提交的末事件；未提交的序号和输出不可见，空会话返回 null。 */
    String committedCursor(@Param("tenantId") long tenantId, @Param("sessionId") String sessionId);

    /** 只验证精确游标归属并取持久序号，不读取事件正文。 */
    Long outputSequence(@Param("tenantId") long tenantId, @Param("sessionId") String sessionId,
            @Param("eventId") String eventId);

    /** 本会话最近独立消息的最高版本快照；首序号决定消息位置，后续delta不挤掉其他消息。 */
    List<SessionOutboxEntryDO> recentMessages(@Param("tenantId") long tenantId,
            @Param("sessionId") String sessionId, @Param("limit") int limit);

    /** 本会话指定消息的最新公开版本。 */
    SessionOutboxEntryDO message(@Param("tenantId") long tenantId, @Param("sessionId") String sessionId,
            @Param("messageId") String messageId);

    /** 只汇总三元归属的 Outbox 与 Webhook 投递状态，不读取传输 payload。 */
    DeliveryCounts deliveryCounts(@Param("tenantId") long tenantId,
            @Param("sessionId") String sessionId, @Param("runtimeRunId") String runtimeRunId);

    /** 只返回有界失败尾部的稳定诊断字段，不暴露订阅 URL、正文或凭据。 */
    List<SessionOutboxWebhookDeliveryDO> latestDeliveryFailures(
            @Param("tenantId") long tenantId, @Param("sessionId") String sessionId,
            @Param("runtimeRunId") String runtimeRunId, @Param("limit") int limit);
}
