package com.skillforge.runtime.adapter.jdbc.memory.search;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MemorySearchDocumentTest {
    @Test
    void preservesHostProvidedBodyAndExplicitProjectionVersionWithoutJsonInterpretation() {
        String body = "偏好：先风险后收益，保留 AbC、数组顺序和🌱。";
        var document = new MemorySearchDocument(body, MemorySearchDocument.VERSION);
        assertEquals("memory-body-v1", document.projectionVersion());
        assertEquals(body, document.text());
        assertEquals("", new MemorySearchDocument("", MemorySearchDocument.VERSION).text());
        assertThrows(NullPointerException.class, () -> new MemorySearchDocument(null, MemorySearchDocument.VERSION));
        assertThrows(IllegalArgumentException.class, () -> new MemorySearchDocument(body, " "));
    }
}
