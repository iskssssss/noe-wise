package com.skillforge.core.foundation.institution;

import static com.skillforge.core.foundation.institution.InstitutionRelationContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.IntegrationContracts.*;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.application.AuditService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InstitutionRelationServiceTest {
    private final InstitutionRelationMapper mapper=mock(InstitutionRelationMapper.class);
    private final PlatformManagementService management=mock(PlatformManagementService.class);
    private final FoundationAccess foundation=mock(FoundationAccess.class);
    private final InstitutionIntegrationService institutions=mock(InstitutionIntegrationService.class);
    private final ResourceQuota quota=mock(ResourceQuota.class);
    private final AuditService audit=mock(AuditService.class);
    private final ObjectMapper json=new ObjectMapper();
    private final InstitutionRelationService service=new InstitutionRelationService(mapper,management,foundation,institutions,quota,audit,json);
    private final Map<Long,InstitutionRelationMapper.Row> rows=new HashMap<>();
    private final Map<String,InstitutionRelationMapper.Receipt> receipts=new HashMap<>();
    private final AtomicLong tree=new AtomicLong(10);
    private final FoundationAccess.Subject headquarters=new FoundationAccess.Subject(false,"7","70");

    @BeforeEach void setup() {
        rows.put(1L,row(1,null,1));rows.put(2L,row(2,null,1));rows.put(3L,row(3,null,1));rows.put(7L,row(7,null,1));
        when(management.authorizedTenantScope(anyString())).thenReturn(new PlatformManagementService.TenantScope("platform_member:1",1,Set.of(1L,2L,3L)));
        when(foundation.currentSubject()).thenReturn(headquarters);
        when(mapper.find(anyLong())).thenAnswer(call->rows.get(call.getArgument(0)));
        when(mapper.treeRevision()).thenAnswer(call->tree.get());
        when(mapper.change(anyLong(),nullable(Long.class),anyLong())).thenAnswer(call->{
            long child=call.getArgument(0),expected=call.getArgument(2);var original=rows.get(child);
            if(original.revision()!=expected)return 0;
            rows.put(child,row(child,call.getArgument(1),expected+1));tree.incrementAndGet();return 1;
        });
        when(mapper.append(anyLong(),anyLong(),nullable(Long.class),anyLong(),anyLong(),anyString(),anyString())).thenReturn(1);
        when(mapper.grantRead(anyLong(),anyLong(),anyLong(),anyLong())).thenReturn(1);
        when(mapper.request(anyString(),anyString(),anyString())).thenAnswer(call->receipts.get(key(call.getArgument(0),call.getArgument(1),call.getArgument(2))));
        when(mapper.receipt(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
            receipts.put(key(call.getArgument(0),call.getArgument(1),call.getArgument(2)),new InstitutionRelationMapper.Receipt(call.getArgument(3),call.getArgument(5)));return 1;
        });
    }
    @AfterEach void clear(){TenantContext.clear();ServicePrincipalContext.clear();ManagementContext.clear();}

    @Test void attachAuthorizesBeforeReadsAndCommitsExactRevisionHistoryReceiptAndAudit() {
        var result=service.attach(new Change(1L,2L,1),"attach-one");
        assertEquals("2",result.parentTenantId());assertEquals(2,result.revision());assertEquals(11,result.treeRevision());
        var order=inOrder(management,mapper);
        order.verify(management).authorizedTenantScope("platform.institution_relations.attach");
        order.verify(mapper).lockRequest("platform_member:1","attach","attach-one");order.verify(mapper).request(anyString(),anyString(),anyString());
        order.verify(mapper).lockTree();order.verify(mapper).find(1L);
        verify(management,times(1)).authorizedTenantScope(anyString());
        verify(mapper).append(anyLong(),eq(1L),eq(2L),eq(2L),eq(11L),eq("platform_member:1"),eq("attach-one"));
        verify(audit).record(isNull(),eq("platform_member:1"),eq("platform.institution_relations.attach"),anyString(),eq("1"),anyString(),anyMap());
    }

    @Test void missingTargetDeniesBeforeExistenceCasAndRequestLookup() {
        assertEquals(403,assertThrows(ApiException.class,()->service.attach(new Change(1L,7L,1),"key")).getStatus().value());
        verifyNoInteractions(mapper,audit);
    }

    @Test void cycleAndStaleRevisionRejectWithoutMutationOrReceipt() {
        when(mapper.wouldCycle(1,2)).thenReturn(true);
        assertEquals(409,assertThrows(ApiException.class,()->service.attach(new Change(1L,2L,1),"cycle")).getStatus().value());
        assertEquals(409,assertThrows(ApiException.class,()->service.attach(new Change(1L,2L,2),"stale")).getStatus().value());
        verify(mapper,never()).change(anyLong(),any(),anyLong());verify(mapper,never()).append(anyLong(),anyLong(),any(),anyLong(),anyLong(),anyString(),anyString());
        verifyNoInteractions(audit);
    }

    @Test void attachMoveAndDetachHaveDistinctLegalStates() {
        rows.put(1L,row(1,2L,2));
        assertThrows(ApiException.class,()->service.attach(new Change(1L,3L,2),"already-parent"));
        var moved=service.move(new Change(1L,3L,2),"move");assertEquals("3",moved.parentTenantId());
        var detached=service.detach(new Change(1L,null,3),"detach");assertNull(detached.parentTenantId());assertEquals(4,detached.revision());
        assertThrows(ApiException.class,()->service.move(new Change(1L,2L,4),"root-move"));
        assertThrows(ApiException.class,()->service.detach(new Change(1L,null,4),"root-detach"));
    }

    @Test void originalReceiptSurvivesLaterMoveAndSameKeyDifferentCommandIsRejected() {
        var command=new Change(1L,2L,1);var first=service.attach(command,"key");
        rows.put(1L,row(1,3L,3));
        assertEquals(first,service.attach(command,"key"));
        assertEquals(409,assertThrows(ApiException.class,()->service.attach(new Change(1L,3L,1),"key")).getStatus().value());
        assertEquals(3,rows.get(1L).revision());verify(mapper,times(1)).lockTree();
    }

    @Test void platformDirectoryPassesCurrentTargetsBeforePaginationAndNeverSynthesizesInvisibleParents() {
        when(mapper.platformRows(Set.of(1L,2L,3L),null,1,1L)).thenReturn(List.of(row(2,null,1)));
        when(mapper.platformCount(Set.of(1L,2L,3L),null)).thenReturn(3L);
        var page=service.platformList(null,2,1);assertEquals(3,page.total());assertEquals("2",page.items().getFirst().tenantId());
        verify(mapper).platformRows(Set.of(1L,2L,3L),null,1,1L);
        assertThrows(ApiException.class,()->service.platformList(7L,1,20));
    }

    @Test void headquartersProvisionPreservesTrustedIdentityAndGrantsOnlyTheCreatedRelation() {
        TenantContext.setTenantId("7");
        when(institutions.createSubInstitution(any(),eq(7L),eq("tenant:7:member:70"))).thenAnswer(call->{
            assertEquals("7",TenantContext.getTenantId());rows.put(11L,row(11,7L,1));tree.incrementAndGet();
            return new Institution("map","11","global","child","integration","child",1,null);
        });
        var first=service.provisionSubInstitution(provision(),"create");
        assertEquals("7",first.parentTenantId());assertEquals("7",TenantContext.getTenantId());
        assertEquals(first,service.provisionSubInstitution(provision(),"create"));
        verify(quota,times(1)).requireSubInstitutionCapacity("7",1);
        verify(mapper).grantRead(anyLong(),eq(7L),eq(11L),anyLong());
        verify(foundation,times(2)).require(headquarters,"tenant.sub_institutions.provision");
        verifyNoInteractions(management);
    }

    @Test void headquartersDirectoryRequiresIndependentActionAndUsesExactGrantQuery() {
        TenantContext.setTenantId("7");
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"no read")).when(foundation).require(headquarters,"tenant.sub_institutions.read");
        assertThrows(ApiException.class,()->service.subInstitutions(1,20));verifyNoInteractions(mapper,institutions);
    }

    @Test void executionAndPlatformMachinesCannotImpersonateHeadquarters() {
        TenantContext.setTenantId("7");var principal=new ServicePrincipalContext.Identity("sp","7","integration",1,Set.of("resources.write"));
        ServicePrincipalContext.set(principal);
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"missing exact scope")).when(institutions).requirePrincipal(principal,"tenant.sub_institutions.provision");
        assertThrows(ApiException.class,()->service.provisionSubInstitution(provision(),"bad"));
        ServicePrincipalContext.clear();ManagementContext.set(new ManagementContext.Identity("1",1,1));
        assertThrows(ApiException.class,()->service.subInstitutions(1,20));verifyNoInteractions(mapper,foundation);
        verify(institutions).requirePrincipal(principal,"tenant.sub_institutions.provision");
    }

    @Test void explicitlyScopedInstitutionMachineKeepsItsRealPrincipalAndTenant() {
        TenantContext.setTenantId("7");var principal=new ServicePrincipalContext.Identity("sp-real","7","integration",1,Set.of("tenant.sub_institutions.provision"));
        ServicePrincipalContext.set(principal);
        when(institutions.createSubInstitution(any(),eq(7L),eq("service_principal:sp-real"))).thenAnswer(call->{
            rows.put(11L,row(11,7L,1));return new Institution("map","11","global","child","integration-new","child",1,null);
        });
        var result=service.provisionSubInstitution(provision(),"machine");assertEquals("7",result.parentTenantId());
        verify(institutions).requirePrincipal(principal,"tenant.sub_institutions.provision");
        verify(mapper).receipt(eq("service_principal:sp-real"),eq("provision"),eq("machine"),anyString(),eq(11L),anyString());
        verifyNoInteractions(foundation);assertEquals("7",TenantContext.getTenantId());
    }

    @Test void successAuditFailurePropagatesToTheOwningTransaction() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyMap());
        assertThrows(IllegalStateException.class,()->service.attach(new Change(1L,2L,1),"audit-failure"));
    }

    @Test void identityJsonMustBeStringsAndRevisionMustBeANumber() throws Exception {
        assertEquals(new Change(1L,2L,3),json.readValue("{\"child_tenant_id\":\"1\",\"parent_tenant_id\":\"2\",\"expected_revision\":3}",Change.class));
        assertThrows(JsonProcessingException.class,()->json.readValue("{\"child_tenant_id\":1,\"parent_tenant_id\":\"2\",\"expected_revision\":3}",Change.class));
        assertThrows(JsonProcessingException.class,()->json.readValue("{\"child_tenant_id\":\"1\",\"parent_tenant_id\":\"2\",\"expected_revision\":\"3\"}",Change.class));
    }
    private ProvisionInstitution provision(){return new ProvisionInstitution("global","child","child","free",null,null,null,null,null);}
    private InstitutionRelationMapper.Row row(long id,Long parent,long revision){return new InstitutionRelationMapper.Row(id,parent,"institution-"+id,"active",revision);}
    private String key(String actor,String operation,String key){return actor+":"+operation+":"+key;}
}
