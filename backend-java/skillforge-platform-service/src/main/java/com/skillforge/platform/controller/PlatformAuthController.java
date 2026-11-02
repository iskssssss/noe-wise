package com.skillforge.platform.controller;

import com.skillforge.core.foundation.dto.auth.AuthResponse;
import com.skillforge.core.foundation.dto.auth.ChangePasswordRequest;
import com.skillforge.core.foundation.dto.auth.MeResponse;
import com.skillforge.core.foundation.dto.auth.RefreshRequest;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.security.SecurityRequests;
import com.skillforge.core.foundation.security.SecurityViews;
import com.skillforge.platform.service.PlatformAuthService;
import com.skillforge.platform.dto.auth.PlatformLoginRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
/** 平台认证扩展：MFA绑定、登录挑战、强化认证与邮箱一次性恢复。 */
@RestController @RequestMapping("/api/v1/platform/auth")
public class PlatformAuthController {
 private final PlatformAuthService authService;private final FoundationSecurityService security;
 /** 正式认证和安全服务共同装配。 */
 public PlatformAuthController(PlatformAuthService authService,FoundationSecurityService security){this.authService=authService;this.security=security;}
 /** 密码正确仍可能只返回受限MFA会话。 */
 @PostMapping("/login") public ApiResponse<AuthResponse> login(@Valid @RequestBody PlatformLoginRequest request){return ApiResponses.wrap(authService.login(request.username(),request.password()));}
 /** 完整会话刷新凭据原子轮换。 */
 @PostMapping("/refresh") public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request){return ApiResponses.wrap(authService.refresh(request.refreshToken()));}
 /** 当前数据库权限集合和MFA状态。 */
 @GetMapping("/me") public ApiResponse<MeResponse> me(){return ApiResponses.wrap(authService.me());}
 /** 密码修改撤销所有设备，调用方必须重新登录。 */
 @PostMapping("/password") public ApiResponse<Map<String,String>> password(@Valid @RequestBody ChangePasswordRequest request){security.changePassword(request.oldPassword(),request.newPassword());return ok();}
 /** 服务端会话撤销。 */
 @PostMapping("/logout") public ApiResponse<Map<String,String>> logout(){security.logout();return ok();}
 /** 受限会话允许读取MFA设置。 */
 @GetMapping("/mfa/status") public ApiResponse<SecurityViews.Status> status(){return ApiResponses.wrap(security.status());}
 /** 一次性交付待绑定种子。 */
 @PostMapping("/mfa/enroll") public ApiResponse<SecurityViews.Enrollment> enroll(){return ApiResponses.wrap(security.enroll());}
 /** 首次确认并一次性交付恢复码和完整认证会话。 */
 @PostMapping("/mfa/enable") public ApiResponse<SecurityViews.Enabled> enable(@Valid @RequestBody SecurityRequests.Proof request){return ApiResponses.wrap(security.enable(request.code()));}
 /** 登录挑战和敏感操作step-up共用服务端验证。 */
 @PostMapping("/mfa/verify") public ApiResponse<AuthResponse> verify(@Valid @RequestBody SecurityRequests.Proof request){return ApiResponses.wrap(security.verify(request.code()));}
 /** 自愿绑定可在强化认证后关闭，显式全员策略开启则拒绝。 */
 @PostMapping("/mfa/disable") public ApiResponse<Map<String,String>> disable(){security.disableMfa();return ok();}
 /** 本人恢复请求始终不透露账号存在性。 */
 @PostMapping("/recovery/request") public ApiResponse<Map<String,String>> requestRecovery(@Valid @RequestBody SecurityRequests.RecoveryRequest request){security.requestRecovery(true,request.tenantSlug(),request.username());return ApiResponses.wrap(Map.of("status","accepted"));}
 /** 邮件恢复不能移除既有MFA。 */
 @PostMapping("/recovery/complete") public ApiResponse<Map<String,String>> recover(@Valid @RequestBody SecurityRequests.PasswordRecovery request){security.completeRecovery(true,"recovery",request.token(),request.newPassword());return ok();}
 /** 邀请只能激活同体系invited账号。 */
 @PostMapping("/invite/activate") public ApiResponse<Map<String,String>> activate(@Valid @RequestBody SecurityRequests.PasswordRecovery request){security.completeRecovery(true,"invitation",request.token(),request.newPassword());return ok();}
 /** 通用操作受理投影。 */ private ApiResponse<Map<String,String>> ok(){return ApiResponses.wrap(Map.of("status","ok"));}
}
