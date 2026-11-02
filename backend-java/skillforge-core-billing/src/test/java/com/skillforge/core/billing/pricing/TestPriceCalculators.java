package com.skillforge.core.billing.pricing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.pricing.ModelPriceSource.Key;
import com.skillforge.core.billing.pricing.ModelPriceSource.Price;
import com.skillforge.core.billing.pricing.ModelPriceSource.Snapshot;
import java.math.BigDecimal;
import java.util.Map;

/** 计量测试使用的平台模型价格快照夹具。 */
public final class TestPriceCalculators {
    private TestPriceCalculators() {
    }

    public static PriceCalculator priced(
            String provider, String model, long input, long output, String currency) {
        ModelPriceSource pricing = mock(ModelPriceSource.class);
        when(pricing.snapshot(any(), any())).thenReturn(new Snapshot("model-pricing-test", Map.of(
                new Key(provider, model), new Price(BigDecimal.valueOf(input),
                        BigDecimal.valueOf(output), null, currency, null, null, null))));
        return new PriceCalculator(pricing);
    }

    public static PriceCalculator unpriced() {
        ModelPriceSource pricing = mock(ModelPriceSource.class);
        when(pricing.snapshot(any(), any())).thenReturn(new Snapshot("model-pricing-test", Map.of()));
        return new PriceCalculator(pricing);
    }
}
