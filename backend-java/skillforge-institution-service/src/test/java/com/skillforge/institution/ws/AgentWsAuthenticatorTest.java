package com.skillforge.institution.ws;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.json.JSONObject;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
/** 坐席签名、权威会话、实时动作授权和存量连接续验边界。 */
class AgentWsAuthenticatorTest {
 private StpLogic logic;private TenantMemberMapper members;private FoundationSecurityService security;private FoundationAccess access;private AgentWsAuthenticator auth;
 private final FoundationAccess.Subject subject=new FoundationAccess.Subject(false,"7","42");
 @BeforeEach void setup(){logic=mock(StpLogic.class);members=mock(TenantMemberMapper.class);security=mock(FoundationSecurityService.class);access=mock(FoundationAccess.class);auth=new AgentWsAuthenticator(logic,members,security,access);when(logic.getLoginType()).thenReturn("login");when(logic.getConfigOrGlobal()).thenReturn(new SaTokenConfig().setJwtSecretKey("secret"));when(logic.getLoginIdByToken("signed")).thenReturn("42");var member=new TenantMemberDO();member.setId(42L);member.setTenantId(7L);member.setStatus("active");member.setUsername("alice");member.setRole("operator");when(members.selectOne(any())).thenReturn(member);}
 @AfterEach void clear(){TenantContext.clear();}
 @Test void signedTokenUsesCurrentSessionAndPermissionRegardlessOfOldRole(){try(var jwt=mockStatic(SaJwtUtil.class)){jwt.when(()->SaJwtUtil.getPayloadsNotCheck("signed","login","secret")).thenReturn(payload());TenantContext.setTenantId("stale");var identity=auth.authenticate("signed");assertThat(identity.memberId()).isEqualTo("42");assertThat(identity.securitySessionId()).isEqualTo(71);verify(security).verifyTrustedSession(subject,71,true,false);verify(access).require(subject,"foundation.handoff.read");}assertThat(TenantContext.getTenantId()).isNull();}
 @Test void revokedDeviceOrPasswordChangeRejectsSignedHandshake(){doThrow(new ApiException(HttpStatus.UNAUTHORIZED,"revoked")).when(security).verifyTrustedSession(subject,71,true,false);try(var jwt=mockStatic(SaJwtUtil.class)){jwt.when(()->SaJwtUtil.getPayloadsNotCheck("signed","login","secret")).thenReturn(payload());assertThatThrownBy(()->auth.authenticate("signed")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));verifyNoInteractions(access);}assertThat(TenantContext.getTenantId()).isNull();}
 @Test void actionWithdrawalRejectsEvenWhenLegacyRoleWasAdmin(){doThrow(new ApiException(HttpStatus.FORBIDDEN,"denied")).when(access).require(subject,"foundation.handoff.read");try(var jwt=mockStatic(SaJwtUtil.class)){jwt.when(()->SaJwtUtil.getPayloadsNotCheck("signed","login","secret")).thenReturn(payload());assertThatThrownBy(()->auth.authenticate("signed")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));verify(members,never()).selectOne(any());}}
 @Test void realSignatureAndLoginTypeStillRequired(){Map<String,Object> claims=Map.of("tenant_id","7","security_session_id","71","username","alice","exp",Instant.now().plusSeconds(300).getEpochSecond());String token=SaJwtUtil.createToken("login","42",claims,"secret");when(logic.getLoginIdByToken(token)).thenReturn("42");assertThat(auth.authenticate(token).memberId()).isEqualTo("42");for(String invalid:java.util.List.of(SaJwtUtil.createToken("login","42",claims,"wrong"),SaJwtUtil.createToken("platform","42",claims,"secret"))){when(logic.getLoginIdByToken(invalid)).thenReturn("42");assertThatThrownBy(()->auth.authenticate(invalid)).isInstanceOf(ApiException.class);}}
 @Test void malformedDeadlineIsUnauthorizedAndNeverReadsAuthority(){for(Object deadline:java.util.List.of(Long.MAX_VALUE,"9223372036854775808",1.5d)){try(var jwt=mockStatic(SaJwtUtil.class)){var invalid=payload();invalid.set("exp",deadline);jwt.when(()->SaJwtUtil.getPayloadsNotCheck("signed","login","secret")).thenReturn(invalid);assertThatThrownBy(()->auth.authenticate("signed")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));assertThat(TenantContext.getTenantId()).isNull();}}verifyNoInteractions(security,access);}
 @Test void liveConnectionCannotOutliveOriginalJwtOrCurrentPermission(){var expired=new AgentWsIdentity("7","42","alice",71,Instant.now().minusSeconds(1));assertThatThrownBy(()->auth.revalidate(expired)).isInstanceOf(ApiException.class);var valid=new AgentWsIdentity("7","42","alice",71,Instant.now().plusSeconds(300));auth.revalidate(valid);doThrow(new ApiException(HttpStatus.FORBIDDEN,"denied")).when(access).require(subject,"foundation.handoff.read");assertThatThrownBy(()->auth.revalidate(valid)).isInstanceOf(ApiException.class);verify(security,times(2)).verifyTrustedSession(subject,71,true,false);}
 private JSONObject payload(){var p=new JSONObject();p.set(SaJwtUtil.LOGIN_ID,"42");p.set("tenant_id","7");p.set("role","admin");p.set("username","stale");p.set("security_session_id","71");p.set("exp",Instant.now().plusSeconds(300).getEpochSecond());return p;}
}
