package com.skillforge.core.chat.observation;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 跨机构读取必须由真实平台角色收敛作用域，客户端的机构参数不授予权限。 */
class SessionObservationAuthorizerTest {
    @Test void requiresAuthenticatedPlatformRoleAndActor() {
        var access = mock(FoundationAccess.class);
        var authorizer = new SessionObservationAuthorizer(access);
        doThrow(new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证"))
                .when(access).require("foundation.sessions.read");
        assertThrows(ApiException.class, authorizer::platformAdmin);
        reset(access); when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","99"));
        assertThrows(ApiException.class, authorizer::platformAdmin);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"99"));
        var scope = authorizer.platformAdmin();
        assertEquals(SessionObservationScope.Kind.PLATFORM_ADMIN, scope.kind());
        assertEquals("99", scope.actorId()); assertNull(scope.tenantId());
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "已撤权"))
                .when(access).require("foundation.sessions.read");
        assertThrows(ApiException.class, authorizer::platformAdmin);
    }

    @Test void tenantScopeCannotReadAnotherInstitution() {
        var scope = new SessionObservationScope(SessionObservationScope.Kind.TENANT, 7L, "9");
        assertDoesNotThrow(() -> scope.requireTenant(7L));
        assertThrows(IllegalArgumentException.class, () -> scope.requireTenant(8L));
    }

    @Test void tenantObservationKeepsCurrentSessionOwnershipAndDepartmentScope() {
        var access = mock(FoundationAccess.class);
        var subject = new FoundationAccess.Subject(false, "7", "99");
        when(access.currentSubject()).thenReturn(subject);
        when(access.visibleSessionIds(subject, "read")).thenReturn(java.util.List.of("owned-session"));
        var authorizer = new SessionObservationAuthorizer(access);
        var scope = authorizer.tenant();
        assertEquals(7L, scope.tenantId());
        assertEquals(java.util.List.of("owned-session"), authorizer.visibleSessionIds(scope));
        authorizer.requireSession(scope, 7L, "owned-session");
        verify(access).requireSession(subject, "owned-session", "read");
        assertThrows(ApiException.class, () -> authorizer.requireSession(scope, 8L, "owned-session"));
        verify(access, never()).requireSession(subject, "foreign-session", "read");

        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "100"));
        assertThrows(ApiException.class, () -> authorizer.visibleSessionIds(scope));
        assertThrows(ApiException.class, () -> authorizer.requireSession(scope, 7L, "owned-session"));
    }

    @Test void platformCannotMintTenantScopeOrReuseTenantObservationIdentity() {
        var access = mock(FoundationAccess.class);
        var authorizer = new SessionObservationAuthorizer(access);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "99"));
        assertThrows(ApiException.class, authorizer::tenant);
        var platform = authorizer.platformAdmin();
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "99"));
        assertThrows(ApiException.class, () -> authorizer.requireSession(platform, 7L, "session"));
    }
}
