package com.skillforge.core.foundation.dto.auth;
import java.util.List;
/** 当前数据库授权和MFA状态；角色权限不从JWT冻结声明推断。 */
public record MeResponse(String sub,String tenantId,String username,List<String> roles,List<String> permissions,long authorizationVersion,String mfaState,String sessionId) { }
