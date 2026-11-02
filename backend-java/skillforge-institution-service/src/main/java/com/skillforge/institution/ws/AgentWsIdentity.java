package com.skillforge.institution.ws;
import java.time.Instant;
/** 握手验证后保存的可信账号、会话引用与原JWT期限；不冻结角色或权限。 */
public record AgentWsIdentity(String tenantId,String memberId,String username,long securitySessionId,Instant tokenExpiresAt) { }
