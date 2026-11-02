package com.skillforge.core.billing.metering;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.persistence.CostEventMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class CostRecorderExternalTest {

    @Test
    void recordsKnowledgeAndWorkflowOperationsOnlyThroughTheirDedicatedTypes() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        PriceCalculator prices = mock(PriceCalculator.class);
        CostRecorder recorder = new CostRecorder(mapper, prices);
        when(prices.calculate(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PriceCalculator.Amounts(
                        null, null, null, "CNY", null, null, null, "model-pricing-test", null));

        recorder.recordExternal("7", CostType.KNOWLEDGE_SOURCE, "knowledge_runtime", "kb-1",
                CostContext.builder().eventId("request-1:knowledge:q1").requestId("input-1")
                        .callPoint("knowledge.source").build(), Map.of("outcome", "succeeded"));
        recorder.recordExternal("7", CostType.WORKFLOW_EXTERNAL, "connector", "tool-1",
                CostContext.builder().eventId("action-1:usage").requestId("input-1")
                        .callPoint("workflow.external").build(), Map.of("outcome", "failed"));

        verify(mapper, org.mockito.Mockito.times(2)).insert(any(CostEventDO.class));
    }
}
