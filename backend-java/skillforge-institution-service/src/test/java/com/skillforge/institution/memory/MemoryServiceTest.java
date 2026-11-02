package com.skillforge.institution.memory;
import com.skillforge.core.shared.config.SnowflakeIds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 短期记忆服务单元测试。
 *
 * <p>覆盖 PG 写穿 + Redis 热消息键约定，以及 Redis 缺失时 PG 回退读取。
 */
class MemoryServiceTest {

    /** Redis 模板 Mock。 */
    private StringRedisTemplate redisTemplate;

    /** Redis List 操作 Mock。 */
    @SuppressWarnings("unchecked")
    private ListOperations<String, String> listOps = mock(ListOperations.class);

    /** 消息表映射器 Mock。 */
    private ChatMessageMapper chatMessageMapper;

    /** 被测记忆服务。 */
    private MemoryService memoryService;

    /** 测试租户 ID（合法 UUID）。 */
    private final String tenantId = String.valueOf(SnowflakeIds.nextId());

    /**
     * 初始化 Mock 与服务。
     */
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(settings.read("memory")).thenReturn(
                new Snapshot(1, Map.of("session_ttl_minutes", 1_440L)));
        memoryService = new MemoryService(
                redisTemplate, chatMessageMapper,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                settings);
    }

    /**
     * append 应写 PG 并写入对齐约定的 Redis 键。
     */
    @Test
    void appendWritesPostgresAndRedis() {
        memoryService.append(tenantId, "sess_1", SnowflakeIds.nextId(), "user", "你好", Map.of(), "msg-1");
        verify(chatMessageMapper).insert(any(ChatMessageDO.class));
        verify(listOps).rightPush(eq(eqKey(tenantId, "sess_1")), anyString());
    }

    /**
     * Redis 热消息缺失时回退 PG 读取最近窗口。
     */
    @Test
    void listFallsBackToPostgresWhenRedisEmpty() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        ChatMessageDO row = new ChatMessageDO();
        row.setMessageId("msg-1");
        row.setRole("assistant");
        row.setContent("已为您处理");
        row.setCreatedAt(Instant.parse("2026-08-05T10:00:00Z"));
        row.setMetadata(Map.of());
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(row));

        List<ChatMessageRecord> records = memoryService.list(tenantId, "sess_1", 20);
        assertEquals(1, records.size());
        assertEquals("已为您处理", records.get(0).content());
    }

    /**
     * 构造期望的 Redis 键。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @return Redis 键
     */
    private String eqKey(String tenantId, String sessionId) {
        return tenantId + ":session:" + sessionId + ":messages";
    }
}
