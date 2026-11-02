package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 生产类型只接收v2受校验正文，检索排除结构键与治理引用。 */
class AgentRuntimeMemoryTypeRegistryTest {
    private final MemoryTypeRegistry registry = new AgentRuntimeMemoryTypeRegistry();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void registersPreferenceAndEpisode() {
        var preference = registry.find(new MemoryTypeRef("preference")).orElseThrow();
        assertTrue(preference.allowedScopes().containsAll(List.of(MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER)));
        assertEquals(List.of(new MemoryTypeRegistry.Schema("v2", AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD)), preference.schemas());
        assertTrue(registry.find(new MemoryTypeRef("episode")).orElseThrow().recentEpisodic());
        assertTrue(registry.find(new MemoryTypeRef("unknown")).isEmpty());
    }

    @Test
    void validatesActualEnvelopeAndRejectsLegacyMalformedOrInconsistentPayload() throws Exception {
        var preference = registry.find(new MemoryTypeRef("preference")).orElseThrow();
        String valid = MemoryContent.encode(MemoryContent.explicitContent(json.readTree("{\"text\":\"下午联系\"}"), "preference"));
        assertInstanceOf(MemoryTypeRegistry.Validation.Allowed.class,
                preference.validator().validate("v2", new TypedPayload(AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD, valid)));
        assertInstanceOf(MemoryTypeRegistry.Validation.Rejected.class,
                preference.validator().validate("v1", new TypedPayload(AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD, valid)));
        assertInstanceOf(MemoryTypeRegistry.Validation.Rejected.class,
                preference.validator().validate("v2", new TypedPayload("skillforge.memory.preference.v1", valid)));
        for (String invalid : List.of(
                "{not-json}", "{\"text\":\"旧正文\"}", valid + " {}",
                "{\"value\":{},\"value\":{},\"conditions\":[],\"temporal\":{\"kind\":\"stable\",\"from\":null,\"to\":null}}",
                valid.replace("stable", "unexpected"),
                valid.replace("\"conditions\":[]", "\"conditions\":\"出差时\""),
                valid.replace("\"from\":null", "\"from\":\"2026-10-23T00:00:00Z\""),
                valid.replace("\"value\":{\"text\":\"下午联系\"}", "\"value\":[]"))) {
            assertEquals("memory_payload_rejected", ((MemoryTypeRegistry.Validation.Rejected)
                    preference.validator().validate("v2", new TypedPayload(AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD, invalid))).reasonCode(), invalid);
        }
    }

    @Test
    void searchProjectionPreservesBusinessRelationValueConditionsAndIntervalButExcludesGovernance() throws Exception {
        var tenant = new TenantId("123"); var session = new SessionRef(tenant,"internal-session");
        var content = MemoryContent.decode("{\"value\":{\"channel\":\"电子邮件\",\"nested\":{\"text\":\"下午\",\"enabled\":true},\"tags\":[\"中文\",2]},\"conditions\":[\"出差时\"],\"temporal\":{\"kind\":\"interval\",\"from\":\"2026-10-23T00:00:00Z\",\"to\":null}}");
        content = MemoryContent.withRevisionProposal(content, "internal-replaced-fact", 7);
        var fact = new MemoryFact(new MemoryFactRef(tenant,"internal-fact"), new MemoryOwner.User(tenant,new EndUserId(7)),
                new MemoryTypeRef("preference"),new RetentionClassRef("standard"),"v2","user:7","followup_time",
                new TypedPayload(AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD,MemoryContent.encode(content)),
                List.of(new EventId("internal-source")),session,Optional.empty(),Optional.empty(),"internal-extractor",1,
                Instant.EPOCH,Instant.EPOCH,Optional.empty(),Optional.empty(),0.9,0.7,false,Optional.empty(),Optional.empty(),0,FactStatus.ACTIVE);
        var projection = ((AgentRuntimeMemoryTypeRegistry) registry).project(fact);
        assertEquals("memory-body-v1",projection.projectionVersion());
        assertEquals("followup_time\n电子邮件\ntrue\n下午\n中文\n2\n出差时\n2026-10-23T00:00:00Z", projection.text());
        for(String excluded : List.of("internal", "channel", "user:7", "skillforge.memory", "revisionProposal", "expectedRevision", "interval", "conditions"))
            assertFalse(projection.text().contains(excluded),excluded);
    }
}
