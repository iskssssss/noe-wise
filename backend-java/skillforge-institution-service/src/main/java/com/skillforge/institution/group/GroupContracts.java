package com.skillforge.institution.group;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.shared.web.DecimalStringIdDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** 群治理协议；Tenant/integration及actor只取当前可信认证。 */
public final class GroupContracts {
    private GroupContracts() { }
    public static final int MAX_DIRECTORY_PAGE_SIZE = 200;
    @Schema(name="GroupEnsure") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ensure(
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=128) String namespace,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=256) String externalGroupRef,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=128) String lifecycleRef,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=64) String businessScopeId) { }
    @Schema(name="GroupClose") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Close(
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=64) String groupBindingId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @NotNull @Min(1) Long expectedRevision) { }
    @Schema(name="GroupUpdateMember") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateMember(
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=64) String groupBindingId,
            @Schema(implementation=String.class,types={"string"},pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19) @JsonDeserialize(using=DecimalStringIdDeserializer.class) @JsonSerialize(using=ToStringSerializer.class) @NotNull @Positive Long endUserId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Schema(allowableValues={"member","manager"}) String role,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Schema(allowableValues={"active","left"}) String status,
            @JsonDeserialize(using=StrictLongDeserializer.class) @NotNull @Min(0) Long expectedRevision,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=256) String sourceEventId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @NotNull @Min(1) Long sourceRevision) { }
    @Schema(name="GroupUpdateAgent") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateAgent(
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=64) String groupBindingId,
            @Schema(implementation=String.class,types={"string"},pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19) @JsonDeserialize(using=DecimalStringIdDeserializer.class) @JsonSerialize(using=ToStringSerializer.class) @NotNull @Positive Long agentId,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Schema(allowableValues={"active","removed"}) String status,
            @JsonDeserialize(using=StrictLongDeserializer.class) @NotNull @Min(0) Long expectedRevision,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank @Size(min=1,max=256) String sourceEventId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @NotNull @Min(1) Long sourceRevision) { }
    @Schema(name="GroupSessionSubject") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Subject(@Schema(allowableValues={"group"},requiredMode=Schema.RequiredMode.REQUIRED) String kind,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId) {
        public Subject {if(!"group".equals(kind)||groupBindingId==null)throw new IllegalArgumentException("group_subject_invalid");}
    }
    @Schema(name="GroupView") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupView(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sessionId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String namespace,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String externalGroupRef,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String lifecycleRef,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String businessScopeId,
            @Schema(allowableValues={"active","closed"},requiredMode=Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Subject subject) { }
    /** 按稳定成员身份翻页，不限制群的成员总数。 */
    @Schema(name="GroupMembersPage") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MembersPage(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) java.util.List<MemberView> items,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String nextAfter,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) {
        public MembersPage { items = java.util.List.copyOf(items); }
    }
    @Schema(name="GroupAgentsPage") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentsPage(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) java.util.List<AgentView> items,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String nextAfter,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) {
        public AgentsPage { items = java.util.List.copyOf(items); }
    }
    @Schema(name="GroupMemberView") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemberView(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(implementation=String.class,types={"string"},pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19,requiredMode=Schema.RequiredMode.REQUIRED) @JsonSerialize(using=ToStringSerializer.class) Long endUserId,
            @Schema(allowableValues={"member","manager"},requiredMode=Schema.RequiredMode.REQUIRED) String role,
            @Schema(allowableValues={"active","left"},requiredMode=Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sourceEventId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long sourceRevision) { }
    @Schema(name="GroupAgentView") @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentView(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(implementation=String.class,types={"string"},pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19,requiredMode=Schema.RequiredMode.REQUIRED) @JsonSerialize(using=ToStringSerializer.class) Long agentId,
            @Schema(allowableValues={"active","removed"},requiredMode=Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sourceEventId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long sourceRevision) { }
}
