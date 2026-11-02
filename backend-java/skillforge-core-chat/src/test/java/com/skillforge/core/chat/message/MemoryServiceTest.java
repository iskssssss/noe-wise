package com.skillforge.core.chat.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 最近会话读取必须返回最新 N 条且保持时间正序。 */
class MemoryServiceTest {

    @Test
    void transactionalAppendPublishesCacheOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.append("7","s1",10L,"user","background",Map.of(),"context-1");
            verify(listOps,never()).rightPush(any(),any());
            var callbacks=TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(TransactionSynchronization::afterCommit);
            verify(listOps).rightPush(eq("7:session:s1:messages"),any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void rolledBackAppendDoesNotPublishCache() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.append("7","s1",10L,"user","background",Map.of(),"context-1");
            TransactionSynchronizationManager.getSynchronizations().forEach(callback->callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(listOps,never()).rightPush(any(),any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private StringRedisTemplate redis;
    private ListOperations<String, String> listOps;
    private ChatMessageMapper mapper;
    private OperationalSettingsService settings;
    private MemoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        mapper = mock(ChatMessageMapper.class);
        when(mapper.upsertById(any())).thenReturn(1);
        settings = mock(OperationalSettingsService.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(settings.read("memory")).thenReturn(
                new Snapshot(3, Map.of("session_ttl_minutes", 90L)));
        service = new MemoryService(redis, mapper, objectMapper, settings);
    }

    @Test
    void hotViewReadsOnlyLatestNEntriesInChronologicalOrder() throws Exception {
        when(listOps.range("7:session:s1:messages", -2, -1)).thenReturn(List.of(
                json("m2", "2026-08-24T00:00:02Z"),
                json("m3", "2026-08-24T00:00:03Z")));

        List<ChatMessageRecord> result = service.list("7", "s1", 2);

        assertEquals(List.of("m2", "m3"), result.stream().map(ChatMessageRecord::messageId).toList());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void incompleteOrOutOfOrderHotViewFallsBackToDatabaseLatestN() throws Exception {
        when(listOps.range("7:session:s1:messages", -2, -1)).thenReturn(List.of(
                json("m3", "2026-08-24T00:00:03Z"),
                json("m2", "2026-08-24T00:00:02Z")));
        when(mapper.selectList(any())).thenReturn(List.of(
                row(3L, "m3", "2026-08-24T00:00:03Z"),
                row(2L, "m2", "2026-08-24T00:00:02Z")));

        List<ChatMessageRecord> result = service.list("7", "s1", 2);

        assertEquals(List.of("m2", "m3"), result.stream().map(ChatMessageRecord::messageId).toList());
        verify(mapper).selectList(any());
    }

    @Test
    void allHistoryAlwaysReadsTheAuthoritativeDatabase() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "m1", "2026-08-24T00:00:01Z")));

        List<ChatMessageRecord> result = service.list("7", "s1", 0);

        assertEquals("m1", result.getFirst().messageId());
        verify(listOps, never()).range(any(), anyLong(), anyLong());
    }

    @Test
    void appendWritesDatabaseThenHotView() {
        ChatMessageRecord result = service.append(
                "7", "s1", 10L, "user", "hello", Map.of("input_id", "input-1"), "m1");

        assertEquals("m1", result.messageId());
        verify(mapper).insert(any(ChatMessageDO.class));
        verify(listOps).rightPush(eq("7:session:s1:messages"), any());
        verify(listOps).trim("7:session:s1:messages", -100, -1);
        verify(redis).expire("7:session:s1:messages", Duration.ofMinutes(90));
        verify(settings, never()).read("context");
        verify(settings).read("memory");
    }

    @Test
    void appendKeepsDatabaseFactWhenHotViewWriteFails() {
        when(listOps.rightPush(any(), any())).thenThrow(new IllegalStateException("redis down"));

        ChatMessageRecord result = service.append("7", "s1", 10L, "user", "hello", Map.of(), "m1");

        assertEquals("m1", result.messageId());
        verify(mapper).insert(any(ChatMessageDO.class));
    }

    @Test
    void appendGeneratesMessageIdWhenClientIdAndMetadataAreAbsent() {
        ChatMessageRecord result = service.append("7", "s1", 10L, "user", "hello", null, "");

        assertNotNull(result.messageId());
        assertEquals(Map.of(), result.metadata());
    }

    @Test
    void upsertReplacesExistingHotRecordInPlace() throws Exception {
        when(listOps.range("7:session:s1:messages", 0, -1)).thenReturn(List.of(
                json("m1", "2026-08-24T00:00:01Z")));

        var stored=row(1L,"m1","2026-08-24T00:00:01Z");
        stored.setContent("updated");stored.setMessageVersion(2L);
        when(mapper.selectOne(any())).thenReturn(stored);
        ChatMessageRecord result = service.upsert(
                "7", "s1", 10L, "assistant", "updated", Map.of("state", "done"), "m1");

        assertEquals("m1", result.messageId());
        assertEquals("updated", result.content());
        assertEquals(2,result.messageVersion());
        assertEquals(Instant.parse("2026-08-24T00:00:01Z"),result.createdAt());
        verify(mapper).upsertById(any(ChatMessageDO.class));
        verify(listOps).set(eq("7:session:s1:messages"), eq(0L), any());
        verify(listOps, never()).rightPush(eq("7:session:s1:messages"), any());
    }

    @Test
    void upsertAddsMissingHotRecordWithoutDuplicatingDatabaseRow() {
        when(listOps.range("7:session:s1:messages", 0, -1)).thenReturn(List.of());

        when(mapper.selectOne(any())).thenReturn(row(2L,"m2","2026-08-24T00:00:02Z"));
        ChatMessageRecord result = service.upsert("7", "s1", 10L, "assistant", "new", Map.of(), "m2");

        assertEquals("m2", result.messageId());
        verify(mapper).upsertById(any(ChatMessageDO.class));
        verify(listOps).rightPush(eq("7:session:s1:messages"), any());
    }

    @Test
    void upsertRejectsBlankMessageId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upsert("7", "s1", 10L, "assistant", "new", null, " "));
        verify(mapper, never()).upsertById(any(ChatMessageDO.class));
    }

    @Test
    void upsertRejectsIdentityConflictBeforeReadingOrCachingExistingMessage() {
        when(mapper.upsertById(any())).thenReturn(0);
        var error = assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> service.upsert("7", "s1", 10L, "assistant", "new", Map.of(), "other-message"));
        assertEquals(409, error.getStatus().value());
        verify(mapper, never()).selectOne(any());
        org.mockito.Mockito.verifyNoInteractions(listOps);
    }

    @Test
    void listAllReturnsTheDatabaseSessionLog() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "m1", "2026-08-24T00:00:01Z")));

        List<ChatMessageRecord> result = service.listAll("7", "s1");

        assertEquals(List.of("m1"), result.stream().map(ChatMessageRecord::messageId).toList());
    }

    @Test
    void malformedHotEntryFallsBackToTheDatabase() {
        when(listOps.range("7:session:s1:messages", -1, -1)).thenReturn(List.of("not-json"));
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "m1", "2026-08-24T00:00:01Z")));

        List<ChatMessageRecord> result = service.list("7", "s1", 1);

        assertEquals("m1", result.getFirst().messageId());
        verify(mapper).selectList(any());
    }

    @Test
    void missingOrTimelessHotEntriesFallBackToTheDatabase() throws Exception {
        when(listOps.range("7:session:s1:messages", -1, -1)).thenReturn(List.of(
                objectMapper.writeValueAsString(new ChatMessageRecord("m1", "assistant", "content", null, Map.of()))));
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "m1", "2026-08-24T00:00:01Z")));

        assertEquals("m1", service.list("7", "s1", 1).getFirst().messageId());

        when(listOps.range("7:session:s1:messages", -1, -1)).thenReturn(null);
        assertEquals("m1", service.list("7", "s1", 1).getFirst().messageId());
    }

    @Test
    void findByMessageIdReadsTenantScopedArchiveWithoutSessionFilter() {
        ChatMessageDO row = row(1L, "m1", "2026-08-24T00:00:01Z");
        row.setSessionId("s1");
        row.setRole("user");
        row.setContent("hello");
        when(mapper.selectOne(any())).thenReturn(row);

        MemoryService.ArchivedChatMessage found = service.findByMessageId("7", "m1");

        assertEquals("s1", found.sessionId());
        assertEquals("hello", found.record().content());
        assertEquals("user", found.record().role());
        assertEquals("m1", found.record().messageId());
    }

    @Test
    void findByMessageIdReturnsNullWhenBlankOrMissing() {
        assertNull(service.findByMessageId("7", " "));
        verify(mapper, never()).selectOne(any());
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(service.findByMessageId("7", "m-missing"));
    }

    @Test
    void acceptedReferencesInvalidateHotViewOnlyWhenArchiveWasFilled() {
        var references=List.of(new com.skillforge.core.chat.contract.ChatReferenceView("回复消息",
                new com.skillforge.core.chat.contract.ChatInputReference.Message(
                        new com.skillforge.core.chat.contract.ChatInputReference.SessionSource(),"m-source",1)));
        when(mapper.freezeInputReferences(7,"s1","input-1",references)).thenReturn(1);
        service.freezeInputReferences("7","s1","input-1",references);
        verify(redis).delete("7:session:s1:messages");
        org.mockito.Mockito.reset(redis);
        when(mapper.freezeInputReferences(7,"s1","input-1",references)).thenReturn(0);
        service.freezeInputReferences("7","s1","input-1",references);
        verify(redis,never()).delete(any(String.class));
    }

    private String json(String messageId, String createdAt) throws Exception {
        return objectMapper.writeValueAsString(new ChatMessageRecord(
                messageId, "assistant", "content", Instant.parse(createdAt), Map.of()));
    }

    private ChatMessageDO row(long id, String messageId, String createdAt) {
        ChatMessageDO row = new ChatMessageDO();
        row.setId(id);
        row.setMessageId(messageId);
        row.setRole("assistant");
        row.setContent("content");
        row.setCreatedAt(Instant.parse(createdAt));
        row.setMetadata(Map.of());
        return row;
    }
}
