package com.skillforge.core.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.skillforge.core.agent.management.AgentManagementContracts;
import com.skillforge.core.agent.mapper.AgentPublicationMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 请求摘要只排序对象字段，保留原 Update 的空值和集合语义。 */
class AgentPublicationRequestTest {
    private final AgentPublicationService service = new AgentPublicationService(mock(AgentService.class),
            mock(AgentPublicationMapper.class), mock(com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService.class),
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL),
            mock(org.springframework.beans.factory.ObjectProvider.class));

    @Test void fingerprintIgnoresNestedMapInsertionOrderAndIncludesActorAndTarget() {
        var first = new LinkedHashMap<String, Object>(); first.put("z", 1); first.put("a", Map.of("y", 2, "b", 3));
        var second = new LinkedHashMap<String, Object>(); second.put("a", Map.of("b", 3, "y", 2)); second.put("z", 1);
        String fingerprint = service.requestFingerprint(7, 9, "member:70", payload(first));
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertEquals(fingerprint, service.requestFingerprint(7, 9, "member:70", payload(second)));
        assertNotEquals(fingerprint, service.requestFingerprint(8, 9, "member:70", payload(first)));
        assertNotEquals(fingerprint, service.requestFingerprint(7, 10, "member:70", payload(first)));
        assertNotEquals(fingerprint, service.requestFingerprint(7, 9, "member:71", payload(first)));
    }

    @Test void fingerprintDistinguishesNullEmptyAndArrayOrderEvenWhenMapperNormallyOmitsNulls() {
        assertNotEquals(hash(null), hash(Map.of()));
        var nullable = new LinkedHashMap<String, Object>(); nullable.put("entry", null);
        assertNotEquals(hash(nullable), hash(Map.of()));
        assertNotEquals(hash(Map.of("items", List.of("a", "b"))), hash(Map.of("items", List.of("b", "a"))));
        assertNotEquals(hash(Map.of("items", List.of())), hash(Map.of()));
        assertNotEquals(hash(Map.of("value", new java.math.BigDecimal("1.000000000000000001"))),
                hash(Map.of("value", new java.math.BigDecimal("1.000000000000000002"))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ordinaryAndRequestPublicationsShareTheExactMicrosecondTimeInSnapshotInsertAndReceipt(boolean withRequest) throws Exception {
        var agents = mock(AgentService.class);
        var rows = mock(AgentPublicationMapper.class);
        var mounts = mock(com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService.class);
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var publishing = new AgentPublicationService(agents, rows, mounts, mapper,
                mock(org.springframework.beans.factory.ObjectProvider.class));
        var agent = new com.skillforge.core.agent.model.AgentDO();
        agent.setId(9L); agent.setTenantId(7L); agent.setName("time precision");
        when(mounts.read(7L, 9L)).thenReturn(
                new com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult(7L, 9L, 1, List.of()));
        when(agents.updateById(agent)).thenReturn(true);
        var snapshot = new java.util.concurrent.atomic.AtomicReference<String>();
        var insertedAt = new java.util.concurrent.atomic.AtomicReference<java.time.Instant>();
        org.mockito.stubbing.Answer<Integer> inserted = call -> {
            snapshot.set(call.getArgument(4)); insertedAt.set(call.getArgument(5)); return 1;
        };
        when(rows.insert(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), any())).thenAnswer(inserted);
        when(rows.insertRequest(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), any(), any(), anyString(), anyString()))
                .thenAnswer(inserted);
        var clockTime = java.time.Instant.parse("2026-10-30T00:53:26.510765500Z");
        var expected = java.time.Instant.parse("2026-10-30T00:53:26.510765Z");
        var request = new AgentPublicationService.RequestIdentity(java.util.UUID.randomUUID(), "a".repeat(64), "member:70");
        AgentPublicationService.Publication published;
        try (var clock = mockStatic(java.time.Instant.class, CALLS_REAL_METHODS)) {
            clock.when(java.time.Instant::now).thenReturn(clockTime);
            published = withRequest ? publishing.publish(agent, request) : publishing.publish(agent);
        }
        assertEquals(expected, published.publishedAt());
        assertEquals(expected, insertedAt.get());
        assertEquals(expected, agent.getPublishedAt());
        assertEquals(expected, java.time.Instant.parse(mapper.readTree(snapshot.get()).path("agent").path("publishedAt").asText()));
        if (withRequest) {
            var receipt = new AgentManagementContracts.SaveAndPublishReceipt(request.requestId(), agent.getId(),
                    published.revisionId(), published.revision(), published.publishedAt());
            assertEquals(insertedAt.get(), receipt.publishedAt());
        }
    }

    private String hash(Map<String, Object> config) { return service.requestFingerprint(7, 9, "member:70", payload(config)); }
    private AgentManagementContracts.Update payload(Map<String, Object> config) {
        return new AgentManagementContracts.Update("name", null, null, null, null, null, config, null, null);
    }
}
