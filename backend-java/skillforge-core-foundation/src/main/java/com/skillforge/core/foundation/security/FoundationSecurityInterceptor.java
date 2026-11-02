package com.skillforge.core.foundation.security;
import com.skillforge.core.foundation.access.FoundationAccess;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
/** 角色权限受理之前验证DB会话和MFA；外部受信认证继续由所属认证链负责。 */
@Component
public class FoundationSecurityInterceptor implements HandlerInterceptor {
 /** 初次已验证主体，异步派发只用该可信属性重新查权限。 */
 public static final String SUBJECT_ATTRIBUTE="FOUNDATION_SECURITY_SUBJECT";
 /** 初次已验证设备会话ID；不能从客户端header取得。 */
 public static final String SESSION_ATTRIBUTE="FOUNDATION_SECURITY_SESSION_ID";
 private static final Set<String> PUBLIC=Set.of("/login","/refresh","/recovery/request","/recovery/complete","/invite/activate");
 private static final Set<String> RESTRICTED=Set.of("/me","/logout","/mfa/status","/mfa/enroll","/mfa/enable","/mfa/verify");
 private final FoundationSecurityService security;private final FoundationAccess access;
 /** 构造正式会话校验器；宿主在鉴权后、权限前order15注册。 */
 public FoundationSecurityInterceptor(FoundationSecurityService security,FoundationAccess access){this.security=security;this.access=access;}
 @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
  if(!(handler instanceof HandlerMethod)) return true;
  String uri=request.getRequestURI();String auth=uri.startsWith("/api/v1/platform/auth/")?uri.substring("/api/v1/platform/auth".length()):uri.startsWith("/api/v1/auth/")?uri.substring("/api/v1/auth".length()):null;
  if(auth!=null && PUBLIC.contains(auth)) return true;
  if(com.skillforge.core.foundation.integration.ManagementAuthenticationInterceptor.verified(request)) return true;
  Object source=request.getAttribute("AUTH_SOURCE");
  if(Set.of("CHANNEL","OPENAPI","SERVICE_PRINCIPAL").contains(String.valueOf(source))) return true;
  boolean full=auth==null || !RESTRICTED.contains(auth);
  boolean stepUp=full && sensitive(request);
  if(request.getDispatcherType()==DispatcherType.ASYNC) {
   Object subject=request.getAttribute(SUBJECT_ATTRIBUTE),session=request.getAttribute(SESSION_ATTRIBUTE);
   if(!(subject instanceof FoundationAccess.Subject trusted) || !(session instanceof Long id))
    throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"可信异步身份缺失");
   security.verifyTrustedSession(trusted,id,full,stepUp);return true;
  }
  var subject=access.currentSubject();
  if((uri.startsWith("/api/v1/admin/foundation/")&&!subject.platform())||(uri.startsWith("/api/v1/tenant/foundation/")&&subject.platform()))
   throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"账号体系与接口范围不匹配");
  var session=security.verifyCurrentSession(subject,full,stepUp);
  request.setAttribute(SUBJECT_ATTRIBUTE,subject);request.setAttribute(SESSION_ATTRIBUTE,session.id());
  return true;
 }
 /** 管理员授权、安全策略、邮箱、密码和导出写入均需要近期二次验证。 */
 private boolean sensitive(HttpServletRequest request) {
  if(Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) return false;
  String uri=request.getRequestURI();
  return uri.contains("/foundation/access/") || uri.endsWith("/foundation/security/policy")
   || uri.endsWith("/foundation/security/invitations") || uri.endsWith("/foundation/security/members/revoke") || uri.endsWith("/foundation/security/members/reset-mfa")
   || uri.contains("/foundation/messaging/mailbox") || uri.contains("/foundation/operations/retention")
   || uri.startsWith("/api/v1/platform/management-principals") || uri.startsWith("/api/v1/platform/institution-credentials")
   || uri.contains("/admin/tenants/") || uri.contains("/members/password") || uri.contains("/members/reset-password");
 }
}
