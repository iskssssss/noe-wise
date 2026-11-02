package com.skillforge.core.foundation.security;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
/** 两个宿主安全设置；主体体系由已验证JWT决定，禁止跨机构参数。 */
@RestController
@RequestMapping({"/api/v1/admin/foundation/security","/api/v1/tenant/foundation/security"})
public class FoundationSecurityController {
 private final FoundationSecurityService security;
 /** 注入统一安全域。 */ public FoundationSecurityController(FoundationSecurityService security){this.security=security;}
 /** 本人MFA状态。 */ @GetMapping("/status") public ApiResponse<SecurityViews.Status> status(){return ApiResponses.wrap(security.status());}
 /** 本人设备列表。 */ @GetMapping("/sessions") public ApiResponse<List<SecurityViews.Device>> sessions(){return ApiResponses.wrap(security.devices());}
 /** 撤销本人指定设备。 */ @PostMapping("/sessions/revoke") public ApiResponse<Map<String,String>> revoke(@Valid @RequestBody SecurityRequests.Device request){security.revokeDevice(request.sessionId());return ok();}
 /** 管理员协助验证器遗失恢复，原设备全部失效。 */ @PostMapping("/members/reset-mfa") public ApiResponse<Map<String,String>> resetMfa(@Valid @RequestBody SecurityRequests.Member request){security.resetMemberMfa(request.memberId());return ok();}
 /** 管理员撤销同范围成员全部设备。 */ @PostMapping("/members/revoke") public ApiResponse<Map<String,String>> revokeMember(@Valid @RequestBody SecurityRequests.Member request){security.revokeMember(request.memberId());return ok();}
 /** 强化认证后重新生成恢复码。 */ @PostMapping("/recovery-codes") public ApiResponse<List<String>> codes(){return ApiResponses.wrap(security.regenerateCodes());}
 /** 当前全员MFA策略。 */ @GetMapping("/policy") public ApiResponse<SecurityViews.Policy> policy(){return ApiResponses.wrap(security.policy());}
 /** 发布全员MFA策略。 */ @PostMapping("/policy") public ApiResponse<SecurityViews.Policy> policy(@Valid @RequestBody SecurityRequests.Policy request){return ApiResponses.wrap(security.setPolicy(request.requireAllMfa()));}
 /** 发送登记邮箱邀请；投递结果由消息域显示。 */ @PostMapping("/invitations") public ApiResponse<Map<String,String>> invite(@Valid @RequestBody SecurityRequests.Member request){security.invite(request.memberId());return ok();}
 /** 会话调查保留策略，批准默认90日，支持同范围显式覆盖。 */ @GetMapping("/sessions/retention") public ApiResponse<SecurityViews.SessionRetention> sessionRetention(){return ApiResponses.wrap(security.sessionRetention());}
 /** 具备安全策略授权的管理员发布显式期限。 */ @PostMapping("/sessions/retention") public ApiResponse<SecurityViews.SessionRetention> sessionRetention(@Valid @RequestBody SecurityRequests.SessionRetention request){return ApiResponses.wrap(security.setSessionRetention(request.days()));}
 /** 安全调查保护，后台清理不得越过已提交保护。 */ @PostMapping("/sessions/protection") public ApiResponse<Map<String,String>> protectSession(@Valid @RequestBody SecurityRequests.SessionProtection request){security.protectSession(request.sessionId(),request.protect());return ok();}
 /** 受理结果。 */ private ApiResponse<Map<String,String>> ok(){return ApiResponses.wrap(Map.of("status","ok"));}
}
