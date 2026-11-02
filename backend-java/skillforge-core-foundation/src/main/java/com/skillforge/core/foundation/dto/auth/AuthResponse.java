package com.skillforge.core.foundation.dto.auth;
import java.util.List;
/** 认证会话响应；MFA未完成只允许绑定验证，refreshToken为空；完整会话返回当前授权集合。 */
public record AuthResponse(String accessToken,String tokenType,int expiresIn,String refreshToken,String tenantId,String username,List<String> roles,List<String> permissions,long authorizationVersion,String mfaState,String sessionId) { }
