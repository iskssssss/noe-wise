package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchDocument;
import com.skillforge.core.agent.memory.MemoryContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 灵知 宿主登记的最小生产 Memory 类型：偏好与情节，均带 schema 校验。 */
final class AgentRuntimeMemoryTypeRegistry implements MemoryTypeRegistry {
    private static final RetentionClassRef RETENTION = new RetentionClassRef("standard");
    private final Map<MemoryTypeRef, Registration> registrations;

    AgentRuntimeMemoryTypeRegistry() {
        this.registrations = Map.of(
                new MemoryTypeRef(AgentRuntimeHostContracts.MEMORY_PREFERENCE_TYPE),
                registration(
                        AgentRuntimeHostContracts.MEMORY_PREFERENCE_TYPE,
                        Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER,MemoryScope.GROUP,MemoryScope.GROUP_MEMBER),
                        AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD,
                        false),
                new MemoryTypeRef(AgentRuntimeHostContracts.MEMORY_EPISODE_TYPE),
                registration(
                        AgentRuntimeHostContracts.MEMORY_EPISODE_TYPE,
                        Set.of(MemoryScope.USER_AGENT,MemoryScope.GROUP,MemoryScope.GROUP_MEMBER),
                        AgentRuntimeHostContracts.MEMORY_EPISODE_PAYLOAD,
                        true));
    }

    @Override
    public Optional<Registration> find(MemoryTypeRef type) {
        return Optional.ofNullable(registrations.get(Objects.requireNonNull(type, "type")));
    }

    /** 索引派生材料只投影有效正文、条件和业务时间，不把归属、schema、JSON键名拼成正文。 */
    MemorySearchDocument project(MemoryFact fact) {
        var registration=find(fact.type()).orElseThrow(()->new IllegalArgumentException("memory_search_type_unregistered"));
        if(!(registration.validator().validate(fact.schemaVersion(),fact.object()) instanceof Validation.Allowed))
            throw new IllegalArgumentException("memory_search_payload_rejected");
        try {
            MemoryContent.Content body=MemoryContent.decode(fact.object().canonicalJson());
            var text=new java.util.ArrayList<String>();
            text.add(fact.predicate());
            scalarValues(body.value(),text);
            text.addAll(body.conditions());
            var temporal=body.temporal();
            if(temporal.from()!=null)text.add(temporal.from().toString());
            if(temporal.to()!=null)text.add(temporal.to().toString());
            return new MemorySearchDocument(String.join("\n",text),MemorySearchDocument.VERSION);
        }catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("memory_search_payload_rejected",invalid);
        }
    }

    private static void scalarValues(JsonNode node,List<String> text) {
        if(node==null||node.isNull())return;
        if(node.isTextual()) {if(!node.textValue().isBlank())text.add(node.textValue());}
        else if(node.isNumber()||node.isBoolean())text.add(node.asText());
        else if(node.isContainerNode())node.forEach(child->scalarValues(child,text));
    }

    private static Registration registration(
            String type, Set<MemoryScope> scopes, String payloadSchema, boolean recentEpisodic) {
        MemoryTypeRef ref = new MemoryTypeRef(type);
        return new Registration(
                ref, scopes, List.of(new Schema(AgentRuntimeHostContracts.MEMORY_SCHEMA_VERSION, payloadSchema)), RETENTION,
                recentEpisodic, false, (schemaVersion, payload) -> validate(payloadSchema, schemaVersion, payload));
    }

    private static Validation validate(String expectedSchema, String schemaVersion, TypedPayload payload) {
        if (!AgentRuntimeHostContracts.MEMORY_SCHEMA_VERSION.equals(schemaVersion) || payload == null
                || !expectedSchema.equals(payload.schemaId())) return new Validation.Rejected("memory_payload_rejected");
        try {
            MemoryContent.decode(payload.canonicalJson());
            return new Validation.Allowed();
        } catch (IllegalArgumentException invalid) {
            return new Validation.Rejected("memory_payload_rejected");
        }
    }
}
