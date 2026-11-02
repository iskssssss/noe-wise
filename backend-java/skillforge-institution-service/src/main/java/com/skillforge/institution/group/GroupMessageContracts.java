package com.skillforge.institution.group;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.*;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.web.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/**
 * 群不可变来源、显式前台触发与原 Run 控制的公开契约。
 *
 * <p>Tenant、integration 与服务主体仅取可信认证上下文。正文中的作者、请求者和操作者是
 * 待核验的真实身份引用；EndUser/Agent 的十进制字符串不构成授权。
 */
public final class GroupMessageContracts {

    private GroupMessageContracts() { }

    /** 来源作者的封闭联合类型；每个分支都须匹配该群真实身份或已提交公开来源。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = EndUserAuthor.class, name = "end_user"),
            @JsonSubTypes.Type(value = AgentAuthor.class, name = "agent"),
            @JsonSubTypes.Type(value = ToolAuthor.class, name = "tool"),
            @JsonSubTypes.Type(value = SystemAuthor.class, name = "system")
    })
    @Schema(name = "GroupMessageAuthor", requiredProperties = {"kind"})
    public sealed interface Author permits EndUserAuthor, AgentAuthor, ToolAuthor, SystemAuthor {

        @JsonProperty("kind")
        default String kind() {
            return this instanceof EndUserAuthor ? "end_user"
                    : this instanceof AgentAuthor ? "agent"
                    : this instanceof ToolAuthor ? "tool" : "system";
        }
    }

    /** 首次记录时须核真实外部 EndUser 映射与当前有效群成员资格。 */
    @Schema(name = "GroupMessageEndUserAuthor")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EndUserAuthor(
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19)
            @JsonDeserialize(using = DecimalStringIdDeserializer.class)
            @JsonSerialize(using = ToStringSerializer.class)
            @NotNull @Positive Long endUserId) implements Author {

        @Override
        @JsonProperty("kind")
        @Schema(allowableValues = {"end_user"}, requiredMode = Schema.RequiredMode.REQUIRED)
        public String kind() {
            return "end_user";
        }
    }

    /** Agent 来源须匹配同群原 Run 已公开的消息、实际 Agent 与完整有序内容。 */
    @Schema(name = "GroupMessageAgentAuthor")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentAuthor(
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19)
            @JsonDeserialize(using = DecimalStringIdDeserializer.class)
            @JsonSerialize(using = ToStringSerializer.class)
            @NotNull @Positive Long agentId,
            @NotBlank @Size(min = 1, max = 128)
            @JsonDeserialize(using = StrictStringDeserializer.class) String runId,
            @NotBlank @Size(min = 1, max = 256)
            @JsonDeserialize(using = StrictStringDeserializer.class) String sourceRef) implements Author {

        @Override
        @JsonProperty("kind")
        @Schema(allowableValues = {"agent"}, requiredMode = Schema.RequiredMode.REQUIRED)
        public String kind() {
            return "agent";
        }
    }

    /** 工具来源必须存在对应的正式公开事实；私有 Action 或结果正文不能据此升级为群消息。 */
    @Schema(name = "GroupMessageToolAuthor")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolAuthor(
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19)
            @JsonDeserialize(using = DecimalStringIdDeserializer.class)
            @JsonSerialize(using = ToStringSerializer.class)
            @NotNull @Positive Long agentId,
            @NotBlank @Size(min = 1, max = 128)
            @JsonDeserialize(using = StrictStringDeserializer.class) String runId,
            @NotBlank @Size(min = 1, max = 256)
            @JsonDeserialize(using = StrictStringDeserializer.class) String sourceRef) implements Author {

        @Override
        @JsonProperty("kind")
        @Schema(allowableValues = {"tool"}, requiredMode = Schema.RequiredMode.REQUIRED)
        public String kind() {
            return "tool";
        }
    }

    /** 系统来源也须匹配已提交公开事实；任意 sourceRef 或服务主体写 scope 均不足以授权。 */
    @Schema(name = "GroupMessageSystemAuthor")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SystemAuthor(
            @NotBlank @Size(min = 1, max = 256)
            @JsonDeserialize(using = StrictStringDeserializer.class) String sourceRef) implements Author {

        @Override
        @JsonProperty("kind")
        @Schema(allowableValues = {"system"}, requiredMode = Schema.RequiredMode.REQUIRED)
        public String kind() {
            return "system";
        }
    }

    /**
     * 只记录来源，不隐式创建 input、Run 或模型预算。
     *
     * @param sourceEventId 同群来源事件的稳定键；同事件重复同参读取原回执，异参冲突
     * @param sourceRevision 原事件的不可变来源修订；不能通过新修订编辑原消息，纠正须用新事件
     * @param content 有序内容块；图片选择器须解析为该群 Session 真正持有的媒体
     */
    @Schema(name = "GroupMessageRecord")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MessageRecord(
            @NotBlank @Size(min = 1, max = 64)
            @JsonDeserialize(using = StrictStringDeserializer.class) String groupBindingId,
            @NotBlank @Size(min = 1, max = 256)
            @JsonDeserialize(using = StrictStringDeserializer.class) String sourceEventId,
            @NotNull @Min(1)
            @JsonDeserialize(using = StrictLongDeserializer.class) Long sourceRevision,
            @Schema(oneOf = {EndUserAuthor.class, AgentAuthor.class, ToolAuthor.class, SystemAuthor.class},
                    discriminatorProperty = "kind",
                    discriminatorMapping = {
                            @DiscriminatorMapping(value = "end_user", schema = EndUserAuthor.class),
                            @DiscriminatorMapping(value = "agent", schema = AgentAuthor.class),
                            @DiscriminatorMapping(value = "tool", schema = ToolAuthor.class),
                            @DiscriminatorMapping(value = "system", schema = SystemAuthor.class)
                    })
            @NotNull @Valid Author author,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid ChatContent> content) { }

    /** 来源记录的稳定六字段回执；重复读取不会重记来源，也不附带虚构的执行身份。 */
    @Schema(name = "GroupMessageReceipt")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MessageReceipt(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sessionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceMessageId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sourceSequence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceEventId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sourceRevision) { }

    /**
     * 从已记录 EndUser 来源显式触发指定 Agent，requester 必须等于原来源作者。
     *
     * <p>同来源最多一个前台触发；同 requester/Agent 换命令键仍读原回执，异参返回冲突。
     */
    @Schema(name = "GroupTrigger")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Trigger(
            @NotBlank @Size(min = 1, max = 64)
            @JsonDeserialize(using = StrictStringDeserializer.class) String groupBindingId,
            @NotBlank @Size(min = 1, max = 64)
            @JsonDeserialize(using = StrictStringDeserializer.class) String sourceMessageId,
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19)
            @JsonDeserialize(using = DecimalStringIdDeserializer.class)
            @JsonSerialize(using = ToStringSerializer.class)
            @NotNull @Positive Long requesterEndUserId,
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19)
            @JsonDeserialize(using = DecimalStringIdDeserializer.class)
            @JsonSerialize(using = ToStringSerializer.class)
            @NotNull @Positive Long agentId) { }

    /**
     * 触发命令的原回执与当前详情共用的协议形状。
     *
     * <p>POST 稳定返回首次 queued 回执；GET detail 读取当前 queued、accepted 或 rejected
     * 投影。queued 的 inputId/runId 为空，accepted 引用真实已准入身份，rejected 保存安全原因。
     * 后续资格变化不改写原回执；真正出队时重新核验当前资格。
     */
    @Schema(name = "GroupTriggerReceipt")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TriggerReceipt(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String groupBindingId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sessionId,
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19, requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonSerialize(using = ToStringSerializer.class) Long requesterEndUserId,
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19, requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonSerialize(using = ToStringSerializer.class) Long agentId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String triggerId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long triggerSequence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceMessageId,
            @Schema(allowableValues = {"queued", "accepted", "rejected"},
                    requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String inputId,
            @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String runId,
            @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String rejectionCode) { }

    /**
     * 对原 Run 的精确等待回复；responder 是当前操作者，不替换原 requester。
     *
     * @param inputId 稳定命令身份，须与 Idempotency-Key 一致
     * @param hitlResponse 正式 Runtime 回复负载，须指向原 Run 及准确的等待代次
     */
    @Schema(name = "GroupRunRespond")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunRespond(
            @NotBlank @Size(min = 1, max = 64)
            @JsonDeserialize(using = StrictStringDeserializer.class) String groupBindingId,
            @NotBlank @Size(min = 1, max = 128)
            @JsonDeserialize(using = StrictStringDeserializer.class) String runId,
            @NotBlank @Size(min = 1, max = 128)
            @JsonDeserialize(using = StrictStringDeserializer.class) String inputId,
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19)
            @JsonDeserialize(using = DecimalStringIdDeserializer.class)
            @JsonSerialize(using = ToStringSerializer.class)
            @NotNull @Positive Long responderEndUserId,
            @NotNull java.util.Map<String, Object> hitlResponse) { }

    /**
     * 定向控制原 Run，直接进入现有 Runtime 控制路径，不进入普通触发队列。
     *
     * @param inputId 稳定命令身份，须与 Idempotency-Key 一致
     * @param operatorEndUserId 须核验为当前成员，且是原 requester 或具有 manager 治理资格
     * @param action cancel_execution 或 steer；取消只有在原 Run 真正终态后才能释放单槽
     * @param text steer 必须提供非空白文本，cancel_execution 仅允许缺失或 null
     */
    @Schema(name = "GroupRunCommand")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunCommand(
            @NotBlank @Size(min = 1, max = 64)
            @JsonDeserialize(using = StrictStringDeserializer.class) String groupBindingId,
            @NotBlank @Size(min = 1, max = 128)
            @JsonDeserialize(using = StrictStringDeserializer.class) String runId,
            @NotBlank @Size(min = 1, max = 128)
            @JsonDeserialize(using = StrictStringDeserializer.class) String inputId,
            @Schema(implementation = String.class, types = {"string"}, pattern = "[1-9][0-9]{0,18}",
                    minLength = 1, maxLength = 19)
            @JsonDeserialize(using = DecimalStringIdDeserializer.class)
            @JsonSerialize(using = ToStringSerializer.class)
            @NotNull @Positive Long operatorEndUserId,
            @NotBlank @Schema(allowableValues = {"cancel_execution", "steer"})
            @JsonDeserialize(using = StrictStringDeserializer.class) String action,
            @Schema(types = {"string", "null"})
            @JsonDeserialize(using = StrictStringDeserializer.class) String text) { }
}
