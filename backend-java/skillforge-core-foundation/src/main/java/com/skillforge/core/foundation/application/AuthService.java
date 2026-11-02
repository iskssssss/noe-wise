package com.skillforge.core.foundation.application;
import com.skillforge.core.foundation.dto.auth.AuthResponse;
import com.skillforge.core.foundation.dto.auth.MeResponse;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import org.springframework.stereotype.Service;
/** 机构认证入口；全部凭据和会话生命周期由安全域统一负责。 */
@Service
public class AuthService {
 private final FoundationSecurityService security;
 /** 注入正式安全权威服务。 */ public AuthService(FoundationSecurityService security){this.security=security;}
 /** 机构密码认证仅允许该机构账号体系。 */ public AuthResponse login(String tenantSlug,String username,String password){return security.login(false,tenantSlug,username,password);}
 /** 机构刷新拒绝platform前缀。 */ public AuthResponse refresh(String refreshToken){return security.refresh(false,refreshToken);}
 /** 当前主体密码修改会撤销全部设备。 */ public void changePassword(String oldPassword,String newPassword){security.changePassword(oldPassword,newPassword);}
 /** 当前数据库身份和授权投影。 */ public MeResponse me(){return security.me();}
}
