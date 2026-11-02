package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.security.SecurityMapper;
import com.skillforge.core.foundation.security.SecurityRows;
import com.skillforge.core.foundation.security.Totp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.security.RefreshTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
/** 当前账号、安全策略与原子凭据失效边界，默认不开启MFA。 */
class AuthServiceCoverageTest {
 private SecurityMapper mapper;private FoundationAccess access;private PasswordEncoder passwords;
 private StringRedisTemplate redis;private FoundationSecurityService security;private MockedStatic<StpUtil> stp;
 private final FoundationAccess.Subject subject=new FoundationAccess.Subject(false,"7","11");
 private final String scope="tenant:7";
 private static final String SECRET="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
 @BeforeEach void setup(){
  mapper=mock(SecurityMapper.class);access=mock(FoundationAccess.class);passwords=mock(PasswordEncoder.class);redis=mock(StringRedisTemplate.class);
  var crypto=mock(CryptoService.class);when(crypto.decrypt(anyString())).thenReturn(SECRET);
  security=new FoundationSecurityService(mapper,access,passwords,crypto,new SkillForgeProperties(),redis,mock(NotificationService.class),mock(AuditService.class));
  when(access.withSubject(any(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get());
  when(access.currentSubject()).thenReturn(subject);when(access.snapshot(subject)).thenReturn(new FoundationAccess.Snapshot(List.of("tenant_admin"),List.of("foundation.access.role.manage"),4));
  when(mapper.tenantBySlug("acme")).thenReturn(7L);when(mapper.accountByName(false,7L,"admin")).thenReturn(account("active","hash"));
  when(mapper.accountById(false,7L,11L)).thenReturn(account("active","hash"));when(mapper.session(scope,71L)).thenReturn(session("full",null,"hash"));
  when(mapper.rotate(anyString(),anyLong(),nullable(String.class),anyString())).thenReturn(1);
  when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),anyString())).thenReturn(1L);
  when(passwords.matches("password","hash")).thenReturn(true);
  stp=mockStatic(StpUtil.class);stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn("71");stp.when(StpUtil::getTokenValue).thenReturn("signed.jwt");
 }
 @AfterEach void cleanup(){stp.close();TenantContext.clear();}
 @Test void privilegedAccountDefaultsToFullWithoutOtp(){var result=security.login(false,"acme","admin","password");assertThat(result.mfaState()).isEqualTo("full");assertThat(result.refreshToken()).startsWith("7.");assertThat(result.roles()).containsExactly("tenant_admin");assertThatCode(security::requireStepUp).doesNotThrowAnyException();}
 @Test void explicitPolicyReturnsEnrollmentWithoutRefresh(){when(mapper.requireAll(scope)).thenReturn(true);var result=security.login(false,"acme","admin","password");assertThat(result.mfaState()).isEqualTo("enrollment");assertThat(result.refreshToken()).isNull();assertThatThrownBy(security::requireFull).isInstanceOf(ApiException.class);}
 @Test void voluntarilyBoundAccountRequiresChallenge(){when(mapper.mfa(scope,11L,false)).thenReturn(new SecurityRows.Mfa(scope,11L,"encrypted",true,-1));var result=security.login(false,"acme","admin","password");assertThat(result.mfaState()).isEqualTo("challenge");assertThat(result.refreshToken()).isNull();}
 @Test void disabledOrChangedPasswordRejectsExistingAccess(){when(mapper.accountById(false,7L,11L)).thenReturn(account("disabled","hash"));assertThatThrownBy(security::currentSession).isInstanceOf(ApiException.class);when(mapper.accountById(false,7L,11L)).thenReturn(account("active","new-hash"));assertThatThrownBy(security::currentSession).isInstanceOf(ApiException.class);}
 @Test void disabledMemberAndCrossRealmRejectRefreshBeforeRotation(){String raw=RefreshTokens.create();when(mapper.refresh(scope,digest(raw))).thenReturn(session("full",null,"hash"));when(mapper.accountById(false,7L,11L)).thenReturn(account("disabled","hash"));assertThatThrownBy(()->security.refresh(false,"7."+raw)).isInstanceOf(ApiException.class);assertThatThrownBy(()->security.refresh(true,"7."+raw)).isInstanceOf(ApiException.class);assertThatThrownBy(()->security.refresh(false,"platform."+raw)).isInstanceOf(ApiException.class);verify(mapper,never()).rotate(anyString(),anyLong(),any(),anyString());}
 @Test void consumedTotpCannotBeReplayed(){long counter=Instant.now().getEpochSecond()/30;when(mapper.mfa(scope,11L,true)).thenReturn(new SecurityRows.Mfa(scope,11L,"encrypted",true,counter));assertThatThrownBy(()->security.verify(Totp.generate(SECRET,counter))).isInstanceOf(ApiException.class);verify(mapper,never()).verifySession(anyString(),anyLong());}
 @Test void usedRecoveryCodeNeverUpgradesSession(){String code=RefreshTokens.create();when(mapper.mfa(scope,11L,true)).thenReturn(new SecurityRows.Mfa(scope,11L,"encrypted",true,-1));when(mapper.consumeCode(scope,11L,digest(code))).thenReturn(0);assertThatThrownBy(()->security.verify(code)).isInstanceOf(ApiException.class);verify(mapper,never()).verifySession(anyString(),anyLong());}
 @Test void optionalMfaRequiresRecentProofForSensitiveWrites(){when(mapper.mfa(scope,11L,false)).thenReturn(new SecurityRows.Mfa(scope,11L,"encrypted",true,-1));when(mapper.session(scope,71L)).thenReturn(session("full",Instant.now().minusSeconds(400),"hash"));assertThatThrownBy(security::requireStepUp).isInstanceOf(ApiException.class);}
 @Test void asyncTrustedIdentityStillRejectsRevokedOrPasswordChangedSession(){
  assertThatCode(()->security.verifyTrustedSession(subject,71,true,false)).doesNotThrowAnyException();
  when(mapper.accountById(false,7L,11L)).thenReturn(account("active","new-password-hash"));
  assertThatThrownBy(()->security.verifyTrustedSession(subject,71,true,false)).isInstanceOf(ApiException.class);
  when(mapper.accountById(false,7L,11L)).thenReturn(account("active","hash"));
  var old=session("full",null,"hash");when(mapper.session(scope,71L)).thenReturn(new SecurityRows.Session(old.id(),old.scopeKey(),old.memberId(),old.credentialHash(),old.refreshHash(),old.state(),old.deviceLabel(),old.createdAt(),old.expiresAt(),old.verifiedAt(),Instant.now()));
  assertThatThrownBy(()->security.verifyTrustedSession(subject,71,true,false)).isInstanceOf(ApiException.class);
 }
 @Test void passwordChangeRevokesAllDevicesAndKeepsMfa(){when(passwords.encode("NewPassword123")).thenReturn("new-hash");when(mapper.password(false,7L,11L,"new-hash",false)).thenReturn(1);security.changePassword("password","NewPassword123");verify(mapper).revoke(scope,11L,null);verify(mapper,never()).disableMfa(anyString(),anyLong());}
 @Test void refreshCasLoserDoesNotSignJwt(){String raw=RefreshTokens.create();var session=new SecurityRows.Session(71,scope,11,digest("hash"),digest(raw),"full","browser",Instant.now().minusSeconds(1),Instant.now().plusSeconds(1000),null,null);when(mapper.refresh(scope,digest(raw))).thenReturn(session);when(mapper.rotate(anyString(),anyLong(),anyString(),anyString())).thenReturn(0);assertThatThrownBy(()->security.refresh(false,"7."+raw)).isInstanceOf(ApiException.class);stp.verify(StpUtil::getTokenValue,never());}
 /** 显式调查期限从已认证scope发布，缺失值关闭，不默补业务TTL。 */
 @Test void sessionRetentionUsesTrustedScopeAndRejectsInvalidDays(){when(mapper.sessionRetention(scope)).thenReturn(90);assertThat(security.sessionRetention().days()).isEqualTo(90);assertThatThrownBy(()->security.setSessionRetention(0)).isInstanceOf(ApiException.class);verify(mapper,never()).setSessionRetention(anyString(),eq(0));security.setSessionRetention(90);verify(mapper).setSessionRetention(scope,90);security.setSessionRetention(null);verify(mapper).setSessionRetention(scope,null);}
 /** 后台作用域不匹配先拒绝；授权调查不能保护他人作用域的未知ID。 */
 @Test void sessionCleanupAndProtectionDoNotAcceptForeignScope(){TenantContext.setTenantId("8");assertThatThrownBy(()->security.cleanupSessions(scope)).isInstanceOf(IllegalStateException.class);verify(mapper,never()).cleanupSessions(anyString(),anyInt());assertThatThrownBy(()->security.protectSession("999",true)).isInstanceOf(ApiException.class);verify(mapper).protectSession(scope,999,true);}
 /** 会话不存在时认证失败关闭，正常清理不会把旧JWT重新解释为有效身份。 */
 @Test void removedSessionRejectsExistingAccess(){when(mapper.session(scope,71L)).thenReturn(null);assertThatThrownBy(security::currentSession).isInstanceOf(ApiException.class);}
 private SecurityRows.Account account(String status,String hash){return new SecurityRows.Account(11,7L,"admin",hash,status,"admin@example.test","admin");}
 private SecurityRows.Session session(String state,Instant verified,String hash){return new SecurityRows.Session(71,scope,11,digest(hash),null,state,"browser",Instant.now().minusSeconds(1),Instant.now().plusSeconds(1000),verified,null);}
 private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
