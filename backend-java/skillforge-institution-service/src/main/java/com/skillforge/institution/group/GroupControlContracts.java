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

/** 群控制的命令、实际参与方与精确消息放行；身份只取固定SP认证上下文。 */
public final class GroupControlContracts {
    private GroupControlContracts() { }
    /** 同键内容不可变；恢复是独立新命令，scopes不会由聊天文本或默认值补造。 */
    @Schema(name="GroupControlCommand") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Command(@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String commandId,
            @NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,
            @Schema(types={"string","null"}) @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String runId,
            @NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long expectedRevision,
            @NotBlank @Schema(allowableValues={"paused","running"}) @JsonDeserialize(using=StrictStringDeserializer.class) String desiredState,
            @NotEmpty @Size(min=1,max=4) @JsonDeserialize(contentUsing=StrictStringDeserializer.class) List<@Pattern(regexp="execution|business_write|send|memory_extraction") String> scopes) { }
    /** 零副作用地复验本地操作者映射的当前精确控制权限。 */
    @Schema(name="GroupControlAuthorize") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Authorize(@NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,
            @NotBlank @Schema(allowableValues={"write","read","reconcile","release","ack","manage"}) @JsonDeserialize(using=StrictStringDeserializer.class) String action) { }
    /** 当前认证而非正文自称的管理主体及可信根登记边界。 */
    @Schema(name="GroupControlAuthorization") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Authorization(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String action,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String principalId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean authorized,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long controlRevision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long latestTriggerSequence) { }
    /** 未受理终结与命令提交共用锁；run_id限定原本地意图范围，不允许随后改换。 */
    @Schema(name="GroupControlTerminalize") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Terminalize(@NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String commandId,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") @JsonDeserialize(using=StrictStringDeserializer.class) String fingerprint,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String localIntentId,
            @Schema(types={"string","null"}) @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String runId,@NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long expectedRevision) { }
    /** 一项既有正式消息版本，不允许对未来任意结果预授权。 */
    @Schema(name="GroupReleaseMessage") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReleaseMessage(@NotBlank @Size(min=1,max=256) @JsonDeserialize(using=StrictStringDeserializer.class) String messageId,@NotNull @Min(1) @JsonDeserialize(using=StrictLongDeserializer.class) Long messageVersion,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String deliveryId) { }
    /** 精确覆盖既有阻止边界；更晚暂停和其他消息不受该回执放行。 */
    @Schema(name="GroupControlRelease") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Release(@NotBlank @Size(min=1,max=64) @JsonDeserialize(using=StrictStringDeserializer.class) String groupBindingId,@NotBlank @Size(min=1,max=128) @JsonDeserialize(using=StrictStringDeserializer.class) String runId,
            @NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long expectedRevision,
            @NotEmpty @Size(min=1,max=100) List<@Valid ReleaseMessage> messages,@NotEmpty @Size(min=1,max=100) @JsonDeserialize(contentUsing=StrictStringDeserializer.class) List<@NotBlank String> blockedBoundaryRefs) { }
    /** 原具体delivery身份及本次覆盖的边界。 */
    @Schema(name="GroupControlReleaseReceipt") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReleaseReceipt(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String releaseId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long controlRevision,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<ReleaseMessage> messages,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> blockedBoundaryRefs) { }
    /** 每项范围各自保存期望状态；同一target可同时保留不同范围的门禁。 */
    @Schema(name="GroupControlState") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StateView(@Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String runId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) @JsonDeserialize(using=StrictStringDeserializer.class) String desiredState,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> scopes,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision) { }
    /** null上界表示暂停仍活动；恢复只固化上界，绝不删除旧触发依据。 */
    @Schema(name="GroupControlBoundary") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BoundaryView(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String boundaryRef,@Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String runId,@Schema(types={"integer","null"},requiredMode=Schema.RequiredMode.REQUIRED) Long throughTriggerSequence) { }
    /** 既有资格在途或UNKNOWN引用；不能把暂停解释为撤销它们。 */
    @Schema(name="GroupControlInFlight") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Flight(@NotBlank @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @JsonDeserialize(using=StrictStringDeserializer.class) String deliveryId,
            @NotBlank @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @JsonDeserialize(using=StrictStringDeserializer.class) String status,@Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) @JsonDeserialize(using=StrictStringDeserializer.class) String resultRef) { }
    /** 必需集合由服务端持久登记，调用方不能删减。 */
    @Schema(name="GroupControlParticipant") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Participant(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String endpointId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long generation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long requiredRevision,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,@Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String appliedBoundaryRef) { }
    /** 已绑定端点主体的持久应用依据。 */
    @Schema(name="GroupControlDurableAck") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DurableAck(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String endpointId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long generation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long controlRevision,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String appliedBoundaryRef,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String ledgerRef,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long fenceRevision) { }
    /** POST重读原快照；detail重读实际传播状态，二者不互相冒充。 */
    @Schema(name="GroupControlReceipt") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Receipt(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String commandId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String runId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String fingerprint,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long controlRevision,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) @JsonDeserialize(using=StrictStringDeserializer.class) String desiredState,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> scopes,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"ACCEPTED","PARTIAL","APPLIED","SUPERSEDED","REJECTED","TERMINAL_NOT_ACCEPTED"}) String status,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"accepted","rejected","terminal_not_accepted"}) String resultKind,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String resultRef,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Participant> requiredParticipants,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<DurableAck> durableAcks,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Flight> inFlight,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String boundaryRef,@Schema(types={"integer","null"},requiredMode=Schema.RequiredMode.REQUIRED) Long throughTriggerSequence) { }
    /** 最新控制快照与根触发登记进度，不以历史APPLIED为新端点授权。 */
    @Schema(name="GroupControlCurrent") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Current(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long controlRevision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long latestTriggerSequence,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<StateView> states,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<BoundaryView> blockedBoundaries,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<GroupSenderContracts.Endpoint> endpoints,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) Receipt latestCommand) { }
}
