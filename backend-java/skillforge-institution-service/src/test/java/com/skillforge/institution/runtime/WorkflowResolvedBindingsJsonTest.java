package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WorkflowResolvedBindingsJsonTest {
    @Test void sourceVersionSetsIgnoreIterationOrderButKeepEverySourceAndVersion() {
        for (boolean snake : new boolean[]{false, true}) {
            String versions = snake ? "source_versions" : "sourceVersions";
            String source = snake ? "source_id" : "sourceId";
            String version = snake ? "source_version" : "sourceVersion";
            String one = "{\"" + source + "\":\"source-a\",\"" + version + "\":\"v1\"}";
            String two = "{\"" + source + "\":\"source-b\",\"" + version + "\":\"v2\"}";
            String prefix = "{\"knowledge\":[{\"target\":{\"" + versions + "\":[";
            String suffix = "]}}]}";
            String first = WorkflowResolvedBindingsJson.canonical(prefix + one + "," + two + suffix);
            assertEquals(first, WorkflowResolvedBindingsJson.canonical(prefix + two + "," + one + suffix));
            assertNotEquals(first, WorkflowResolvedBindingsJson.canonical(prefix + one + "," + two.replace("v2", "v3") + suffix));
            assertNotEquals(first, WorkflowResolvedBindingsJson.canonical(prefix + one + "," + two.replace("source-b", "source-c") + suffix));
            assertNotEquals(first, WorkflowResolvedBindingsJson.canonical(prefix + one + suffix));
        }
    }

    @Test void canonicalizesOnlyDeclaredSetsAndPreservesOrderedArraysAndStrings() {
        String first = """
                {"knowledge":[{"target":{"source_versions":[{"source_id":"s","source_version":"v2"},{"source_version":"v1","source_id":"s"}],"allowed_operators":["VECTOR","KEYWORD"]}}],
                 "tools":[{"target":{"sensitive_fields":["b","a"],"provider_requirements":["y","x"]}}],
                 "ordered":["b","a"],"opaque":"{\\\"order\\\":[2,1]}"}
                """;
        String second = first.replace("[\"VECTOR\",\"KEYWORD\"]", "[\"KEYWORD\",\"VECTOR\"]")
                .replace("[\"b\",\"a\"]", "[\"a\",\"b\"]").replace("\"ordered\":[\"a\",\"b\"]", "\"ordered\":[\"b\",\"a\"]")
                .replace("[\"y\",\"x\"]", "[\"x\",\"y\"]");
        String encoded = WorkflowResolvedBindingsJson.canonical(first);
        assertEquals(encoded, WorkflowResolvedBindingsJson.canonical(second));
        assertTrue(encoded.contains("\"ordered\":[\"b\",\"a\"]"));
        assertTrue(encoded.contains("{\\\"order\\\":[2,1]}"));
        assertNotEquals(encoded, WorkflowResolvedBindingsJson.canonical(first.replace("\"ordered\":[\"b\",\"a\"]", "\"ordered\":[\"a\",\"b\"]")));
        assertNotEquals(encoded, WorkflowResolvedBindingsJson.canonical(first.replace("v2", "v3")));
        assertNotEquals(encoded, WorkflowResolvedBindingsJson.canonical(first.replace("\"VECTOR\",", "")));
    }

    @Test void rejectsDuplicateSetMembersAndMalformedSetStructure() {
        for (String field : new String[]{"allowed_operators", "allowedOperators"}) {
            for (String value : new String[]{"[\"VECTOR\",\"VECTOR\"]", "null", "{}", "[3]", "[\"\"]"})
                assertThrows(IllegalArgumentException.class, () -> WorkflowResolvedBindingsJson.canonical(
                        "{\"knowledge\":[{\"target\":{\"" + field + "\":" + value + "}}]}"));
        }
        for (String value : new String[]{"[{}]", "[{\"source_id\":\"s\",\"source_version\":\"v\",\"extra\":1}]",
                "[{\"source_id\":\"s\",\"source_version\":\"v\"},{\"source_version\":\"v\",\"source_id\":\"s\"}]"})
            assertThrows(IllegalArgumentException.class, () -> WorkflowResolvedBindingsJson.canonical(
                    "{\"knowledge\":[{\"target\":{\"source_versions\":" + value + "}}]}"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowResolvedBindingsJson.canonical("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowResolvedBindingsJson.canonical("{\"x\":1,\"x\":2}"));
    }

    @Test void ordinaryFieldNamesCannotImpersonateSetPaths() {
        String slash = "{\"knowledge/*/target/allowed_operators\":[\"VECTOR\",\"KEYWORD\"]}";
        assertEquals(slash, WorkflowResolvedBindingsJson.canonical(slash));
        String star = "{\"knowledge\":{\"*\":{\"target\":{\"allowed_operators\":[\"VECTOR\",\"KEYWORD\"]}}}}";
        assertEquals(star, WorkflowResolvedBindingsJson.canonical(star));
    }
}
