package com.skillforge.core.foundation.security;
import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.List;
import org.springframework.stereotype.Component;
/** 安全域管理权限；个人操作始终限定当前账号。 */
@Component
public class SecurityPermissions implements FoundationPermissionContributor {
 /** 当前账号个人安全操作；不形成管理其他成员的权限。 */ public static final String SELF="foundation.security.self";
 /** 维护全员 MFA 策略。 */ public static final String POLICY="foundation.security.policy.manage";
 /** 向成员已登记邮箱发送邀请。 */ public static final String INVITE="foundation.security.invitation.manage";
 /** 撤销成员全部设备会话。 */ public static final String REVOKE="foundation.security.session.manage";
 /** 协助成员重置遗失验证器。 */ public static final String RESET_MFA="foundation.security.mfa.manage";
 @Override public List<FoundationPermission> permissions() {
  return List.of(new FoundationPermission(SELF,"管理本人账号安全",FoundationPermission.Audience.BOTH),new FoundationPermission(POLICY,"管理安全策略",FoundationPermission.Audience.BOTH),new FoundationPermission(INVITE,"发送成员邀请",FoundationPermission.Audience.BOTH),new FoundationPermission(REVOKE,"撤销成员会话",FoundationPermission.Audience.BOTH),new FoundationPermission(RESET_MFA,"重置成员验证器",FoundationPermission.Audience.BOTH));
 }
}
