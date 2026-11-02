package com.skillforge.institution.group;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.io.IOException;

/** 群记忆治理输入；操作者与描述对象分别表达，Tenant/integration只来自认证。 */
public final class GroupMemoryContracts {
    private GroupMemoryContracts() { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="GroupMemoryCreate")
    public record Create(@JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String groupBindingId,
            @Schema(pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19)
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String operatorEndUserId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String agentId,
            @Schema(allowableValues={"GROUP","GROUP_MEMBER"})
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String scope,
            @Schema(types={"string","null"},pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19)
            @JsonDeserialize(using=StrictStringDeserializer.class) String subjectEndUserId,
            @JsonDeserialize(contentUsing=StrictStringDeserializer.class) @NotEmpty List<@NotBlank String> sourceMessageIds,
            @Schema(allowableValues={"preference","episode"})
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String type,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String subject,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String predicate,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String objectJson,
            @Schema(defaultValue="0") @JsonDeserialize(using=DefaultScore.class)
            @DecimalMin("0") @DecimalMax("1") double confidence,
            @Schema(defaultValue="0") @JsonDeserialize(using=DefaultScore.class)
            @DecimalMin("0") @DecimalMax("1") double importance,
            @Schema(defaultValue="false") @JsonDeserialize(using=DefaultBoolean.class) boolean reviewRequired) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="GroupMemoryReview")
    public record Review(@JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String groupBindingId,
            @Schema(pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19)
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String operatorEndUserId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String agentId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String factId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1")
            @JsonDeserialize(using=StrictLongDeserializer.class) @Positive long expectedRevision,
            @JsonDeserialize(using=StrictBoolean.class) @NotNull Boolean approved) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="GroupMemoryCorrect")
    public record Correct(@JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String groupBindingId,
            @Schema(pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19)
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String operatorEndUserId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String agentId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String factId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1")
            @JsonDeserialize(using=StrictLongDeserializer.class) @Positive long expectedRevision,
            @Schema(types={"string","null"}) @JsonDeserialize(using=StrictStringDeserializer.class) String subject,
            @Schema(types={"string","null"}) @JsonDeserialize(using=StrictStringDeserializer.class) String predicate,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String objectJson,
            @Schema(defaultValue="0") @JsonDeserialize(using=DefaultScore.class)
            @DecimalMin("0") @DecimalMax("1") double confidence,
            @Schema(defaultValue="0") @JsonDeserialize(using=DefaultScore.class)
            @DecimalMin("0") @DecimalMax("1") double importance) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="GroupMemoryRevision")
    public record Revision(@JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String groupBindingId,
            @Schema(pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19)
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String operatorEndUserId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String agentId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String factId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1")
            @JsonDeserialize(using=StrictLongDeserializer.class) @Positive long expectedRevision) { }

    /** 治理意图只接受真实 JSON 布尔值，不把字符串或整数隐式视作批准／拒绝。 */
    public static final class StrictBoolean extends JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser,DeserializationContext context)throws IOException {
            if(!parser.hasToken(JsonToken.VALUE_TRUE)&&!parser.hasToken(JsonToken.VALUE_FALSE))
                return (Boolean)context.handleUnexpectedToken(Boolean.class,parser);
            return parser.getBooleanValue();
        }
    }

    /** 省略评分沿用公开默认值；显式 null 或非数字不能伪装成缺省。 */
    public static final class DefaultScore extends JsonDeserializer<Double> {
        @Override public Double deserialize(JsonParser parser,DeserializationContext context)throws IOException {
            if(!parser.hasToken(JsonToken.VALUE_NUMBER_INT)&&!parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT))
                return (Double)context.handleUnexpectedToken(Double.class,parser);
            return parser.getDoubleValue();
        }
        @Override public Double getNullValue(DeserializationContext context)throws JsonMappingException {
            return context.reportInputMismatch(Double.class,"memory score must not be null");
        }
        @Override public Double getAbsentValue(DeserializationContext context) { return 0d; }
    }

    /** 缺省审核标记为 false；显式 null 不是审核决定。 */
    public static final class DefaultBoolean extends JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser,DeserializationContext context)throws IOException {
            if(!parser.hasToken(JsonToken.VALUE_TRUE)&&!parser.hasToken(JsonToken.VALUE_FALSE))
                return (Boolean)context.handleUnexpectedToken(Boolean.class,parser);
            return parser.getBooleanValue();
        }
        @Override public Boolean getNullValue(DeserializationContext context)throws JsonMappingException {
            return context.reportInputMismatch(Boolean.class,"memory review_required must not be null");
        }
        @Override public Boolean getAbsentValue(DeserializationContext context) { return false; }
    }

    /** 外部投影保留精确来源与治理状态；不暴露 Runtime owner、内部类型封装或数值用户 ID。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="GroupMemoryFact")
    public record FactView(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String factId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"GROUP","GROUP_MEMBER"}) String scope,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String subjectEndUserId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String type,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String subject,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String predicate,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String objectJson,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<@NotBlank String> sourceMessageIds,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<@NotBlank String> sourceEventIds,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String sourceSessionId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank String extractorVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1") long revision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant validFrom,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) Instant validTo,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="0",maximum="1") double confidence,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="0",maximum="1") double importance,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean reviewRequired,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String derivedFrom,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String supersededBy,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,
                    allowableValues={"ACTIVE","DECAYED","PENDING_CONFIRM","REJECTED","ARCHIVED","FORGOTTEN"}) String status) {
        public FactView {
            sourceMessageIds = List.copyOf(sourceMessageIds);
            sourceEventIds = List.copyOf(sourceEventIds);
        }

        public static FactView from(MemoryFact fact) {
            String groupId;
            String subjectId = null;
            if (fact.owner() instanceof MemoryOwner.Group group) {
                groupId = group.groupBindingRef().groupBindingId();
            } else if (fact.owner() instanceof MemoryOwner.GroupMember member) {
                groupId = member.groupBindingRef().groupBindingId();
                subjectId = Long.toString(member.subjectEndUserId().value());
            } else {
                throw new IllegalArgumentException("group memory projection requires a group owner");
            }
            List<String> events = fact.sourceEventIds().stream().map(value -> value.value()).toList();
            List<String> messages = events.stream().map(FactView::sourceMessageId).distinct().toList();
            return new FactView(fact.ref().factId(), groupId, fact.scope().name(), subjectId,
                    fact.type().value(), fact.subject(), fact.predicate(), fact.object().canonicalJson(),
                    messages, events, fact.sourceSessionRef().sessionId(), fact.extractorVersion(),
                    fact.revision(), fact.createdAt(), fact.validFrom(), fact.validTo().orElse(null),
                    fact.expiresAt().orElse(null), fact.confidence(), fact.importance(),
                    fact.reviewRequired(), fact.derivedFrom().map(value -> value.factId()).orElse(null),
                    fact.supersededBy().map(value -> value.factId()).orElse(null), fact.status().name());
        }

        private static String sourceMessageId(String eventId) {
            String prefix = "group-memory-source:";
            int separator = eventId.lastIndexOf(':');
            if (!eventId.startsWith(prefix) || separator <= prefix.length()
                    || !eventId.substring(separator + 1).matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("group memory source event identity is invalid");
            }
            return eventId.substring(prefix.length(), separator);
        }
    }
}
