package com.skillforge.core.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.pricing.MediaCostPrice.Unit;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MediaCostPricingTest {
    @Test void unitsAreExplicitAndBelongToTheirActualInputOrOutputKind() {
        var allowed = Map.of(
                "IMAGE_GENERATION", Set.of(Unit.OUTPUT_IMAGE, Unit.TOKENS),
                "VIDEO_GENERATION", Set.of(Unit.OUTPUT_VIDEO, Unit.VIDEO_SECOND, Unit.TOKENS),
                "SPEECH_RECOGNITION", Set.of(Unit.INPUT_AUDIO_SECOND, Unit.TOKENS),
                "SPEECH_SYNTHESIS", Set.of(Unit.BILLING_CHARACTER, Unit.OUTPUT_AUDIO_SECOND, Unit.TOKENS));
        for (var unit : Unit.values()) {
            var pricing = price(unit);
            for (var kind : allowed.keySet()) {
                if (allowed.get(kind).contains(unit)) assertDoesNotThrow(() -> pricing.requireKind(kind));
                else assertThrows(IllegalArgumentException.class, () -> pricing.requireKind(kind));
            }
            assertThrows(IllegalArgumentException.class, () -> pricing.requireKind("LLM"));
            assertEquals(unit.name(), pricing.toPrice().unit().name());
        }
    }

    @Test void decimalStringPricesRoundTripWithoutJavascriptLossOrScaleDrift() throws Exception {
        var json = new ObjectMapper();
        var price = new MediaCostPricing(Unit.VIDEO_SECOND, new BigDecimal("12.500000"), null, null, "720P");
        var wire = json.readTree(json.writeValueAsString(price));
        assertTrue(wire.path("price_per_unit_cents").isTextual());
        assertEquals("12.5", wire.path("price_per_unit_cents").textValue());
        assertEquals(price, json.treeToValue(wire, MediaCostPricing.class));
        assertEquals(new BigDecimal("12.5"), price.toPrice().pricePerUnitCents());
        for (String invalid : new String[]{"1.5", "\"-1\"", "\"0.0000001\"", "\"9223372036854775808\"", "\"1e3\""}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> json.readValue(
                    "{\"unit\":\"OUTPUT_IMAGE\",\"price_per_unit_cents\":" + invalid + "}", MediaCostPricing.class));
        }
    }

    @Test void tokenPricesAndResolutionNeverSilentlyDiscardAnInapplicableField() {
        assertThrows(IllegalArgumentException.class, () -> new MediaCostPricing(Unit.TOKENS, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaCostPricing(Unit.TOKENS, null, BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaCostPricing(Unit.OUTPUT_IMAGE, BigDecimal.ONE, BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaCostPricing(Unit.OUTPUT_IMAGE, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaCostPricing(Unit.VIDEO_SECOND, BigDecimal.ONE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaCostPricing(Unit.VIDEO_SECOND, BigDecimal.ONE, null, null, "4K"));
        assertThrows(IllegalArgumentException.class, () -> new MediaCostPricing(Unit.OUTPUT_VIDEO, BigDecimal.ONE, null, null, "720P"));
        assertDoesNotThrow(() -> new MediaCostPricing(Unit.OUTPUT_IMAGE, BigDecimal.ZERO, null, null, null));
    }

    private static MediaCostPricing price(Unit unit) {
        return unit == Unit.TOKENS
                ? new MediaCostPricing(unit, null, BigDecimal.ONE, new BigDecimal("0.000001"), null)
                : new MediaCostPricing(unit, new BigDecimal("0.000001"), null, null, unit == Unit.VIDEO_SECOND ? "720P" : null);
    }
}
