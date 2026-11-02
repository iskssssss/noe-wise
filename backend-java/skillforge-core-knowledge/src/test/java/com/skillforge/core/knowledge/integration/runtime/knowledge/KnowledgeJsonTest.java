package com.skillforge.core.knowledge.integration.runtime.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ID 与游标使用的原字符串元组必须保持逐字节稳定，避免同一冻结来源身份漂移。 */
class KnowledgeJsonTest {
    @Test
    void stableIdentityTuplesPreserveOrderEscapingAndUnicode() {
        assertEquals("[]", KnowledgeJson.encode(List.of()));
        assertEquals("[\"来源😀\",\"v1\",\"chunk\\\"\\\\\\u000a\"]",
                KnowledgeJson.encode(List.of("来源😀", "v1", "chunk\"\\\n")));
        assertEquals("[\"a\",\"b\"]", KnowledgeJson.encode(List.of("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeJson.encode(List.of("\uD83D")));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeJson.quote("\uDE00"));
    }
}
