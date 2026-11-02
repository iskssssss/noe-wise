package com.skillforge.core.capability.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionToolDefinitionsTest {
    /** 程序纠正和后续步骤按参数区分新请求；政策随真实发布描述冻结，不授予幂等或并行资格。 */
    @Test void publishesArgumentIdentityForDifferentProgramRequests() {
        var schemas = new ToolSchemaContributor() {
            @Override public Map<String, Object> codePublicPresentationSchema() { return Map.of("type", "object"); }
            @Override public PlatformToolCatalog.Entry contextReferenceEntry() { throw new AssertionError("not used"); }
        };
        var entry = ExecutionToolDefinitions.runCode(schemas);
        assertEquals("builtin:22", entry.publishedVersionKey());
        assertEquals(ToolCallIdentityPolicy.ARGUMENTS, entry.callIdentityPolicy());
        assertEquals("ARGUMENTS", entry.descriptor().get("call_identity_policy"));
        assertEquals(ToolEffect.NON_IDEMPOTENT_WRITE, entry.effect());
        assertFalse(entry.idempotent());
        assertFalse(entry.parallelSafe());
    }
}
