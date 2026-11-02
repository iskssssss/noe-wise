package com.skillforge.core.chat.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.agent.AgentQueryService;
import com.skillforge.core.chat.ChatRuntimeHistory;
import com.skillforge.core.chat.ChatSessionService;
import com.skillforge.core.chat.ChatTeamGateway;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式 Mapper SQL 与真实主动消息消费者共同验证身份冲突不会发布成功消息。 */
@Testcontainers(disabledWithoutDocker = false)
class ChatMessageMergeConflictPostgresTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @SuppressWarnings("unchecked")
    void conflictStopsBeforeCacheAndOutboxWhileSameOwnerUpdatesRemainValid() throws Exception {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE nw_chat_messages(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,session_id text NOT NULL,end_user_id bigint,"
                + "message_id text NOT NULL,role text NOT NULL,content text,metadata jsonb,input_references jsonb NOT NULL DEFAULT '[]',"
                + "message_version bigint NOT NULL DEFAULT 1,created_at timestamptz,UNIQUE(tenant_id,message_id))");
        jdbc.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,end_user_id,message_id,role,content,metadata,created_at) VALUES "
                + "(1,7,'other-session',10,'cross-session','assistant','private-session-content','{}',now()),"
                + "(2,7,'current-session',11,'cross-user','assistant','other-user-content','{}',now()),"
                + "(3,7,'current-session',10,'cross-role','user','user-input','{}',now())");
        var config = new MybatisConfiguration(new Environment("chat-merge-private-pg", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/ChatMessageMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        try (var sql = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            var redis = mock(StringRedisTemplate.class);
            var lists = (ListOperations<String,String>) mock(ListOperations.class);
            when(redis.opsForList()).thenReturn(lists);
            when(lists.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());
            var settings = mock(OperationalSettingsService.class);
            when(settings.read("memory")).thenReturn(new OperationalSettingsService.Snapshot(1, Map.of("session_ttl_minutes", 90L)));
            var memory = new MemoryService(redis, sql.getMapper(ChatMessageMapper.class),
                    new ObjectMapper().registerModule(new JavaTimeModule()), settings);
            var sessions = mock(SessionService.class);
            var current = mock(SessionView.class);
            when(current.getEndUserId()).thenReturn(10L);
            when(sessions.require("7", "current-session")).thenReturn(current);
            var mailbox = mock(SessionMailboxService.class);
            var chat = new ChatSessionService(mock(AgentQueryService.class), memory, sessions, mailbox,
                    mock(ChatRuntimeHistory.class), mock(ChatTeamGateway.class), mock(PublicOutputGuard.class));
            for (String conflict : List.of("cross-session", "cross-user", "cross-role")) {
                var error = assertThrows(ApiException.class, () -> chat.sendToSession("7", "current-session",
                        "replacement", null, null, conflict));
                assertEquals(409, error.getStatus().value());
            }
            verifyNoInteractions(redis, lists, mailbox);
            assertEquals(List.of("private-session-content", "other-user-content", "user-input"),
                    jdbc.queryForList("SELECT content FROM nw_chat_messages ORDER BY id", String.class));

            var inserted = chat.sendToSession("7", "current-session", "first", null, null, "owned");
            var updated = chat.sendToSession("7", "current-session", "second", null, null, "owned");
            assertEquals(1, inserted.messageVersion());
            assertEquals(2, updated.messageVersion());
            assertEquals("second", jdbc.queryForObject("SELECT content FROM nw_chat_messages WHERE message_id='owned'", String.class));
            assertEquals("current-session", jdbc.queryForObject("SELECT session_id FROM nw_chat_messages WHERE message_id='owned'", String.class));
            verify(mailbox, times(2)).appendOutput(eq("7"), eq("current-session"), anyString(), anyString(), anyMap());
            verify(lists, times(2)).rightPush(eq("7:session:current-session:messages"), anyString());
        }
    }
}
