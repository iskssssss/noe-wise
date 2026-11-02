package com.skillforge.core.agent.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper.GrantRow;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 授权固定单链，原授重试读取同ID当前事实；其余命令读取不可变原回执。 */
class AgentTemplateGrantServiceTest {
    private final AgentDeploymentMapper mapper=mock(AgentDeploymentMapper.class);
    private final AgentTemplateService templates=mock(AgentTemplateService.class);
    private final PlatformManagementService management=mock(PlatformManagementService.class);
    private final FoundationAccess foundation=mock(FoundationAccess.class);
    private final AuditService audit=mock(AuditService.class);
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    private final AgentTemplateGrantService service=new AgentTemplateGrantService(mapper,templates,management,foundation,mock(InstitutionIntegrationService.class),audit,json);
    private final Map<Long,GrantRow> rows=new HashMap<>();
    private final Map<String,AgentDeploymentMapper.GrantReceipt> receipts=new HashMap<>();
    @BeforeEach void setup() {
        when(management.actorRef()).thenReturn("platform:9");
        when(management.authorizedTenantScope(anyString())).thenReturn(new PlatformManagementService.TenantScope("platform:9",1,Set.of(7L,8L)));
        when(mapper.findGrantRequest(anyString(),anyString())).thenAnswer(c->rows.values().stream().filter(r->r.actorRef().equals(c.getArgument(0))&&r.requestKey().equals(c.getArgument(1))).findFirst().orElse(null));
        when(mapper.findGrant(anyLong())).thenAnswer(c->rows.get(c.<Long>getArgument(0)));
        when(mapper.findManagedGrant(anyLong(),any(),any())).thenAnswer(c->rows.get(c.<Long>getArgument(0)));
        when(mapper.lockGrant(anyLong())).thenAnswer(c->rows.get(c.<Long>getArgument(0)));
        when(mapper.insertGrant(any())).thenAnswer(c->{var row=c.<GrantRow>getArgument(0);rows.put(row.grantId(),row);return 1;});
        when(mapper.appendGrantRevision(anyLong(),anyString(),anyString(),anyString())).thenReturn(1);
        when(mapper.saveGrantReceipt(anyString(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(c->{receipts.put(c.getArgument(0)+"|"+c.getArgument(1),new AgentDeploymentMapper.GrantReceipt(c.getArgument(2),c.getArgument(4)));return 1;});
        when(mapper.grantReceipt(anyString(),anyString())).thenAnswer(c->receipts.get(c.getArgument(0)+"|"+c.getArgument(1)));
        when(mapper.lockGrantTenant(anyLong())).thenAnswer(c->new AgentDeploymentMapper.TenantRow(c.getArgument(0),null,"active",1));
        when(mapper.grantTenant(anyLong())).thenAnswer(c->new AgentDeploymentMapper.TenantRow(c.getArgument(0),null,"active",1));
        when(templates.getById(31L)).thenReturn(definition());when(templates.lockTemplate(31L)).thenReturn(definition());
    }
    @AfterEach void clear(){TenantContext.clear();}
    @Test void targetOptionsReuseCreateAdmissionWithoutAnyDirectoryReadPermission() {
        TenantContext.setTenantId("7");
        var subject = new FoundationAccess.Subject(false, "7", "70");
        when(foundation.currentSubject()).thenReturn(subject);
        assertEquals(7L, service.requireTenantGrantTargetOptions());
        verify(foundation).require(subject, "tenant.agent_grants.create");
        verify(mapper).grantTenant(7L);
        verifyNoInteractions(templates);
    }
    @Test void targetPermissionIsCheckedBeforeDefinitionLookup() {
        doThrow(denied()).when(management).requireTenant("platform.agent_grants.create",7);
        assertThrows(ApiException.class,()->service.grant(command(),"key"));verifyNoInteractions(mapper,templates);
    }
    @Test void platformOriginalGrantAllowsExplicitRegrantAndSubsetActions() {
        var result=service.grant(new Grant(31L,7L,List.of("deploy"),null,true,3,null),"key");
        assertTrue(result.allowRegrant());assertEquals(3,result.remainingDepth());assertEquals(List.of("deploy"),result.actions());
        verify(templates).requireAuthorizedDeployment(any());verify(audit).record(isNull(),eq("platform:9"),eq("permission.agent_grant.create"),anyString(),anyString(),anyString(),anyMap());
    }
    @Test void illegalDepthOrActionNeverCreatesGrant() {
        for(var command:List.of(new Grant(31L,7L,List.of("source.edit"),null,false,0,null),new Grant(31L,7L,List.of("deploy"),null,true,0,null),new Grant(31L,7L,List.of("deploy"),null,false,1,null)))
            assertThrows(ApiException.class,()->service.grant(command,"key"));
        verify(mapper,never()).insertGrant(any());
    }
    @Test void originalCreateReplayReturnsCurrentNarrowedFactWithoutWidening() {
        var original=service.grant(command(),"key");var row=rows.get(original.grantId());rows.put(row.grantId(),changed(row,"deploy",null,"active",2,false,0));
        var replay=service.grant(command(),"key");assertEquals(original.grantId(),replay.grantId());assertEquals(2,replay.revision());assertEquals(List.of("deploy"),replay.actions());
        verify(mapper,times(1)).insertGrant(any());
    }
    @Test void readOnlyReceiptMatchesOriginalActorAndFingerprintAndKeepsCurrentFact() {
        assertEquals("NOT_FOUND",service.grantReceipt(command(),"key").status());
        var created=service.grant(command(),"key");
        var row=rows.get(created.grantId());rows.put(row.grantId(),changed(row,"deploy",null,"revoked",2,false,0));
        var receipt=service.grantReceipt(command(),"key");
        assertEquals("FOUND",receipt.status());assertEquals("revoked",receipt.result().status());
        assertThrows(ApiException.class,()->service.grantReceipt(new Grant(31L,7L,List.of("execute"),null,false,0,null),"key"));
        when(management.actorRef()).thenReturn("platform:other");
        assertEquals("NOT_FOUND",service.grantReceipt(command(),"key").status());
        verify(mapper,times(1)).insertGrant(any());verify(mapper,times(1)).appendGrantRevision(anyLong(),anyString(),anyString(),anyString());
        verify(management,atLeastOnce()).requireTenant("platform.agent_grants.read",7L);
        verify(management,atLeastOnce()).requireDefinition("platform.agent_grants.read",31L);
    }
    @Test void revokedCreateReplayNeverReactivatesAndNewKeyCreatesNewIdentity() {
        var original=service.grant(command(),"key");var row=rows.get(original.grantId());rows.put(row.grantId(),changed(row,"deploy,execute",null,"revoked",2,false,0));
        assertEquals("revoked",service.grant(command(),"key").status());
        assertNotEquals(original.grantId(),service.grant(command(),"new-key").grantId());
        verify(mapper,times(2)).insertGrant(any());
    }
    @Test void sameCreateKeyWithDifferentScopeConflicts() {
        service.grant(command(),"key");assertThrows(ApiException.class,()->service.grant(new Grant(31L,7L,List.of("execute"),null,false,0,null),"key"));
        verify(mapper,times(1)).insertGrant(any());
    }
    @Test void sameCreateKeyCannotChooseAnotherTargetOrDefinition() {
        service.grant(command(),"key");
        assertThrows(ApiException.class,()->service.grant(new Grant(31L,8L,List.of("deploy","execute"),null,false,0,null),"key"));
        assertThrows(ApiException.class,()->service.grant(new Grant(32L,7L,List.of("deploy","execute"),null,false,0,null),"key"));
        verify(mapper,times(1)).insertGrant(any());
        verify(mapper,times(3)).lockExternal(eq("grant:platform:9"),eq(0L),anyString());
    }
    @Test void auditFailureNeverReturnsSuccessfulGrant() {
        doThrow(new IllegalStateException("audit_failed")).when(audit).record(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyMap());
        assertThrows(IllegalStateException.class,()->service.grant(command(),"audit-failure"));
    }
    @Test void narrowingCannotExpandActionsExpiryOrDepth() {
        rows.put(41L,row(41,7,"deploy",null,false,0,null,null));
        assertThrows(ApiException.class,()->service.narrow(new Narrow(41L,1,List.of("deploy","execute"),null,false,0),"narrow"));
        assertThrows(ApiException.class,()->service.narrow(new Narrow(41L,1,List.of("deploy"),null,true,1),"regrant"));
        verify(mapper,never()).narrowGrant(anyLong(),anyLong(),anyString(),any(),anyBoolean(),anyInt());
    }
    @Test void revokeUsesCasAndOriginalResponseSurvivesLaterReads() {
        rows.put(41L,row(41,7,"deploy,execute",null,false,0,null,null));
        when(mapper.revokeGrant(eq(41L),eq(1L),eq("platform:9"),eq("revoke"),anyString())).thenAnswer(c->{rows.put(41L,changed(rows.get(41L),"deploy,execute",null,"revoked",2,false,0));return 1;});
        var result=service.revoke(new Revoke(41L,1),"revoke");assertEquals("revoked",result.status());
        assertEquals(result,service.revoke(new Revoke(41L,1),"revoke"));verify(mapper,times(1)).revokeGrant(anyLong(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void failedHistoryOrAuditDoesNotReturnSuccess() {
        when(mapper.appendGrantRevision(anyLong(),anyString(),anyString(),anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->service.grant(command(),"failed"));verifyNoInteractions(audit);
    }
    @Test void admissionLocksOnlyExactRootToLeafThenTenantRows() {
        rows.put(40L,row(40,7,"deploy,execute",null,true,2,null,null));rows.put(41L,row(41,8,"deploy",null,false,0,40L,7L));
        when(mapper.chainAvailable(anyString(),eq(8L),eq(31L),eq("deploy"))).thenReturn(true);
        var chain=service.requireChainForAdmission(41,8,31,"deploy");assertEquals(List.of(40L,41L),chain.hops().stream().map(GrantHop::grantId).toList());
        var order=inOrder(mapper);order.verify(mapper).lockGrant(40);order.verify(mapper).lockGrant(41);
        verify(mapper,never()).grants(anyLong());verify(mapper,never()).lockExternal(anyString(),anyLong(),anyString());
    }
    @Test void frozenChainPreservesOriginalIdentityAndExplicitAction() throws Exception {
        var chain=new GrantChain(31L,"platform",null,List.of(new GrantHop(40L,1,"platform",null,7L,null,null,null,null)));
        when(mapper.chainAvailable(anyString(),eq(7L),eq(31L),eq("execute"))).thenReturn(false);
        assertThrows(ApiException.class,()->service.requireFrozenChain(chain,7,31,"execute"));
        verify(mapper).chainAvailable(eq(json.writeValueAsString(chain)),eq(7L),eq(31L),eq("execute"));verify(mapper,never()).grants(anyLong());
    }
    @Test void parentNarrowingDoesNotRequireCurrentRevisionToEqualFrozenRevision() {
        var frozen=new GrantChain(31L,"platform",null,List.of(new GrantHop(40L,1,"platform",null,7L,null,null,null,null)));
        when(mapper.chainAvailable(anyString(),eq(7L),eq(31L),eq("deploy"))).thenReturn(true);
        assertDoesNotThrow(()->service.requireFrozenChain(frozen,7,31,"deploy"));verify(mapper,never()).findGrant(anyLong());
    }
    @Test void tenantOriginalGrantRequiresSourceOwnershipAndCurrentRelation() {
        TenantContext.setTenantId("7");when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","9"));
        assertThrows(ApiException.class,()->service.tenantGrant(new Grant(31L,8L,List.of("deploy"),null,false,0,null),"wrong-owner"));
        var tenantSource=definition();tenantSource.setOwnerKind("tenant");tenantSource.setOwnerTenantId(7L);when(templates.getById(31L)).thenReturn(tenantSource);when(templates.lockTemplate(31L)).thenReturn(tenantSource);
        assertThrows(ApiException.class,()->service.tenantGrant(new Grant(31L,8L,List.of("deploy"),null,false,0,null),"missing-relation"));
        when(mapper.grantRelation(8,7)).thenReturn(new AgentDeploymentMapper.RelationRow(91L,2));
        var result=service.tenantGrant(new Grant(31L,8L,List.of("deploy"),null,false,0,null),"grant");assertEquals(91L,result.relationRef());assertEquals(2,result.relationRevision());assertEquals(7L,result.grantorTenantId());
    }
    @Test void platformDirectoryBatchesOnlyAuthorizedDefinitionsAndPreservesEveryGrantFact() {
        var grants=List.of(displayRow(1,31),displayRow(2,32),displayRow(3,33),displayRow(4,31),displayRow(5,99),displayRow(6,777));
        when(mapper.grants(7)).thenReturn(grants);
        when(management.allowedDefinitionIds("platform.agent_grants.read")).thenReturn(Set.of(31L,32L,33L,99L));
        var displays=Map.of(31L,new com.skillforge.core.agent.model.AgentTemplateDisplay(31L,"一","1"),
                32L,new com.skillforge.core.agent.model.AgentTemplateDisplay(32L,"二","2"),
                33L,new com.skillforge.core.agent.model.AgentTemplateDisplay(33L,"三","3"));
        when(templates.displays(List.of(31L,32L,33L,99L))).thenReturn(displays);
        var result=service.list(7);
        assertEquals(5,result.total());
        assertEquals(grants.subList(0,5).stream().map(row->{var d=displays.get(row.definitionId());return displayView(row,d==null?null:d.name(),d==null?null:d.version());}).toList(),result.items());
        var order=inOrder(management,mapper,templates);
        order.verify(management).requireTenant("platform.agent_grants.read",7L);
        order.verify(management).allowedDefinitionIds("platform.agent_grants.read");
        order.verify(mapper).grants(7L);
        order.verify(templates).displays(List.of(31L,32L,33L,99L));
        verify(templates,never()).getById(anyLong());
        clearInvocations(templates,mapper);
        when(management.allowedDefinitionIds("platform.agent_grants.read")).thenReturn(Set.of());
        assertTrue(service.list(7).items().isEmpty()); verifyNoInteractions(templates);
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"拒绝")).when(management).requireTenant("platform.agent_grants.read",7L);
        clearInvocations(mapper,templates);
        assertThrows(ApiException.class,()->service.list(7));verifyNoInteractions(mapper,templates);
    }
    @Test void tenantIssuedDirectoryUsesTheSameBatchProjectionAfterCurrentAuthorization() {
        TenantContext.setTenantId("7");var subject=new FoundationAccess.Subject(false,"7","9");
        when(foundation.currentSubject()).thenReturn(subject);
        var rows=List.of(displayRow(1,31),displayRow(2,32),displayRow(3,33),displayRow(4,31),displayRow(5,99));
        when(mapper.issuedGrants(7)).thenReturn(rows);
        when(templates.displays(List.of(31L,32L,33L,99L))).thenReturn(Map.of());
        assertEquals(rows.stream().map(row->displayView(row,null,null)).toList(),service.tenantList().items());
        var order=inOrder(foundation,mapper,templates);
        order.verify(foundation).require(subject,"tenant.agent_grants.read");
        order.verify(mapper).issuedGrants(7L);order.verify(templates).displays(List.of(31L,32L,33L,99L));
        verify(templates,never()).getById(anyLong());
        clearInvocations(templates);when(mapper.issuedGrants(7)).thenReturn(List.of());
        assertTrue(service.tenantList().items().isEmpty());verifyNoInteractions(templates);
    }
    private GrantRow displayRow(long id,long definition) {
        return new GrantRow(id,definition,7L,"deploy,execute",Instant.parse("2030-01-01T00:00:00Z"),"active",2,
                "actor","request-"+id,"fingerprint",null,null,Instant.EPOCH,"platform",null,"tenant",8L,10L,1L,20L,3L,true,1);
    }
    private GrantView displayView(GrantRow row,String name,String version) {
        return new GrantView(row.grantId(),row.definitionId(),row.targetTenantId(),List.of(row.actions().split(",")),row.expiresAt(),row.status(),row.revision(),
                name,version,row.ownerKind(),row.ownerTenantId(),row.grantorKind(),row.grantorTenantId(),row.parentGrantId(),row.parentGrantRevision(),row.relationRef(),row.relationRevision(),row.allowRegrant(),row.remainingDepth());
    }
    private Grant command(){return new Grant(31L,7L,List.of("deploy","execute"),null,false,0,null);}
    private GrantRow row(long id,long target,String actions,Instant expiry,boolean regrant,int depth,Long parent,Long issuer) {
        return new GrantRow(id,31L,target,actions,expiry,"active",1,"platform:9","key","f".repeat(64),null,null,Instant.EPOCH,"platform",null,issuer==null?"platform":"tenant",issuer,parent,parent==null?null:1L,issuer==null?null:91L,issuer==null?null:1L,regrant,depth);
    }
    private GrantRow changed(GrantRow r,String actions,Instant expiry,String status,long revision,boolean regrant,int depth) {
        return new GrantRow(r.grantId(),r.definitionId(),r.targetTenantId(),actions,expiry,status,revision,r.actorRef(),r.requestKey(),r.fingerprint(),r.revokeKey(),r.revokeFingerprint(),r.createdAt(),r.ownerKind(),r.ownerTenantId(),r.grantorKind(),r.grantorTenantId(),r.parentGrantId(),r.parentGrantRevision(),r.relationRef(),r.relationRevision(),regrant,depth);
    }
    private AgentTemplateDO definition(){var v=new AgentTemplateDO();v.setId(31L);v.setOwnerKind("platform");v.setTemplateKey("definition");v.setName("定义");v.setVersion("1.0.0");v.setStatus("published");v.setDistributionPolicy(AgentDistributionPolicy.AUTHORIZED_DEPLOY);return v;}
    private ApiException denied(){return new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"拒绝");}
}
