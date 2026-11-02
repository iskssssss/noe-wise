package com.skillforge.institution.group;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.List;

/** 中央发送资格只核正式final/根边界；实际DISPATCHING仍归发送端持久事务。 */
public final class GroupDeliveryContracts {
    private GroupDeliveryContracts() { }
    /** 稳定delivery不能换绑消息或进程世代绕过去重。 */
    @Schema(name="GroupDeliveryQualify") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Qualify(@NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String runId,
            @NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String messageId,@NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long messageVersion,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String deliveryId,
            @NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String endpointId,@NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long generation) { }
    /** 不足资格时明确返回原因与未覆盖边界，正文来自唯一正式公开投影。 */
    @Schema(name="GroupDeliveryQualification") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Qualification(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean eligible,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String deliveryId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String runId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String rootTriggerId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long triggerSequence,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String messageId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long messageVersion,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String content,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String contentDigest,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long controlRevision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> blockedBoundaryRefs) { }
}
