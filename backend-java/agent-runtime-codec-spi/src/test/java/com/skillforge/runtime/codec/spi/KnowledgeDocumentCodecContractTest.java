package com.skillforge.runtime.codec.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 适配器只取得强类型编解码能力，不能从 SPI 获得 Core 实现或执行权限。 */
class KnowledgeDocumentCodecContractTest {
    @Test
    void exposesOnlyTypedDocumentOperationsWithNoDefaultImplementation() {
        assertTrue(KnowledgeDocumentCodec.class.isInterface());
        assertFalse(KnowledgeDocumentCodec.class.isSealed());
        assertEquals(List.of(
                        "decode(TypedPayload):KnowledgeDocumentResult",
                        "encode(KnowledgeDocumentResult):TypedPayload",
                        "encodeText(String):TypedPayload"),
                Arrays.stream(KnowledgeDocumentCodec.class.getDeclaredMethods())
                        .peek(method -> {
                            assertTrue(Modifier.isPublic(method.getModifiers()));
                            assertTrue(Modifier.isAbstract(method.getModifiers()));
                            assertFalse(method.isDefault());
                            assertFalse(Modifier.isStatic(method.getModifiers()));
                        })
                        .map(method -> method.getName() + "("
                                + method.getParameterTypes()[0].getSimpleName() + "):"
                                + method.getReturnType().getSimpleName())
                        .sorted().toList());
        assertEquals("skillforge.runtime.knowledge.document-access.v1", KnowledgeDocumentCodec.SCHEMA);
        assertEquals("skillforge.runtime.knowledge.document-slice.v1", KnowledgeDocumentCodec.TEXT_SCHEMA);
    }
}
