package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.json.RuntimeJson;
import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.TypedPayload;
import java.util.Map;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** before/fact原revision仍可供生命周期SQL观察，大正文恢复后公共v2全字节保持不变。 */
class SessionMemoryEventStorageTest {
    @SuppressWarnings("unchecked")
    @Test void independentProductionWrittenGoldenPreservesAllMetadataWhenBeforeAndFactBodyGrow() throws Exception {
        String golden;
        try (var resource=getClass().getResourceAsStream("/storage/memory-written-transition-golden.json")) {
            assertNotNull(resource); golden=new String(resource.readAllBytes(),StandardCharsets.UTF_8);
        }
        var original=new TypedPayload(SessionMemoryEventStorage.SCHEMA,golden);
        assertEquals(original,SessionMemoryEventStorage.freeze("memory.written",original).compact());
        var root=(Map<String,Object>)RuntimeJson.parseCanonical(golden,new CodecLimits(golden.length(),golden.length(),128));
        var changed=new TreeMap<String,Object>(root);
        String content=RuntimeJson.writeCanonical(Map.of("a","雪😀\\\n\0".repeat(2_000),"z","line\nnext"));
        for (String field : java.util.List.of("before","fact")) {
            var fact=new TreeMap<String,Object>((Map<String,Object>)root.get(field));
            fact.put("object",Map.of("canonicalJson",content,"schemaId","fact.payload.v1"));
            changed.put(field,fact);
        }
        var large=new TypedPayload(original.schemaId(),RuntimeJson.writeCanonical(changed));
        var frozen=SessionMemoryEventStorage.freeze("memory.written",large);
        assertEquals(1,frozen.bodies().size());
        assertTrue(frozen.compact().canonicalJson().contains("\"expectedRevision\":\"9007199254740993\""));
        assertTrue(frozen.compact().canonicalJson().contains("\"revision\":\"9007199254740993\""));
        assertTrue(frozen.compact().canonicalJson().contains("\"sourceEventIds\":[\"source-一\",\"source-二\"]"));
        assertEquals(large,SessionMemoryEventStorage.restore(frozen.compact(),frozen.bodies()::get,frozen.originalDigest()));
    }
    @Test void duplicateBeforeAndAfterContentHasOneOwnerBodyAndPreservesExactRevisionFields() {
        String content = RuntimeJson.writeCanonical(Map.of("content","冻结记忆😀\\\n".repeat(2_000)));
        var payload = new TypedPayload(SessionMemoryEventStorage.SCHEMA, RuntimeJson.writeCanonical(Map.of(
                "before",fact(content,"7"),"fact",fact(content,"8"),"operation","TRANSITION",
                "command",Map.of("fact",Map.of("tenantId","t","factId","fact"),"expectedRevision","7"))));
        var frozen = SessionMemoryEventStorage.freeze(SessionMemoryEventStorage.EVENT_TYPE,payload);
        assertEquals(1,frozen.bodies().size());
        assertTrue(frozen.compact().canonicalJson().contains("\"revision\":\"7\""));
        assertTrue(frozen.compact().canonicalJson().contains("\"revision\":\"8\""));
        assertTrue(frozen.compact().canonicalJson().contains("\"ref\":{\"factId\":\"fact\",\"tenantId\":\"t\"}"));
        assertEquals(payload,SessionMemoryEventStorage.restore(frozen.compact(),frozen.bodies()::get,frozen.originalDigest()));
        assertThrows(IllegalStateException.class,() -> SessionMemoryEventStorage.freeze(SessionMemoryEventStorage.EVENT_TYPE,frozen.compact()));
        assertThrows(IllegalStateException.class,() -> SessionMemoryEventStorage.restore(frozen.compact(),ignored -> null,frozen.originalDigest()));
        assertThrows(IllegalStateException.class,() -> SessionMemoryEventStorage.restore(frozen.compact(),ignored -> "\"changed\"",frozen.originalDigest()));
    }
    private static Map<String,Object> fact(String content,String revision) {
        return Map.of("ref",Map.of("tenantId","t","factId","fact"),"revision",revision,
                "object",Map.of("schemaId","memory.preference.v1","canonicalJson",content));
    }
}
