package com.skillforge.institution.group;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.shared.web.DecimalStringIdDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;

/** 群级显式提取开关与首次执行 Agent 选择，不改变已登记逻辑任务的执行归属。 */
public final class GroupMemoryExtractionPolicy {
    private GroupMemoryExtractionPolicy() { }

    @Schema(name="GroupMemoryExtractionPolicyCommand")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Command(@NotBlank @Size(max=19) @Pattern(regexp="[1-9][0-9]{0,18}") @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,
            @Schema(implementation=String.class,types={"string"},pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19)
            @NotNull @Positive @JsonDeserialize(using=DecimalStringIdDeserializer.class) @JsonSerialize(using=ToStringSerializer.class) Long operatorEndUserId,
            @NotNull @JsonDeserialize(using=StrictEnabledDeserializer.class) Boolean enabled,
            @Schema(implementation=String.class,types={"string"},pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19)
            @NotNull @Positive @JsonDeserialize(using=DecimalStringIdDeserializer.class) @JsonSerialize(using=ToStringSerializer.class) Long extractorAgentId,
            @NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long expectedRevision) { }

    @Schema(name="GroupMemoryExtractionPolicyView")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,types={"string","null"}) String extractorAgentId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision) { }

    /** 与既有严格布尔协议一致：显式开关只接受 JSON boolean，不接受文本或数字强转。 */
    public static final class StrictEnabledDeserializer extends JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            return switch(parser.currentToken()) {
                case VALUE_TRUE -> Boolean.TRUE;
                case VALUE_FALSE -> Boolean.FALSE;
                default -> (Boolean) context.handleUnexpectedToken(Boolean.class,parser);
            };
        }
    }
}
