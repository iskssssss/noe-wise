package com.skillforge.core.agent.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillActivationMode;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CapabilityMount 请求只允许四类封闭的强类型配置。 */
class CapabilityMountRequestContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void snakeCasePayloadRoundTripsTypedConfig() throws Exception {
        String payload = """
                {
                  "capability_type": "SKILL",
                  "capability_definition_id": "100",
                  "capability_version_id": "101",
                  "skill_config": {
                    "activation_mode": "ALWAYS",
                    "token_budget": 1000
                  },
                  "priority": 3
                }
                """;

        CapabilityMountRequest request = mapper.readValue(payload, CapabilityMountRequest.class);

        assertThat(request.capabilityType()).isEqualTo(CapabilityType.SKILL);
        assertThat(request.skillConfig()).isEqualTo(
                new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1000L));
        assertThat(request.toolConfig()).isNull();
        assertThat(request.workflowConfig()).isNull();
        assertThat(request.knowledgeConfig()).isNull();
        assertThat(mapper.writeValueAsString(request)).contains("skill_config");
        var wire = mapper.valueToTree(request);
        assertThat(wire.path("capability_definition_id").isTextual()).isTrue();
        assertThat(wire.path("capability_definition_id").asText()).isEqualTo("100");
        assertThat(wire.path("capability_version_id").isTextual()).isTrue();
        assertThat(wire.path("capability_version_id").asText()).isEqualTo("101");
    }

    @Test
    void numericCapabilityIdentitiesAreRejected() {
        var valid = mapper.createObjectNode()
                .put("capability_type", "SKILL")
                .put("capability_definition_id", "100")
                .put("capability_version_id", "101");
        for (String field : new String[] {"capability_definition_id", "capability_version_id"}) {
            var payload = valid.deepCopy();
            payload.put(field, 100L);
            assertThatThrownBy(() -> mapper.treeToValue(payload, CapabilityMountRequest.class))
                    .isInstanceOf(JsonProcessingException.class);
        }
    }

    @Test
    void camelCaseConfigAliasIsRejected() {
        String payload = """
                {
                  "capability_type": "SKILL",
                  "capability_definition_id": "100",
                  "capability_version_id": "101",
                  "skillConfig": {
                    "activation_mode": "ALWAYS",
                    "token_budget": 1000
                  }
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(payload, CapabilityMountRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void freeFormOrUnknownConfigFieldsAreRejectedByJsonContract() {
        String freeForm = """
                {
                  "capability_type": "SKILL",
                  "capability_definition_id": "100",
                  "capability_version_id": "101",
                  "config": {"activation_mode": "ALWAYS"}
                }
                """;
        String unknownNestedField = """
                {
                  "capability_type": "SKILL",
                  "capability_definition_id": "100",
                  "capability_version_id": "101",
                  "skill_config": {
                    "activation_mode": "ALWAYS",
                    "token_budget": 1000,
                    "provider": "network"
                  }
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(freeForm, CapabilityMountRequest.class))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> mapper.readValue(unknownNestedField, CapabilityMountRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void requestHasNoMapConfigEscapeHatch() {
        assertThat(Arrays.stream(CapabilityMountRequest.class.getRecordComponents())
                .map(component -> component.getType())
                .filter(Map.class::isAssignableFrom))
                .isEmpty();
    }
}
