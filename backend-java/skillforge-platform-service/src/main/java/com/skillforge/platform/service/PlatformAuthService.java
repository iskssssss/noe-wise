package com.skillforge.platform.service;
import com.skillforge.core.foundation.dto.auth.AuthResponse;
import com.skillforge.core.foundation.dto.auth.MeResponse;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import org.springframework.stereotype.Service;
/** 平台隔离认证入口；JWT继续使用平台宿主issuer/audience。 */
@Service
public class PlatformAuthService {
 private final FoundationSecurityService security;
 /** 注入统一安全权威。 */ public PlatformAuthService(FoundationSecurityService security){this.security=security;}
 /** 不允许租户slug或tenant_id参与平台密码认证。 */ public AuthResponse login(String username,String password){return security.login(true,null,username,password);}
 /** 平台刷新只接受platform前缀。 */ public AuthResponse refresh(String token){return security.refresh(true,token);}
 /** 当前账号数据库授权投影。 */ public MeResponse me(){return security.me();}
}
