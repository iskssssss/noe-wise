package com.skillforge.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** HTTP 小数分价格往返；拒绝 number、溢出和超精度，不默默舍入。 */
class ModelPriceJsonTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test void timePolicyUsesStructuredConfigurationAndExactDecimalStrings() throws Exception {
        var policy = TimePricingTestPolicies.configured(List.of());
        String wire = "{\"time_pricing\":" + json.writeValueAsString(policy) + "}";
        var write = json.readValue(wire, ModelWrite.class);
        assertThat(write.timePricing()).isEqualTo(policy);
        var view = json.readValue(wire, ModelView.class);
        assertThat(json.valueToTree(view).path("time_pricing").path("off_peak_multiplier").textValue()).isEqualTo("0.5");
        assertThat(json.readValue("{}", ModelWrite.class).timePricing()).isNull();
        assertThat(json.readValue("{\"time_pricing\":null}", ModelWrite.class).timePricing()).isNull();
        for (String value : List.of("\"DEEPSEEK_CN_2026_V1\"", "0", "true", "{}", "[]")) {
            assertThatThrownBy(() -> json.readValue("{\"time_pricing\":" + value + "}", ModelWrite.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
        String numericMultiplier = wire.replace("\"off_peak_multiplier\":\"0.5\"", "\"off_peak_multiplier\":0.5");
        assertThatThrownBy(() -> json.readValue(numericMultiplier, ModelWrite.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test void preservesLongMaximumAndIntegersAboveJavascriptPrecision() throws Exception {
        String wire = """
                {"input_price_per_million_cents":"9223372036854775807",
                 "output_price_per_million_cents":"9007199254740993","price_per_quantity_cents":"0"}
                """;
        var write = json.readValue(wire, ModelWrite.class);
        assertThat(write.inputPricePerMillionCents()).isEqualByComparingTo("9223372036854775807");
        assertThat(write.outputPricePerMillionCents()).isEqualByComparingTo("9007199254740993");
        var response = json.readTree(json.writeValueAsString(json.readValue(wire, ModelView.class)));
        assertThat(response.path("input_price_per_million_cents").isTextual()).isTrue();
        assertThat(response.path("output_price_per_million_cents").textValue()).isEqualTo("9007199254740993");
        assertThat(response.path("price_per_quantity_cents").textValue()).isEqualTo("0");
        assertThat(json.readValue("{}", ModelWrite.class).inputPricePerMillionCents()).isNull();
    }

    @Test void preservesFractionalCentsAndNormalizesTrailingZeros() throws Exception {
        String wire = """
                {"input_price_per_million_cents":"12.500000",
                 "output_price_per_million_cents":"0.000001","price_per_quantity_cents":null}
                """;
        var write = json.readValue(wire, ModelWrite.class);
        assertThat(write.inputPricePerMillionCents()).isEqualTo(new BigDecimal("12.5"));
        assertThat(write.outputPricePerMillionCents()).isEqualTo(new BigDecimal("0.000001"));
        assertThat(write.pricePerQuantityCents()).isNull();
        var response = json.readTree(json.writeValueAsString(json.readValue(wire, ModelView.class)));
        assertThat(response.path("input_price_per_million_cents").textValue()).isEqualTo("12.5");
        assertThat(response.path("output_price_per_million_cents").textValue()).isEqualTo("0.000001");
    }

    @Test void rejectsLossyOrInvalidInput() {
        for (String value : new String[]{"1", "1.5", "\"-1\"", "\"0.0000001\"", "\"1e3\"",
                "\"9223372036854775808\"", "\"9223372036854775807.000001\"", "\"01.5\"", "\" 12.5\""}) {
            assertThatThrownBy(() -> json.readValue("{\"input_price_per_million_cents\":" + value + "}", ModelWrite.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
    }
}
