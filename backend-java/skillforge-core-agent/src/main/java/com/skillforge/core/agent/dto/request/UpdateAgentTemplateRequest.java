package com.skillforge.core.agent.dto.request;

import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.util.AccessPattern;
import java.io.IOException;
import java.util.List;

/** 平台模板编辑请求；定义为完整对象替换，草稿允许尚未选择模型。 */
public record UpdateAgentTemplateRequest(
        String name,
        String category, List<String> tags, String description, String iconUrl,
        String priceType, Integer priceCents, AgentTemplateDefinition definition,
        @JsonDeserialize(using = OptionalDistributionPolicyDeserializer.class)
        AgentDistributionPolicy distributionPolicy) {

    /** Record 缺失组件保持现值；显式 null 不得清空服务端分发策略。 */
    public static final class OptionalDistributionPolicyDeserializer extends StdDeserializer<AgentDistributionPolicy> {
        public OptionalDistributionPolicyDeserializer() { super(AgentDistributionPolicy.class); }

        @Override
        public AgentDistributionPolicy deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (AgentDistributionPolicy) context.handleUnexpectedToken(AgentDistributionPolicy.class, parser);
            }
            String value = parser.getText();
            try {
                return AgentDistributionPolicy.valueOf(value);
            } catch (IllegalArgumentException failure) {
                return (AgentDistributionPolicy) context.handleWeirdStringValue(AgentDistributionPolicy.class, value,
                        "distribution_policy 必须为 OPEN_INSTALL 或 AUTHORIZED_DEPLOY");
            }
        }

        @Override
        public AgentDistributionPolicy getAbsentValue(DeserializationContext context) { return null; }

        @Override
        public AgentDistributionPolicy getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(AgentDistributionPolicy.class, "distribution_policy 不允许显式 null");
        }

        @Override
        public AccessPattern getNullAccessPattern() { return AccessPattern.DYNAMIC; }
    }
}
