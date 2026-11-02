package com.skillforge.core.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 请求认证只复用本次数据库事实；每次异步派发仍拒绝当前撤销、停用和MFA变更。 */
class FoundationSecurityRequestVerificationTest {
    private static final FoundationAccess.Subject SUBJECT=new FoundationAccess.Subject(false,"7","11");
    private static final String SCOPE="tenant:7";
    private SecurityMapper mapper;
    private FoundationAccess access;
    private FoundationSecurityService security;
    private MockedStatic<StpUtil> stp;
    private SecurityRows.Session session;

    @BeforeEach void setUp() {
        mapper=mock(SecurityMapper.class);access=mock(FoundationAccess.class);
        security=new FoundationSecurityService(mapper,access,mock(PasswordEncoder.class),mock(CryptoService.class),
                new SkillForgeProperties(),mock(StringRedisTemplate.class),mock(NotificationService.class),mock(AuditService.class));
        session=session(SCOPE,11,Instant.now().plusSeconds(3600),Instant.now().minusSeconds(60),null,"full");
        when(access.currentSubject()).thenReturn(SUBJECT);
        when(mapper.session(SCOPE,71)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isEqualTo("7");return session;});
        when(mapper.accountById(false,7L,11)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isEqualTo("7");return account("active","hash");});
        when(access.snapshot(SUBJECT)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isEqualTo("7");return new FoundationAccess.Snapshot(List.of("member"),List.of("foundation.sessions.read"),9);});
        stp=mockStatic(StpUtil.class);
        stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn("71");
        TenantContext.setTenantId("99");
    }

    @AfterEach void cleanUp() {stp.close();TenantContext.clear();}

    @Test void currentRequestReadsSessionAccountPermissionsAndMfaOnce() {
        when(mapper.mfa(SCOPE,11,false)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isEqualTo("7");return mfa();});
        when(mapper.requireAll(SCOPE)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isEqualTo("7");return true;});

        assertThat(security.verifyCurrentSession(SUBJECT,true,true)).isSameAs(session);

        verify(mapper).session(SCOPE,71);verify(mapper).accountById(false,7L,11);
        verify(mapper).mfa(SCOPE,11,false);verify(mapper).requireAll(SCOPE);
        verifyNoMoreInteractions(mapper);
        verify(access).currentSubject();verify(access).snapshot(SUBJECT);verifyNoMoreInteractions(access);
        stp.verify(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM));
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    @Test void restrictedEndpointStillChecksAccountAndPermissionsWithoutRequiringMfa() {
        session=session(SCOPE,11,Instant.now().plusSeconds(3600),null,null,"enrollment");
        assertThat(security.verifyCurrentSession(SUBJECT,false,false)).isSameAs(session);
        verify(access).snapshot(SUBJECT);
        verify(mapper).accountById(false,7L,11);
        verify(mapper,never()).mfa(anyString(),anyLong(),anyBoolean());
        verify(mapper,never()).requireAll(anyString());
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    @ParameterizedTest @NullSource @ValueSource(strings={"","0","-1","abc","9223372036854775808"})
    void absentOrInvalidJwtSessionReferenceCannotSelectADatabaseSession(String claim) {
        stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn(claim);
        assertSessionInvalid(()->security.verifyCurrentSession(SUBJECT,true,false));
        verify(mapper,never()).session(anyString(),anyLong());
        verify(access,never()).snapshot(any());
    }

    @Test void currentRequestCannotUseSubjectDifferentFromAuthenticatedIdentity() {
        assertSessionInvalid(()->security.verifyCurrentSession(new FoundationAccess.Subject(false,"8","11"),true,false));
        verify(mapper,never()).session(anyString(),anyLong());
        stp.verify(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM),never());
    }

    @ParameterizedTest @EnumSource(InvalidSession.class)
    void invalidDatabaseSessionRejectsBeforePermissionsOrMfa(InvalidSession invalid) {
        session=switch(invalid) {
            case MISSING -> null;
            case REVOKED -> session(SCOPE,11,Instant.now().plusSeconds(3600),null,Instant.now(),"full");
            case EXPIRED -> session(SCOPE,11,Instant.EPOCH,null,null,"full");
            case FOREIGN_SCOPE -> session("tenant:8",11,Instant.now().plusSeconds(3600),null,null,"full");
            case FOREIGN_MEMBER -> session(SCOPE,12,Instant.now().plusSeconds(3600),null,null,"full");
        };
        assertSessionInvalid(()->security.verifyTrustedSession(SUBJECT,71,true,false));
        verify(mapper,never()).accountById(anyBoolean(),any(),anyLong());
        verify(access,never()).snapshot(any());
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    @ParameterizedTest @ValueSource(strings={"absent","disabled","password_changed","password_missing"})
    void invalidAccountCannotReusePreviouslyValidSession(String invalid) {
        SecurityRows.Account account=switch(invalid) {
            case "absent" -> null;
            case "disabled" -> account("disabled","hash");
            case "password_changed" -> account("active","changed");
            default -> account("active",null);
        };
        doReturn(account).when(mapper).accountById(false,7L,11);
        assertSessionInvalid(()->security.verifyCurrentSession(SUBJECT,true,false));
        verify(access,never()).snapshot(any());
        verify(mapper,never()).mfa(anyString(),anyLong(),anyBoolean());
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    @Test void trustedRedispatchReadsCurrentPolicyAndThenCurrentRevocation() {
        security.verifyCurrentSession(SUBJECT,true,false);
        when(mapper.requireAll(SCOPE)).thenReturn(true);
        assertThatThrownBy(()->security.verifyTrustedSession(SUBJECT,71,true,false))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getCode()).isEqualTo("MFA_REQUIRED"));
        session=session(SCOPE,11,Instant.now().plusSeconds(3600),null,Instant.now(),"full");
        assertSessionInvalid(()->security.verifyTrustedSession(SUBJECT,71,true,false));
        verify(mapper,times(3)).session(SCOPE,71);
        verify(mapper,times(2)).accountById(false,7L,11);
        verify(access,times(2)).snapshot(SUBJECT);
        verify(access).currentSubject();
        stp.verify(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM));
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    @Test void voluntaryMfaStillRequiresChallengeWithoutProof() {
        session=session(SCOPE,11,Instant.now().plusSeconds(3600),null,null,"full");
        when(mapper.mfa(SCOPE,11,false)).thenReturn(mfa());
        assertThatThrownBy(()->security.verifyCurrentSession(SUBJECT,true,false))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getCode()).isEqualTo("MFA_REQUIRED"));
    }

    @Test void sensitiveWriteRejectsExpiredStepUpUsingTheSameMfaRead() {
        session=session(SCOPE,11,Instant.now().plusSeconds(3600),Instant.now().minusSeconds(400),null,"full");
        when(mapper.mfa(SCOPE,11,false)).thenReturn(mfa());
        assertThatThrownBy(()->security.verifyCurrentSession(SUBJECT,true,true))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getCode()).isEqualTo("MFA_STEP_UP_REQUIRED"));
        verify(mapper).mfa(SCOPE,11,false);
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    @Test void currentAuthorizationFailureRemainsFailureAndRestoresTenant() {
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED,"foundation.subject.inactive","账号停用",false)).when(access).snapshot(SUBJECT);
        assertThatThrownBy(()->security.verifyTrustedSession(SUBJECT,71,false,false))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getCode()).isEqualTo("foundation.subject.inactive"));
        verify(mapper,never()).mfa(anyString(),anyLong(),anyBoolean());
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    @Test void platformVerificationClearsTenantOnlyInsideAuthentication() {
        var subject=new FoundationAccess.Subject(true,null,"11");
        session=session("platform",11,Instant.now().plusSeconds(3600),null,null,"full");
        when(access.currentSubject()).thenReturn(subject);
        when(mapper.session("platform",71)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isNull();return session;});
        when(mapper.accountById(true,null,11)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isNull();return new SecurityRows.Account(11,null,"admin","hash","active",null,"admin");});
        when(access.snapshot(subject)).thenAnswer(call->{assertThat(TenantContext.getTenantId()).isNull();return new FoundationAccess.Snapshot(List.of("admin"),List.of(),10);});
        assertThat(security.verifyCurrentSession(subject,true,false)).isSameAs(session);
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    private static void assertSessionInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(error.getCode()).isEqualTo("AUTH_SESSION_INVALID");
        });
    }
    private static SecurityRows.Session session(String scope,long member,Instant expires,Instant verified,Instant revoked,String state) {
        return new SecurityRows.Session(71,scope,member,FoundationSecurityService.digest("hash"),null,state,"browser",Instant.EPOCH,expires,verified,revoked);
    }
    private static SecurityRows.Account account(String status,String hash) {return new SecurityRows.Account(11,7L,"member",hash,status,null,"member");}
    private static SecurityRows.Mfa mfa() {return new SecurityRows.Mfa(SCOPE,11,"encrypted",true,-1);}
    private enum InvalidSession {MISSING,REVOKED,EXPIRED,FOREIGN_SCOPE,FOREIGN_MEMBER}
}
