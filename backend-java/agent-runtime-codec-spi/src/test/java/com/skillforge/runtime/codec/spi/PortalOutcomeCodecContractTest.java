package com.skillforge.runtime.codec.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 冻结私有 Codec SPI 的单一强类型入口，避免 adapter 另建镜像结果或散装函数。 */
class PortalOutcomeCodecContractTest {

    /** 六类结果编解码及 resolution 语义必须在同一无默认实现的接口上提供。 */
    @Test
    void exposesOnlySixStronglyTypedCodecPairs() {
        assertTrue(PortalOutcomeCodec.class.isInterface());
        assertFalse(PortalOutcomeCodec.class.isSealed());
        assertEquals(0, PortalOutcomeCodec.class.getTypeParameters().length);
        assertEquals(List.of(
                        "compareResolutions(PortalOutcomeSnapshot,PortalCallKind,PortalOutcomeSnapshot,PortalCallKind):ResolutionRelation",
                        "decodeKnowledge(PortalOutcomeSnapshot,PortalCallKind):KnowledgeOutcome",
                        "decodeModel(PortalOutcomeSnapshot,PortalCallKind):ModelResult",
                        "decodeTool(PortalOutcomeSnapshot,PortalCallKind):ToolOutcome",
                        "decodeWorkflow(PortalOutcomeSnapshot,PortalCallKind):WorkflowResult",
                        "decodeWorkflowControl(PortalOutcomeSnapshot,PortalCallKind):WorkflowControlResult",
                        "decodeWorkflowRecovery(PortalOutcomeSnapshot,PortalCallKind):WorkflowRecoveredResult",
                        "encodeKnowledge(PortalCallKind,KnowledgeOutcome):PortalOutcomeSnapshot",
                        "encodeModel(PortalCallKind,ModelResult):PortalOutcomeSnapshot",
                        "encodeTool(PortalCallKind,ToolOutcome):PortalOutcomeSnapshot",
                        "encodeWorkflow(PortalCallKind,WorkflowResult):PortalOutcomeSnapshot",
                        "encodeWorkflowControl(PortalCallKind,WorkflowControlResult):PortalOutcomeSnapshot",
                        "encodeWorkflowRecovery(PortalCallKind,WorkflowRecoveredResult):PortalOutcomeSnapshot",
                        "semantics(PortalOutcomeSnapshot,PortalCallKind):OutcomeSemantics"),
                Arrays.stream(PortalOutcomeCodec.class.getDeclaredMethods())
                        .peek(method -> {
                            assertTrue(Modifier.isPublic(method.getModifiers()), method.toString());
                            assertTrue(Modifier.isAbstract(method.getModifiers()), method.toString());
                            assertFalse(method.isDefault(), method.toString());
                            assertFalse(Modifier.isStatic(method.getModifiers()), method.toString());
                        })
                        .map(PortalOutcomeCodecContractTest::signature)
                        .sorted()
                        .toList());
        assertEquals(List.of("REQUIRED", "FORBIDDEN"),
                Arrays.stream(PortalOutcomeCodec.CanonicalObservationRule.values())
                        .map(Enum::name).toList());
        assertEquals(List.of("EQUIVALENT", "COMPATIBLE", "CONFLICT"),
                Arrays.stream(PortalOutcomeCodec.ResolutionRelation.values())
                        .map(Enum::name).toList());
    }

    /** 只压缩已冻结的 API 包前缀，保留每个强类型结果名称。 */
    private static String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return method.getName() + "(" + parameters + "):"
                + method.getReturnType().getSimpleName();
    }
}
