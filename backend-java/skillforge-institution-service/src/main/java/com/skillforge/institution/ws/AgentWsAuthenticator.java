package com.skillforge.institution.ws;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.time.BusinessTime;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
/** 坐席JWT认证与连接续验；角色、设备和密码变更立即影响下一次收发。 */
@Component
public class AgentWsAuthenticator {
 /** 与坐席REST列表使用同一实际动作权限。 */
 private static final String READ="foundation.handoff.read";
 private final StpLogic stpLogic;private final TenantMemberMapper members;
 private final FoundationSecurityService security;private final FoundationAccess access;
 /** 正式签名、账号、安全会话与动作授权装配。 */
 public AgentWsAuthenticator(StpLogic stpLogic,TenantMemberMapper members,FoundationSecurityService security,FoundationAccess access){this.stpLogic=stpLogic;this.members=members;this.security=security;this.access=access;}
 /** 先验证签名/issuer/audience/exp，再读取不可由客户端覆盖的会话引用。 */
 public AgentWsIdentity authenticate(String token) {
  TenantContext.clear();
  try {
   if(token==null||token.isBlank())throw unauthorized();
   var payload=SaJwtUtil.getPayloadsNotCheck(token,stpLogic.getLoginType(),stpLogic.getConfigOrGlobal().getJwtSecretKey());
   Object verifiedId=stpLogic.getLoginIdByToken(token);
   String memberId=String.valueOf(payload.get(SaJwtUtil.LOGIN_ID));String tenantId=String.valueOf(payload.get("tenant_id"));
   Object expiration=payload.get("exp");
   if(verifiedId==null||!memberId.equals(verifiedId.toString())||!(expiration instanceof Number expires)||expires.longValue()!=expires.doubleValue())throw unauthorized();
   long sessionId=id(String.valueOf(payload.get(FoundationSecurityService.SESSION_CLAIM)));
   id(tenantId);id(memberId);
   var identity=new AgentWsIdentity(tenantId,memberId,String.valueOf(payload.get("username")),sessionId,java.time.Instant.ofEpochSecond(expires.longValue()));
   revalidate(identity);
   TenantContext.setTenantId(tenantId);
   var member=members.selectOne(new LambdaQueryWrapper<TenantMemberDO>().eq(TenantMemberDO::getTenantId,id(tenantId)).eq(TenantMemberDO::getId,id(memberId)));
   if(member==null||!"active".equals(member.getStatus()))throw unauthorized();
   return new AgentWsIdentity(tenantId,memberId,member.getUsername(),sessionId,identity.tokenExpiresAt());
  }catch(ApiException failure){throw failure;}catch(SaTokenException|IllegalArgumentException|java.time.DateTimeException failure){throw unauthorized();}
  finally {TenantContext.clear();}
 }
 /** 每次收发使用可信握手主体重查会话/MFA和动作授权；原JWT期限不能被刷新偷偷延长。 */
 public void revalidate(AgentWsIdentity identity) {
  if(identity==null||identity.tokenExpiresAt()==null||!identity.tokenExpiresAt().isAfter(BusinessTime.clock().instant()))throw unauthorized();
  var subject=new FoundationAccess.Subject(false,identity.tenantId(),identity.memberId());
  security.verifyTrustedSession(subject,identity.securitySessionId(),true,false);
  access.require(subject,READ);
 }
 /** 十进制正数身份；非法会话引用与伪造账号统一拒绝。 */
 private static long id(String text){try{if(text==null||!text.matches("[1-9][0-9]*"))throw new NumberFormatException();return Long.parseLong(text);}catch(NumberFormatException invalid){throw unauthorized();}}
 /** 不输出令牌、签名或密码材料。 */
 private static ApiException unauthorized(){return new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_SESSION_INVALID","坐席连接认证失败或已撤销",false);}
}
