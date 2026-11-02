package com.skillforge.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.pricing.CacheCostPrice.Mode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 缓存成本固定合同保留未知与零价，写入价不作为普通输入的附加费。 */
class ModelCachePricingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void normalizesExplicitPricesAndMapsOnlyCostFields() throws Exception {
        var configured = new ModelCachePricing(Mode.CACHE_READ_WRITE,
                new BigDecimal("0.123456"), new BigDecimal("12.500000"));
        assertThat(configured.writePricePerMillionCents()).isEqualTo(new BigDecimal("12.5"));
        assertThat(configured.toPrice().mode()).isEqualTo(Mode.CACHE_READ_WRITE);
        assertThat(configured.toPrice().readPricePerMillionCents()).isEqualTo(new BigDecimal("0.123456"));
        assertThat(configured.toPrice().writePricePerMillionCents()).isEqualTo(new BigDecimal("12.5"));
        var wire = json.readTree(json.writeValueAsString(configured));
        assertThat(wire.path("read_price_per_million_cents").textValue()).isEqualTo("0.123456");
        assertThat(wire.path("write_price_per_million_cents").textValue()).isEqualTo("12.5");
        assertThat(wire.has("readPricePerMillionCents")).isFalse();
        assertThat(json.treeToValue(wire, ModelCachePricing.class)).isEqualTo(configured);
    }

    @Test void unknownAndExplicitZeroPricesRemainDifferent() throws Exception {
        var unknown = json.readValue("""
                {"mode":"CACHE_READ_WRITE","read_price_per_million_cents":null,"write_price_per_million_cents":null}
                """, ModelCachePricing.class);
        var zero = json.readValue("""
                {"mode":"CACHE_READ_WRITE","read_price_per_million_cents":"0","write_price_per_million_cents":"0"}
                """, ModelCachePricing.class);
        assertThat(unknown.toPrice().readPricePerMillionCents()).isNull();
        assertThat(unknown.toPrice().writePricePerMillionCents()).isNull();
        assertThat(zero.toPrice().readPricePerMillionCents()).isEqualTo(BigDecimal.ZERO);
        assertThat(zero.toPrice().writePricePerMillionCents()).isEqualTo(BigDecimal.ZERO);
        assertThat(zero).isNotEqualTo(unknown);
    }

    @Test void modeIsRequiredAndReadOnlyModeRejectsAnyWritePrice() {
        assertThatThrownBy(() -> new ModelCachePricing(null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("model_cache_pricing_mode_required");
        for (var price : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE}) {
            assertThatThrownBy(() -> new ModelCachePricing(Mode.CACHE_READ, null, price))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("model_cache_write_price_not_applicable");
        }
        assertThat(new ModelCachePricing(Mode.CACHE_READ, null, null).toPrice().mode()).isEqualTo(Mode.CACHE_READ);
    }

    @Test void rejectsLossyNestedPriceWireValues() {
        for (String value : new String[]{"1", "\"-1\"", "\"0.0000001\"", "\"1e3\"",
                "\"9223372036854775808\"", "\"01.5\""}) {
            assertThatThrownBy(() -> json.readValue("{\"mode\":\"CACHE_READ\",\"read_price_per_million_cents\":"
                    + value + "}", ModelCachePricing.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
    }
}
