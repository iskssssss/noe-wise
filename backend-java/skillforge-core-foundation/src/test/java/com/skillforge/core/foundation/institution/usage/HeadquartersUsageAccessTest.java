package com.skillforge.core.foundation.institution.usage;

import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 总部角色动作与明确许可交集；关系/许可修订和SP scope均参与游标授权身份。 */
class HeadquartersUsageAccessTest {
    private final FoundationAccess foundation=mock(FoundationAccess.class);
    private final InstitutionIntegrationService institutions=mock(InstitutionIntegrationService.class);
    private final HeadquartersUsageMapper mapper=mock(HeadquartersUsageMapper.class);
    private final HeadquartersUsageAccess access=new HeadquartersUsageAccess(foundation,institutions,mapper);
    private final FoundationAccess.Subject actor=new FoundationAccess.Subject(false,"7","71");

    HeadquartersUsageAccessTest() {
        TenantContext.setTenantId("7");
        when(foundation.currentSubject()).thenReturn(actor);
        when(foundation.snapshot(actor)).thenReturn(new FoundationAccess.Snapshot(List.of("reader"),List.of(HeadquartersUsageActions.SUMMARY),3));
        when(mapper.currentTargets(7,HeadquartersUsageActions.SUMMARY)).thenReturn(List.of(new HeadquartersUsageMapper.TargetGrant(8,81,1,82,1)));
    }
    @AfterEach void clear() { TenantContext.clear();ServicePrincipalContext.clear();ManagementContext.clear(); }

    @Test void summaryDoesNotGrantDetailsEventsOrFutureChildren() {
        var scope=access.authorize(HeadquartersUsageActions.SUMMARY);
        assertEquals(Set.of(8L),scope.tenantIds());assertEquals("7",scope.actorTenantId());
        assertThrows(ApiException.class,()->access.authorize(HeadquartersUsageActions.DETAILS));
        assertThrows(ApiException.class,()->access.authorize(HeadquartersUsageActions.EVENTS));
        verify(mapper,never()).currentTargets(7,HeadquartersUsageActions.DETAILS);
        when(mapper.currentTargets(7,HeadquartersUsageActions.SUMMARY)).thenReturn(List.of());
        assertTrue(access.authorize(HeadquartersUsageActions.SUMMARY).tenantIds().isEmpty());
    }
    @Test void currentFoundationSnapshotRevokesStillAuthenticatedActor() {
        access.authorize(HeadquartersUsageActions.SUMMARY);
        when(foundation.snapshot(actor)).thenReturn(new FoundationAccess.Snapshot(List.of(),List.of(),4));
        assertThrows(ApiException.class,()->access.authorize(HeadquartersUsageActions.SUMMARY));
        verify(mapper,times(1)).currentTargets(anyLong(),anyString());
    }
    @Test void unchangedTargetSetStillChangesVersionWhenRelationOrGrantChanges() {
        var first=access.authorize(HeadquartersUsageActions.SUMMARY);
        when(mapper.currentTargets(7,HeadquartersUsageActions.SUMMARY)).thenReturn(List.of(new HeadquartersUsageMapper.TargetGrant(8,91,1,92,3)));
        var reattached=access.authorize(HeadquartersUsageActions.SUMMARY);
        assertEquals(first.tenantIds(),reattached.tenantIds());
        assertNotEquals(first.authorizationVersion(),reattached.authorizationVersion());
        when(foundation.snapshot(actor)).thenReturn(new FoundationAccess.Snapshot(List.of("reader"),List.of(HeadquartersUsageActions.SUMMARY),4));
        assertNotEquals(reattached.authorizationVersion(),access.authorize(HeadquartersUsageActions.SUMMARY).authorizationVersion());
    }
    @Test void machineUsesCurrentScopeAndScopeChangesInvalidateVersion() {
        var first=new ServicePrincipalContext.Identity("service-1","7","integration-1",1,Set.of(HeadquartersUsageActions.SUMMARY,"sessions.read"));
        ServicePrincipalContext.set(first);
        var before=access.authorize(HeadquartersUsageActions.SUMMARY);
        verify(institutions).requirePrincipal(first,HeadquartersUsageActions.SUMMARY);
        var narrowed=new ServicePrincipalContext.Identity("service-1","7","integration-1",1,Set.of(HeadquartersUsageActions.SUMMARY));
        ServicePrincipalContext.set(narrowed);
        var after=access.authorize(HeadquartersUsageActions.SUMMARY);
        assertEquals(before.actorRef(),after.actorRef());assertNotEquals(before.authorizationVersion(),after.authorizationVersion());
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"当前scope已撤销"))
                .when(institutions).requirePrincipal(narrowed,HeadquartersUsageActions.SUMMARY);
        assertThrows(ApiException.class,()->access.authorize(HeadquartersUsageActions.SUMMARY));
        verifyNoInteractions(foundation);
    }
    @Test void platformAndMismatchedTenantCannotBorrowHeadquartersScope() {
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"72"));
        assertThrows(ApiException.class,()->access.authorize(HeadquartersUsageActions.SUMMARY));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("service-1","8","integration-1",1,Set.of(HeadquartersUsageActions.SUMMARY)));
        assertThrows(ApiException.class,()->access.authorize(HeadquartersUsageActions.SUMMARY));
        verifyNoInteractions(mapper,institutions);
    }

    @Test void publicReadRejectsForgedAuthorizationBeforeRunningTheOperation() {
        var current=access.authorize(HeadquartersUsageActions.SUMMARY);
        var forged=List.of(
                new HeadquartersUsageAccess.Authorization("9",current.actorRef(),current.authorizationVersion(),current.action(),current.tenantIds()),
                new HeadquartersUsageAccess.Authorization(current.actorTenantId(),"tenant_member:7:72",current.authorizationVersion(),current.action(),current.tenantIds()),
                new HeadquartersUsageAccess.Authorization(current.actorTenantId(),current.actorRef(),"forged-version",current.action(),current.tenantIds()),
                new HeadquartersUsageAccess.Authorization(current.actorTenantId(),current.actorRef(),current.authorizationVersion(),HeadquartersUsageActions.DETAILS,current.tenantIds()),
                new HeadquartersUsageAccess.Authorization(current.actorTenantId(),current.actorRef(),current.authorizationVersion(),current.action(),Set.of(8L,9L)));
        for(var authorization:forged)assertReadDeniedBeforeOperation(authorization);
        assertReadDeniedBeforeOperation(null);
    }

    @Test void publicReadRejectsOldGrantAndSubjectVersionsEvenWhenTargetsStayTheSame() {
        var before=access.authorize(HeadquartersUsageActions.SUMMARY);
        when(mapper.currentTargets(7,HeadquartersUsageActions.SUMMARY))
                .thenReturn(List.of(new HeadquartersUsageMapper.TargetGrant(8,81,2,82,1)));
        assertReadDeniedBeforeOperation(before);
        var renewed=access.authorize(HeadquartersUsageActions.SUMMARY);
        assertEquals(before.tenantIds(),renewed.tenantIds());
        when(foundation.snapshot(actor)).thenReturn(new FoundationAccess.Snapshot(
                List.of("reader"),List.of(HeadquartersUsageActions.SUMMARY),4));
        assertReadDeniedBeforeOperation(renewed);
    }

    @Test void publicReadRechecksRevokedCurrentPermission() {
        var before=access.authorize(HeadquartersUsageActions.SUMMARY);
        when(foundation.snapshot(actor)).thenReturn(new FoundationAccess.Snapshot(List.of(),List.of(),4));
        assertReadDeniedBeforeOperation(before);
    }

    private void assertReadDeniedBeforeOperation(HeadquartersUsageAccess.Authorization authorization) {
        var invoked=new AtomicBoolean();
        var rejected=assertThrows(ApiException.class,()->access.read(authorization,()->{
            invoked.set(true);return "unexpected_read";
        }));
        assertEquals(403,rejected.getStatus().value());
        assertFalse(invoked.get());assertNull(HeadquartersUsageAccess.currentAuthorization());
        assertEquals("7",TenantContext.getTenantId());
    }
}
