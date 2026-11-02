package com.skillforge.core.foundation.integration;

import static com.skillforge.core.foundation.integration.IntegrationContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** 机构接入契约验证：当前目标、同参重读、一次秘密、固定执行域和状态CAS。 */
class InstitutionIntegrationServiceTest {
    private InstitutionIntegrationMapper mapper;
    private TenantMapper tenants;
    private ServicePrincipalService principals;
    private PlatformManagementService management;
    private InstitutionIntegrationService service;
    private final Map<String, InstitutionOperationRow> operations = new HashMap<>();
    private InstitutionMappingRow savedMapping;

    /** 公开构造器装配，模拟存储只保存本例已提交事实，真实事务与RLS由PG用例验证。 */
    @BeforeEach void setup() {
        mapper = mock(InstitutionIntegrationMapper.class); tenants = mock(TenantMapper.class);
        when(tenants.insert(any(TenantDO.class))).thenReturn(1);
        principals = mock(ServicePrincipalService.class); management = mock(PlatformManagementService.class);
        service = new InstitutionIntegrationService(mapper, tenants, principals, management);
        when(management.actorRef()).thenReturn("management_principal:1");
        when(mapper.activePlanId("free")).thenReturn(11L);
        when(mapper.operation(anyString(),anyString(),anyString())).thenAnswer(c ->
                operations.get(c.getArgument(0) + "/" + c.getArgument(1) + "/" + c.getArgument(2)));
        when(mapper.insertOperation(any())).thenAnswer(c -> {
            InstitutionOperationRow row = c.getArgument(0); operations.put(row.getActorRef() + "/" + row.getOperationKind() + "/" + row.getIdempotencyKey(), row); return 1;
        });
        when(mapper.scopeOperation(anyLong(),anyString(),anyString(),anyString(),anyString())).thenAnswer(c ->
                operations.get(c.getArgument(2) + "/" + c.getArgument(3) + "/" + c.getArgument(4)));
        when(mapper.insertScopeOperation(any())).thenAnswer(c -> {
            InstitutionOperationRow row = c.getArgument(0); operations.put(row.getActorRef() + "/" + row.getOperationKind() + "/" + row.getIdempotencyKey(), row); return 1;
        });
        when(mapper.insertMapping(any())).thenAnswer(c -> {
            savedMapping = c.getArgument(0); savedMapping.setTenantName("机构一"); savedMapping.setCreatedAt(Instant.parse("2026-10-22T00:00:00Z")); return 1;
        });
        when(mapper.mapping(anyLong())).thenAnswer(c -> savedMapping);
        when(mapper.tenantStatus(anyLong())).thenReturn("active");
    }

    /** 请求线程身份在每例结束恢复为空，不污染其他范围用例。 */
    @AfterEach void cleanup() { TenantContext.clear(); ServicePrincipalContext.clear(); }

    /** 开通写真实Tenant与明确默认策略，重复原请求不再开通或签凭证。 */
    @Test void provisionCommitsTenantPolicyMappingAndStableReceiptWithoutCredentials() {
        var request = provision("机构一");
        var created = service.provision(request,"key-1");
        var capture = ArgumentCaptor.forClass(TenantDO.class); verify(tenants).insert(capture.capture());
        assertEquals(11L,capture.getValue().getPlanId()); assertEquals(1234,capture.getValue().getOverrideQuotaApiCallsPerMonth());
        assertEquals("active",capture.getValue().getStatus()); assertNull(TenantContext.getTenantId());
        assertEquals(created,service.provision(request,"key-1"));
        assertEquals(1L,created.revision()); assertNotNull(created.integrationId());
        verify(tenants,times(1)).insert(any(TenantDO.class)); verifyNoInteractions(principals);
        verify(mapper,never()).registerIntegration(anyLong(),anyString());
        verify(mapper,times(1)).insertMapping(any());
        // namespace原创建回执可以查回，但不能隐含授予新Tenant管理权。
        verify(management,never()).requireTenant(anyString(),anyLong());
    }

    /** 同键异参在副作用之前409，namespace拒绝同样不能建Tenant。 */
    @Test void conflictingIdempotencyAndDeniedNamespaceNeverCreateAnotherTenant() {
        service.provision(provision("机构一"),"same-key");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->service.provision(provision("机构二"),"same-key")).getStatus());
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"denied")).when(management).requireNamespace("platform.institutions.provision","crm");
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->service.provision(provision("机构三"),"new-key")).getStatus());
        verify(tenants,times(1)).insert(any(TenantDO.class));
    }

    /** 已有外部键不能被另一个Tenant认领，即使调用者分别有两侧目标授权。 */
    @Test void externalInstitutionCannotBeReboundToAnotherTenant() {
        var row = new InstitutionMappingRow(); row.setTenantId(7L);
        when(mapper.externalMapping("crm","store-1")).thenReturn(row);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->
                service.associate(new AssociateInstitution("8","crm","store-1"),"associate-key")).getStatus());
        verify(mapper,never()).insertMapping(any());
        verify(management).requireNamespace("platform.institutions.associate","crm");
        verify(management).requireTenant("platform.institutions.associate",8L);
    }

    /** 原生/手工ServicePrincipal可用，不要求外部机构映射；固定执行域不得冒用。 */
    @Test void manualIntegrationDoesNotRequireExternalMappingButRejectsAnotherDomain() {
        TenantContext.setTenantId("7"); when(mapper.integrationExists(7L,"native")).thenReturn(true);
        service.requireIntegration("7","native");
        verify(mapper,never()).externalMapping(any(),any());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp-1","7","crm",1,Set.of("sessions.write")));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->service.requireIntegration("7","native")).getStatus());
    }

    /** 管理代部署只需已登记的固定执行域，不要求签发秘密；未登记域仍在任何后续消费前拒绝。 */
    @Test void managementCanValidateRegisteredIntegrationWithoutAnyServicePrincipal() {
        TenantContext.setTenantId("7"); when(mapper.integrationExists(7L,"platform-business")).thenReturn(true);
        service.requireIntegration("7","platform-business");
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->service.requireIntegration("7","missing")).getStatus());
        verifyNoInteractions(principals); verify(mapper,never()).externalMapping(any(),any());
    }

    /** 当前凭据先于机构目录校验，拒绝不会读取任何Session或扫描凭据列表。 */
    @Test void principalAuthorizationSharesOneExactCredentialBoundary() {
        var identity=new ServicePrincipalContext.Identity("sp-1","7","crm",1,Set.of("resources.write"));
        when(mapper.integrationExists(7L,"crm")).thenReturn(true);
        service.requirePrincipal(identity,"resources.write");
        var ordered=inOrder(principals,mapper);
        ordered.verify(principals).requireCurrent(identity,"resources.write");
        ordered.verify(mapper).tenantStatus(7L);
        ordered.verify(mapper).integrationExists(7L,"crm");
        assertNull(TenantContext.getTenantId());verify(principals,never()).list(anyString());
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"revoked")).when(principals).requireCurrent(identity,"resources.write");
        clearInvocations(mapper);
        assertThrows(ApiException.class,()->service.requirePrincipal(identity,"resources.write"));
        verifyNoInteractions(mapper);assertNull(TenantContext.getTenantId());
    }

    /** 签发秘密只交付一次，同键重读安全原回执，不读取或重新生成密钥。 */
    @Test void credentialIssueReplaysOriginalSafeReceiptWithoutSecret() {
        var principal = principal();
        when(principals.issue("7","crm",Set.of("sessions.write"))).thenReturn(new ServicePrincipalService.IssuedPrincipal(principal,"first-key","first-secret"));
        var request = new IssueExecutionCredential("7","crm",Set.of("sessions.write"));
        var first = service.issue(request,"issue-key");
        assertEquals("first-key",first.apiKey()); assertEquals("first-secret",first.signingSecret()); assertNull(TenantContext.getTenantId());
        principal.setCredentialVersion(9L); principal.setApiKeyPrefix("new-prefix");
        var replay = service.issue(request,"issue-key");
        assertNull(replay.apiKey()); assertNull(replay.signingSecret()); assertEquals(1L,replay.principal().credentialVersion());
        assertEquals("first-prefix",replay.principal().apiKeyPrefix());
        verify(principals,times(1)).issue("7","crm",Set.of("sessions.write"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->service.issue(
                new IssueExecutionCredential("7","crm",Set.of("sessions.read")),"issue-key")).getStatus());
    }

    /** 平台管理scope不能签为机构执行凭据；CAS冲突也不能调用轮换。 */
    @Test void managementScopeAndCredentialRevisionMismatchFailBeforeIssuance() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException.class,()->service.issue(
                new IssueExecutionCredential("7","crm",Set.of("platform.institutions.provision")),"key")).getStatus());
        when(mapper.lockPrincipal(7L,"crm",10L)).thenReturn(principal());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->service.rotate(
                new ExecutionCredentialCommand("7","crm","10",2L),"rotate-key")).getStatus());
        verifyNoInteractions(principals); assertNull(TenantContext.getTenantId());
    }

    /** 源执行域冻结，范围状态必须命中旧revision，撤销终态不复活。 */
    @Test void businessScopeStatusUsesFixedIdentityAndRevisionCas() {
        var actor = principal(); actor.setScopes(List.of("business.scopes.write"));
        TenantContext.setTenantId("7"); ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp-1","7","crm",1,Set.of("business.scopes.write")));
        when(mapper.integrationExists(7L,"crm")).thenReturn(true);
        var scope = new IntegrationBusinessScopeRow(); scope.setScopeId("101");scope.setTenantId(7L);scope.setIntegrationId("crm");
        scope.setScopeType("store");scope.setExternalRef("store-1");scope.setDisplayName("门店");scope.setStatus("active");scope.setRevision(1L);
        when(mapper.businessScopeById(7L,"crm","101")).thenReturn(scope); when(mapper.updateBusinessScope(any(),eq(1L))).thenReturn(1);
        var result = service.setBusinessScopeStatus(new BusinessScopeStatus("101","revoked",1L),"revoke-scope");
        assertEquals(2L,result.revision());assertEquals("revoked",result.status());
        assertEquals(result,service.setBusinessScopeStatus(new BusinessScopeStatus("101","revoked",1L),"revoke-scope"));
        verify(mapper,times(1)).updateBusinessScope(any(),eq(1L));
        verify(principals,atLeastOnce()).requireCurrent(ServicePrincipalContext.get(),"business.scopes.write");
        verify(principals,never()).list(anyString());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->service.setBusinessScopeStatus(
                new BusinessScopeStatus("101","active",2L),"reactivate-scope")).getStatus());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,()->service.requireBusinessScopeById("7","crm","101")).getStatus());
    }

    /** 分页和计数共享当前目标谓词，空目标不能查询无限制目录。 */
    @Test void directoryFiltersCurrentTargetsBeforePaginationAndCount() {
        when(management.allowedTenantIds("platform.institutions.read")).thenReturn(Set.of(7L));
        when(mapper.mappings(Set.of(7L),null,null,20,0)).thenReturn(List.of());
        when(mapper.mappingCount(Set.of(7L),null,null)).thenReturn(0L);
        assertEquals(new Page<Institution>(List.of(),0L,1,20),service.institutions(null,null,1,20));
        verify(mapper).mappings(Set.of(7L),null,null,20,0);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException.class,()->service.institutions(null,null,0,20)).getStatus());
    }

    /** 显式开通策略样例，空quota继承套餐。 */
    private ProvisionInstitution provision(String name) { return new ProvisionInstitution("crm","store-1",name,"free",1234,null,null,null,null); }
    /** 安全原始凭证事实，测试不提供哈希或真实秘密。 */
    private ServicePrincipalDO principal() {
        var row = new ServicePrincipalDO();row.setId(10L);row.setTenantId(7L);row.setPrincipalId("sp-1");row.setIntegrationId("crm");
        row.setStatus("active");row.setCredentialVersion(1L);row.setScopes(List.of("sessions.write"));row.setApiKeyPrefix("first-prefix");
        row.setCreatedAt(Instant.parse("2026-10-22T00:00:00Z"));return row;
    }
}
