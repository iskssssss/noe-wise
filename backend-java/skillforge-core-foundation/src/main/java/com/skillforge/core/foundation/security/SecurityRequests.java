package com.skillforge.core.foundation.security;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
/** 安全接口输入；主体和凭据用途由服务重新校验。 */
public final class SecurityRequests {
 private SecurityRequests() { }
 /** 六位验证码或高熵一次性恢复码。 */
 public record Proof(@NotBlank @Size(max=128) String code) { }
 /** 本人指定设备。 */
 public record Device(@NotBlank @Pattern(regexp="[1-9][0-9]*") String sessionId) { }
 /** 同账号体系成员。 */
 public record Member(@NotBlank @Pattern(regexp="[1-9][0-9]*") String memberId) { }
 /** 发布全员 MFA 策略，显式值必填。 */
 public record Policy(@NotNull Boolean requireAllMfa) { }
 /** days必须出现；显式NULL关闭生命周期，启用时必须给正整数天数。 */
 public record SessionRetention(
         @JsonProperty(value="days",required=true)
         @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true,
                 description="必须出现；null显式关闭会话生命周期，1至36500为保留天数；未覆盖的服务策略默认90天")
         @Min(1) @Max(36500) Integer days) { }
 /** 安全调查保护，解除时仍需同范围管理授权和强化认证。 */
 public record SessionProtection(@NotBlank @Pattern(regexp="[1-9][0-9]*") String sessionId,@NotNull Boolean protect) { }
 /** 恢复请求统一受理，邮箱从账号取。 */
 public record RecoveryRequest(@Size(max=128) String tenantSlug,@NotBlank @Size(max=128) String username) { }
 /** 邮件凭据和新密码，服务另校验BCrypt字节边界。 */
 public record PasswordRecovery(@NotBlank @Size(max=256) String token,@NotBlank @Size(min=12,max=72) @Pattern(regexp="^(?=.*[A-Za-z])(?=.*[0-9]).+$") String newPassword) { }
}
