package com.skillforge.core.chat.message;
import com.skillforge.core.shared.config.SnowflakeIds;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 短期记忆服务。
 *
 * <p>会话全量消息写穿 PostgreSQL {@code chat_messages}，Redis
 * {@code {tenant_id}:session:{session_id}:messages} 作为热消息缓存；
 * 消息窗口与 TTL 经版本化运营配置提供。
 */
@Service
public class MemoryService {
    /** Redis 热视图大小与 Runtime 历史分页独立。 */
    private static final int HOT_VIEW_MESSAGES = 100;

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final String SYSTEM_ROLE="system";

    /** Redis 操作模板。 */
    private final StringRedisTemplate redisTemplate;

    /** 会话消息表映射器。 */
    private final ChatMessageMapper chatMessageMapper;

    /** Jackson 对象映射器，用于热消息 JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** 版本化运营配置，提供消息窗口与 TTL。 */
    private final OperationalSettingsService settings;

    /**
     * 构造记忆服务。
     *
     * @param redisTemplate     Redis 操作模板
     * @param chatMessageMapper 会话消息表映射器
     * @param objectMapper      Jackson 对象映射器
     * @param settings          版本化运营配置
     */
    public MemoryService(
            StringRedisTemplate redisTemplate,
            ChatMessageMapper chatMessageMapper,
            ObjectMapper objectMapper,
            OperationalSettingsService settings) {
        this.redisTemplate = redisTemplate;
        this.chatMessageMapper = chatMessageMapper;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    /**
     * 追加一条对话消息（PG 归档 + Redis 热消息写穿）。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @param endUserId C 端用户 ID
     * @param role      消息角色：user / assistant / system
     * @param content   消息内容
     * @param metadata  附加元数据，可空
     * @param messageId 消息幂等 ID，可空（空则服务端生成）
     * @return 消息记录
     */
    public ChatMessageRecord append(
            String tenantId,
            String sessionId,
            Long endUserId,
            String role,
            String content,
            Map<String, Object> metadata,
            String messageId) {
        HotViewSettings hotViewSettings = readHotViewSettings();
        ChatMessageDO row = newMessage(tenantId, sessionId, endUserId, role, content, metadata, messageId);
        chatMessageMapper.insert(row);

        String key = memoryKey(tenantId, sessionId);
        ChatMessageRecord record = toRecord(row);
        Runnable publishHotView = () -> {
        try {
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -hotViewSettings.historyMessages(), -1);
            redisTemplate.expire(key, Duration.ofMinutes(hotViewSettings.sessionTtlMinutes()));
        } catch (Exception e) {
            log.warn("Redis 热消息写入失败，PG 归档不受影响: {}", e.getMessage(), e);
        }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publishHotView.run(); }
            });
        } else publishHotView.run();
        return record;
    }

    /** 外部消息与执行受理共用事务；只归档，不提交事务或提前更新 Redis。 */
    public void appendBorrowed(org.apache.ibatis.session.SqlSession sql, String tenantId, String sessionId,
            Long endUserId, String role, String content, Map<String, Object> metadata, String messageId) {
        sql.getMapper(ChatMessageMapper.class).insertArchived(
                newMessage(tenantId, sessionId, endUserId, role, content, metadata, messageId));
    }

    /** 普通写穿与外部受理使用同一消息实体，保留公开身份及原始内容分段。 */
    private static ChatMessageDO newMessage(String tenantId, String sessionId, Long endUserId,
            String role, String content, Map<String, Object> metadata, String messageId) {
        String finalMessageId = messageId == null || messageId.isBlank()
                ? String.valueOf(SnowflakeIds.nextId()) : messageId;
        ChatMessageDO row = new ChatMessageDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(Long.parseLong(tenantId));
        row.setSessionId(sessionId);
        row.setEndUserId(endUserId);
        row.setMessageId(finalMessageId);
        row.setRole(role);
        row.setContent(content);
        row.setMetadata(metadata == null ? Map.of() : metadata);
        row.setMessageVersion(ChatMessageRecord.INITIAL_VERSION);
        row.setReferences(List.of());
        row.setCreatedAt(Instant.now());
        return row;
    }

    /** 为执行受理事务安装消息领域自身的映射，不依赖 Spring 的独立连接。 */
    public static void configureBorrowed(org.apache.ibatis.session.Configuration configuration) {
        String resource = "mapper/ChatMessageMapper.xml";
        if (configuration.isResourceLoaded(resource)) return;
        try (var input = MemoryService.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("chat_message_mapper_missing");
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("chat_message_mapper_failed", failure);
        }
    }

    /**
     * 按租户与消息幂等 ID 读取权威归档行。
     *
     * <p>唯一约束是 {@code (tenant_id, message_id)}，查询不能再按 session 收窄，
     * 否则会漏掉跨会话冲突并在插入时变成 500。</p>
     *
     * @param tenantId  租户 ID
     * @param messageId 消息幂等 ID
     * @return 归档消息；不存在或 ID 空白时返回 {@code null}
     */
    public ArchivedChatMessage findByMessageId(String tenantId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        ChatMessageDO row = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getTenantId, Long.parseLong(tenantId))
                .eq(ChatMessageDO::getMessageId, messageId));
        return row == null ? null : new ArchivedChatMessage(row.getSessionId(), toRecord(row));
    }

    /** 引用重放只核对稳定归属与可用性，不加载可变消息的当前正文和版本。 */
    public boolean publicMessageExists(String tenantId,String sessionId,String messageId) {
        return chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getTenantId,Long.parseLong(tenantId))
                .eq(ChatMessageDO::getSessionId,sessionId).eq(ChatMessageDO::getMessageId,messageId)
                .ne(ChatMessageDO::getRole,SYSTEM_ROLE))>0;
    }

    /**
     * 卡消息同 id merge：同 (tenant_id, message_id) 覆盖内容与元数据，
     * created_at 保留首次时间（历史时序稳定），Redis 热消息原位替换。
     *
     * <p>用于进度卡/状态卡 updateFlag 原地更新：消息不再追加，SSE 以
     * {@code message.card.update} 覆盖推送，前端按 message_id 原位刷新。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @param endUserId C 端用户 ID
     * @param role      消息角色
     * @param content   消息内容
     * @param metadata  附加元数据，可空
     * @param messageId 覆盖目标消息 ID，必填（同 id 覆盖）
     * @return 消息记录
     */
    public ChatMessageRecord upsert(
            String tenantId,
            String sessionId,
            Long endUserId,
            String role,
            String content,
            Map<String, Object> metadata,
            String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("merge 消息 ID 不能为空");
        }
        HotViewSettings hotViewSettings = readHotViewSettings();
        ChatMessageDO row = new ChatMessageDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(Long.parseLong(tenantId));
        row.setSessionId(sessionId);
        row.setEndUserId(endUserId);
        row.setMessageId(messageId);
        row.setRole(role);
        row.setContent(content);
        row.setMetadata(metadata == null ? Map.of() : metadata);
        row.setCreatedAt(Instant.now());
        if (chatMessageMapper.upsertById(row) != 1) {
            throw new com.skillforge.core.shared.exception.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT, "消息身份与当前会话、用户或角色冲突");
        }

        // Redis 热消息原位替换：同 message_id 覆盖（保持列表位置与时间序）
        String key = memoryKey(tenantId, sessionId);
        ChatMessageDO stored=chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getTenantId,Long.parseLong(tenantId)).eq(ChatMessageDO::getMessageId,messageId));
        if(stored==null) throw new IllegalStateException("chat_message_upsert_missing");
        ChatMessageRecord record = toRecord(stored);
        try {
            List<String> hot = redisTemplate.opsForList().range(key, 0, -1);
            List<ChatMessageRecord> records = parseHot(hot);
            for (int i = 0; i < records.size(); i++) {
                if (messageId.equals(records.get(i).messageId())) {
                    records.set(i, record);
                    String replaced = objectMapper.writeValueAsString(record);
                    redisTemplate.opsForList().set(key, i, replaced);
                    return record;
                }
            }
            // 热消息中不存在该 ID（新消息）：按追加处理
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -hotViewSettings.historyMessages(), -1);
            redisTemplate.expire(key, Duration.ofMinutes(hotViewSettings.sessionTtlMinutes()));
        } catch (Exception e) {
            log.warn("Redis 热消息 merge 失败，PG 覆盖不受影响: {}", e.getMessage(), e);
        }
        return record;
    }

    /**
     * 读取最近 N 条会话消息，优先 Redis 热消息，缺失时回退 PG。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @param limit     返回条数上限；小于等于 0 表示不限制
     * @return 消息记录列表（按时间正序）
     */
    public List<ChatMessageRecord> list(String tenantId, String sessionId, int limit) {
        if (limit <= 0) {
            return listArchived(tenantId, sessionId, 0);
        }
        List<String> hot = redisTemplate.opsForList().range(memoryKey(tenantId, sessionId), -limit, -1);
        List<ChatMessageRecord> records = parseHot(hot);
        if (records.size() >= limit && chronological(records)) {
            return records;
        }
        return listArchived(tenantId, sessionId, limit);
    }

    /** 从权威归档读取近期消息；外部受理不写 Redis，不能用热视图替代此读取。 */
    public List<ChatMessageRecord> listArchived(String tenantId, String sessionId, int limit) {
        LambdaQueryWrapper<ChatMessageDO> wrapper = new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getTenantId, Long.parseLong(tenantId))
                .eq(ChatMessageDO::getSessionId, sessionId);
        if (limit > 0) {
            // 先取最新 N 条，再恢复为模型上下文所需的时间正序；不能使用 ASC + LIMIT。
            wrapper.orderByDesc(ChatMessageDO::getCreatedAt)
                    .orderByDesc(ChatMessageDO::getId)
                    .last("LIMIT " + limit);
            List<ChatMessageDO> rows = new ArrayList<>(chatMessageMapper.selectList(wrapper));
            java.util.Collections.reverse(rows);
            return toRecords(rows);
        }
        wrapper.orderByAsc(ChatMessageDO::getCreatedAt).orderByAsc(ChatMessageDO::getId);
        return toRecords(chatMessageMapper.selectList(wrapper));
    }

    /**
     * 读取会话全部历史消息（PG 权威）。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @return 消息记录列表
     */
    public List<ChatMessageRecord> listAll(String tenantId, String sessionId) {
        List<ChatMessageDO> rows = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getTenantId, Long.parseLong(tenantId))
                        .eq(ChatMessageDO::getSessionId, sessionId)
                        .orderByAsc(ChatMessageDO::getCreatedAt));
        return toRecords(rows);
    }

    /**
     * 构造记忆 Redis 键，对齐文档约定。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @return Redis 键
     */
    private String memoryKey(String tenantId, String sessionId) {
        return tenantId + ":session:" + sessionId + ":messages";
    }

    /** 仅受理后的服务器引用写回用户消息；Redis 热视图失效后从权威归档恢复。 */
    public void freezeInputReferences(String tenant,String session,String input,
            List<com.skillforge.core.chat.contract.ChatReferenceView> references) {
        if(chatMessageMapper.freezeInputReferences(Long.parseLong(tenant),session,input,references)>0)
            redisTemplate.delete(memoryKey(tenant,session));
    }

    /** 每个写入任务只读取一次各分组，后续 Redis 操作复用同一快照。 */
    private HotViewSettings readHotViewSettings() {
        var memory = settings.read("memory");
        return new HotViewSettings(
                HOT_VIEW_MESSAGES,
                memory.integer("session_ttl_minutes"));
    }

    /**
     * 解析 Redis 热消息 JSON 列表。
     *
     * @param hot 原始 JSON 列表，可为空
     * @return 消息记录列表
     */
    private List<ChatMessageRecord> parseHot(List<String> hot) {
        if (hot == null || hot.isEmpty()) {
            return List.of();
        }
        List<ChatMessageRecord> records = new ArrayList<>();
        for (String json : hot) {
            try {
                records.add(objectMapper.readValue(json, ChatMessageRecord.class));
            } catch (Exception e) {
                log.warn("Redis 热消息解析失败，跳过: {}", e.getMessage(), e);
            }
        }
        return records;
    }

    /** Redis List 正常右侧追加应按时间递增；异常数据不参与上下文注入。 */
    private boolean chronological(List<ChatMessageRecord> records) {
        if (records == null || records.isEmpty()) {
            return false;
        }
        Instant previous = null;
        for (ChatMessageRecord record : records) {
            if (record == null || record.createdAt() == null
                    || (previous != null && record.createdAt().isBefore(previous))) {
                return false;
            }
            previous = record.createdAt();
        }
        return true;
    }

    /**
     * 将消息实体转换为记录。
     *
     * @param rows 消息实体列表
     * @return 消息记录列表
     */
    private List<ChatMessageRecord> toRecords(List<ChatMessageDO> rows) {
        return rows.stream().map(MemoryService::toRecord).toList();
    }

    private static ChatMessageRecord toRecord(ChatMessageDO row) {
        return new ChatMessageRecord(
                row.getMessageId(),
                row.getRole(),
                row.getContent(),
                row.getCreatedAt(),
                row.getMetadata(),row.getMessageVersion()==null?ChatMessageRecord.INITIAL_VERSION:row.getMessageVersion(),
                row.getReferences()==null?List.of():row.getReferences());
    }

    /**
     * 权威归档消息，附带会话归属，供入口判断合法重放与冲突。
     *
     * @param sessionId 消息所属会话
     * @param record    对外消息记录
     */
    public record ArchivedChatMessage(String sessionId, ChatMessageRecord record) {
    }

    private record HotViewSettings(int historyMessages, int sessionTtlMinutes) {
    }
}
