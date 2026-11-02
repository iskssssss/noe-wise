package com.skillforge.platform.foundation.messaging.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 平台消息HTTP输入；身份和租户只能来自认证上下文。 */
public final class MessagingRequests {
    private MessagingRequests() { }
    /** @param title 公告标题 @param body 纯文本正文 @param audience all/members/departments/roles @param recipientIds 范围ID @param email 是否邮件通知 @param deduplicationKey 发布幂等键 */
    public record Publish(@NotBlank @Size(max=240) String title,@NotBlank String body,@NotBlank String audience,
            @NotNull List<String> recipientIds,@NotNull Boolean email,@NotBlank @Size(max=160) String deduplicationKey) { }
    /** @param id 同作用域公告、通知或投递ID */
    public record Identity(@NotBlank String id) { }
    /** @param key 模板稳定键 @param subject 标题模板 @param body 正文模板 @param variableNames 完整必需变量声明 */
    public record Template(@NotBlank String key,@NotBlank String subject,@NotBlank String body,@NotNull List<String> variableNames) { }
    /** @param host SMTP主机 @param port TCP端口 @param username SMTP账号 @param password 新密码；null沿用密文 @param fromAddress 发件邮箱 @param tlsMode starttls/tls @param enabled 启用时必须完整配置 */
    public record Mailbox(String host,Integer port,String username,String password,String fromAddress,String tlsMode,@NotNull Boolean enabled) { }
    /** @param days 必须出现的终态材料保留天数；显式NULL关闭 */
    public record Retention(
            @JsonProperty(value="days",required=true)
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true,
                    description="必须出现；null显式关闭消息生命周期，1至36500为保留天数；未覆盖的服务策略默认30天")
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(36500) Integer days) { }
    /** @param id 所属通知ID @param protect 是否调查保护，拒绝退休后补建 */
    public record Protection(@NotBlank String id,@NotNull Boolean protect) { }
    /** @param id 投递ID @param status accepted/rejected @param evidence 外部核验证据，禁止粘贴密码或一次性token */
    public record Reconcile(@NotBlank String id,@NotBlank String status,@NotBlank @Size(max=1024) String evidence) { }
}
