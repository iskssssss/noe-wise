package com.skillforge.institution.group;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import com.skillforge.institution.group.GroupControlContracts.Flight;

/** 发送端点逻辑身份、进程世代与持久隔离证据；principal始终由服务端绑定。 */
public final class GroupSenderContracts {
    private GroupSenderContracts() { }
    /** 同一逻辑端点换代必须接续同一可恢复ledger，不能换空库绕过去重。 */
    @Schema(name="GroupSenderRegister") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Register(@NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String endpointId,
            @NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long generation,@NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long expectedGeneration,@NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String ledgerRef) { }
    /** 原在途不撤销；新代证明在共享ledger中已经隔离旧代后才能提交。 */
    @Schema(name="GroupSenderRetire") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Retire(@NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String endpointId,
            @NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long oldGeneration,@NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long newGeneration,@NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String ledgerRef,
            @NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long fenceRevision,@NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String retirementReceiptRef,@NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String appliedBoundaryRef,
            @NotNull @Size(max=1000) List<@Valid Flight> inFlight) { }
    /** ACK只能由注册时绑定的固定principal提交，不接受正文身份自证。 */
    @Schema(name="GroupSenderAck") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ack(@NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String endpointId,
            @NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long generation,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String commandId,@NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long controlRevision,
            @NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String ledgerRef,@NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String appliedBoundaryRef,@NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long fenceRevision,
            @NotNull @Size(max=1000) List<@Valid Flight> inFlight) { }
    /** status与最后应用版本独立；RETIRING/REGISTERING不能取得新发送资格。 */
    @Schema(name="GroupSenderEndpoint") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Endpoint(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String endpointId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long generation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String ledgerRef,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long appliedRevision,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long fenceRevision,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String retirementReceiptRef,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) String lastConfirmedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"UNKNOWN"}) String connectionState) { }
    /** 注册不等于启用：还须隔离旧代、应用当前快照并ACK。 */
    @Schema(name="GroupSenderRegistration") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Registration(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String endpointId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long generation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String ledgerRef,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long controlRevision,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Long> retiringGenerations,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) GroupControlContracts.Current currentState) { }
}
