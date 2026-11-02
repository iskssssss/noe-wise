package com.skillforge.core.foundation.institution;

import static com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.application.AuditService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 独立目标许可的协议、当前双目标授权、幂等和本机构/总部身份边界。 */
class InstitutionDeploymentManagementServiceTest {
    private static final String READ = "tenant.agent_deployments.read";
    private final InstitutionDeploymentManagementMapper mapper = mock(InstitutionDeploymentManagementMapper.class);
    private final InstitutionRelationMapper receipts = mock(InstitutionRelationMapper.class);
    private final PlatformManagementService management = mock(PlatformManagementService.class);
    private final FoundationAccess foundation = mock(FoundationAccess.class);
    private final InstitutionIntegrationService institutions = mock(InstitutionIntegrationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final InstitutionDeploymentManagementService service = new InstitutionDeploymentManagementService(
            mapper, receipts, management, foundation, institutions, audit, json);
    private final Map<String, InstitutionRelationMapper.Receipt> history = new HashMap<>();
    private final FoundationAccess.Subject headquarters = new FoundationAccess.Subject(false, "7", "70");

    /** 夹具模拟领域依赖；数据库约束及正式审计另由受限 PG 测试验证。 */
    @BeforeEach void setup() {
        when(management.authorizedTenantScope(anyString())).thenReturn(new PlatformManagementService.TenantScope("platform_member:1", 1, Set.of(7L, 8L, 9L)));
        when(foundation.currentSubject()).thenReturn(headquarters);
        when(mapper.lockTenants(7, 8)).thenReturn(List.of(new InstitutionDeploymentManagementMapper.TenantRow(7, "active"),
                new InstitutionDeploymentManagementMapper.TenantRow(8, "warning")));
        when(mapper.currentRelation(7, 8)).thenReturn(new InstitutionDeploymentManagementMapper.RelationRow(80, 3));
        when(mapper.insert(anyLong(), anyLong(), anyLong(), anyString(), anyLong())).thenReturn(1);
        when(receipts.request(anyString(), anyString(), anyString())).thenAnswer(call -> history.get(historyKey(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
        when(receipts.receipt(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            history.put(historyKey(call.getArgument(0), call.getArgument(1), call.getArgument(2)),
                    new InstitutionRelationMapper.Receipt(call.getArgument(3), call.getArgument(5)));
            return 1;
        });
    }

    /** 每个测试恢复线程身份，不让许可凭据污染后续请求。 */
    @AfterEach void clear() {
        TenantContext.clear(); ServicePrincipalContext.clear(); ManagementContext.clear();
        assertNull(InstitutionDeploymentManagementService.currentTarget());
    }

    /** 候选复用当前动作与独立目标许可，不经过普通机构目录读取。 */
    @Test void targetOptionsContainOnlyOwnTenantAndExactActionTargets() {
        TenantContext.setTenantId("7");
        when(mapper.authorizedTargets(7, READ)).thenReturn(Set.of(8L));
        var scope = service.requireTargetOptions(READ);
        assertEquals(7, scope.actorTenantId()); assertEquals(Set.of(7L, 8L), scope.tenantIds());
        verify(foundation).require(headquarters, READ);
        verify(mapper).authorizedTargets(7, READ);
        assertThrows(UnsupportedOperationException.class, () -> scope.tenantIds().add(9L));
        assertThrows(ApiException.class, () -> service.requireTargetOptions("tenant.sub_institutions.read"));
    }

    /** 当前平台动作与双目标必须先于回执和业务读取。 */
    @Test void createsExactIndependentGrantAndReceiptAuditWithCurrentDualTargetAuthorization() {
        var result = service.create(create(READ), "create-one");
        assertEquals("7", result.actorTenantId()); assertEquals("8", result.targetTenantId());
        assertEquals("80", result.relationRef()); assertEquals(3, result.relationRevision());
        assertEquals(READ, result.action()); assertEquals("active", result.status());
        var ordered = inOrder(management, receipts, mapper, audit);
        ordered.verify(management).authorizedTenantScope("platform.institution_management_grants.create");
        ordered.verify(receipts).lockRequest("platform_member:1", "deployment_management_grant.create", "create-one");
        ordered.verify(receipts).request(anyString(), anyString(), anyString());
        ordered.verify(mapper).lockTenants(7, 8); ordered.verify(mapper).currentRelation(7, 8);
        verify(mapper).insert(anyLong(), eq(7L), eq(8L), eq(READ), eq(80L));
        verify(audit).record(isNull(), eq("platform_member:1"), eq("platform.institution_management_grants.create"),
                eq("institution_management_grant"), eq(result.grantId()), anyString(), anyMap());
        verifyNoInteractions(foundation, institutions);
    }

    /** 单边目标授权不能读取旧成功回执，也不能对另一方创建许可。 */
    @Test void losingEitherTargetRejectsEvenAnExistingIdempotentReceipt() {
        service.create(create(READ), "same");
        clearInvocations(mapper, receipts, audit);
        for (var targets : List.of(Set.of(7L), Set.of(8L))) {
            when(management.authorizedTenantScope(anyString())).thenReturn(new PlatformManagementService.TenantScope("platform_member:1", 2, targets));
            assertEquals(403, assertThrows(ApiException.class, () -> service.create(create(READ), "same")).getStatus().value());
        }
        verifyNoInteractions(mapper, receipts, audit);
    }

    /** 原回执可重读，但不能将同键用于另一项部署动作。 */
    @Test void originalReceiptDoesNotRecreateGrantAfterRelationChangedAndDifferentCommandConflicts() {
        var command = create(READ); var first = service.create(command, "same");
        when(mapper.currentRelation(7, 8)).thenReturn(null);
        assertEquals(first, service.create(command, "same"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.create(create("tenant.agent_deployments.bind"), "same")).getStatus().value());
        verify(mapper, times(1)).insert(anyLong(), anyLong(), anyLong(), anyString(), anyLong());
    }

    /** 当前关系、预期修订、运营状态及已有 active 许可均为创建前置条件。 */
    @Test void relationshipRevisionStatusAndDuplicateAreIndependentCreationPreconditions() {
        assertEquals(409, assertThrows(ApiException.class, () -> service.create(new Create(7L, 8L, READ, 2L), "stale")).getStatus().value());
        when(mapper.activeExists(7, 8, READ, 80)).thenReturn(true);
        assertEquals(409, assertThrows(ApiException.class, () -> service.create(create(READ), "duplicate")).getStatus().value());
        when(mapper.lockTenants(7, 8)).thenReturn(List.of(new InstitutionDeploymentManagementMapper.TenantRow(7, "suspended"),
                new InstitutionDeploymentManagementMapper.TenantRow(8, "active")));
        assertEquals(403, assertThrows(ApiException.class, () -> service.create(create(READ), "inactive")).getStatus().value());
        verify(mapper, never()).insert(anyLong(), anyLong(), anyLong(), anyString(), anyLong());
    }

    /** 撤销只更新 active 的精确修订，重复新命令不作幂等别名或激活。 */
    @Test void revokeUsesCurrentDualTargetAndCasWhileOldReceiptRemainsImmutable() {
        var active = grant("active", 1);
        when(mapper.visible(10, Set.of(7L, 8L, 9L))).thenReturn(active);
        when(mapper.lockGrant(10)).thenReturn(active);
        when(mapper.revoke(10, 1)).thenReturn(1);
        var command = new Revoke(10L, 1L); var result = service.revoke(command, "revoke");
        assertEquals("revoked", result.status()); assertEquals(2, result.revision());
        when(mapper.lockGrant(10)).thenReturn(grant("revoked", 2));
        assertEquals(result, service.revoke(command, "revoke"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.revoke(new Revoke(10L, 2L), "new-revoke")).getStatus().value());
        verify(mapper, times(1)).revoke(anyLong(), anyLong());
        verify(audit).record(isNull(), anyString(), eq("platform.institution_management_grants.revoke"), anyString(), eq("10"), anyString(), anyMap());
    }

    /** 当前可见范围由 SQL 同时过滤总部和目标，再分页计数。 */
    @Test void listingSuppliesBothTargetScopeAndPagingToTheMapper() {
        when(mapper.rows(Set.of(7L, 8L, 9L), null, null, 1, 1)).thenReturn(List.of(grant("active", 1)));
        when(mapper.count(Set.of(7L, 8L, 9L), null, null)).thenReturn(3L);
        var page = service.platformList(null, null, 2, 1);
        assertEquals(3, page.total()); assertEquals("10", page.items().getFirst().grantId());
        assertThrows(ApiException.class, () -> service.platformList(1L, null, 1, 20));
    }

    /** 跨目标权限锁精确关系，本 Tenant 不生成跨域凭据。 */
    @Test void headquartersRequiresOwnActionAndExactGrantAndRetainsRealMemberReference() {
        TenantContext.setTenantId("7");
        when(mapper.lockAuthorization(7, 8, READ)).thenReturn(grant("active", 1));
        var target = service.requireTarget("8", READ);
        assertEquals("tenant:7:member:70", target.actorRef()); assertEquals("7", target.actorTenantId());
        assertEquals("8", target.targetTenantId()); assertEquals("80", target.relationRef()); assertEquals(3, target.relationRevision());
        verify(foundation).require(headquarters, READ);
        assertNull(service.requireTarget("7", READ)); assertNull(InstitutionDeploymentManagementService.currentTarget());
        assertEquals(403, assertThrows(ApiException.class, () -> service.requireTarget("9", READ)).getStatus().value());
        assertEquals("7", TenantContext.getTenantId());
    }

    /** source impact 交集缺动作返回空；失效账号及 IAM 故障不能被降为成功空结果。 */
    @Test void targetIntersectionDoesNotRequireDeploymentPermissionForSourceRead() {
        TenantContext.setTenantId("7");
        assertEquals(Set.of(), service.authorizedTargets(READ)); verify(mapper, never()).authorizedTargets(anyLong(), anyString());
        when(foundation.allowed(headquarters, READ)).thenReturn(true);
        when(mapper.authorizedTargets(7, READ)).thenReturn(Set.of(8L)); assertEquals(Set.of(8L), service.authorizedTargets(READ));
        when(foundation.allowed(headquarters, READ)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "inactive"));
        assertEquals(401, assertThrows(ApiException.class, () -> service.authorizedTargets(READ)).getStatus().value());
    }

    /** 机构机器保持原固定域；跨机构及平台身份都不能冒充总部人员。 */
    @Test void servicePrincipalCanOnlyUseItsOwnTenantAndNeverGetsCrossTargetIntersection() {
        TenantContext.setTenantId("7");
        var principal = new ServicePrincipalContext.Identity("sp-real", "7", "integration", 1, Set.of(READ));
        ServicePrincipalContext.set(principal);
        assertEquals(Set.of(), service.authorizedTargets(READ));
        assertEquals(403, assertThrows(ApiException.class, () -> service.requireTarget("8", READ)).getStatus().value());
        assertNull(service.requireTarget("7", READ));
        verify(institutions).requirePrincipal(principal, READ); verifyNoInteractions(foundation, mapper);
        ServicePrincipalContext.clear(); ManagementContext.set(new ManagementContext.Identity("1", 1, 1));
        assertThrows(ApiException.class, () -> service.requireTarget("8", READ));
    }

    /** 未授权动作、非法 ID 或原主体与线程机构不符均在任何目标 SQL 之前拒绝。 */
    @Test void unknownActionAndForgedCurrentTenantNeverReachTargetLookup() {
        TenantContext.setTenantId("8");
        assertThrows(ApiException.class, () -> service.requireTarget("9", READ));
        assertThrows(ApiException.class, () -> service.requireTarget("8", "tenant.sessions.read"));
        assertThrows(ApiException.class, () -> service.requireTarget("+8", READ));
        assertThrows(ApiException.class, () -> service.authorizedTargets("tenant.sub_institutions.read"));
        verifyNoInteractions(mapper, institutions);
    }

    /** 审计失败必须传播给事务所有者，不能返回许可成功。 */
    @Test void auditFailureIsNeverSwallowed() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
        assertThrows(IllegalStateException.class, () -> service.create(create(READ), "audit-failure"));
    }

    /** 十进制 ID 字符串和整数 CAS 在真实 Jackson 入口中分开校验。 */
    @Test void strictWireTypesRejectNumericIdsAndStringOrFloatingRevisions() throws Exception {
        String valid = "{\"actor_tenant_id\":\"7\",\"target_tenant_id\":\"8\",\"action\":\"tenant.agent_deployments.read\",\"expected_relation_revision\":3}";
        assertEquals(create(READ), json.readValue(valid, Create.class));
        for (String invalid : List.of(valid.replace("\"7\"", "7"), valid.replace(":3}", ":\"3\"}"), valid.replace(":3}", ":3.0}")))
            assertThrows(JsonProcessingException.class, () -> json.readValue(invalid, Create.class));
        assertEquals(new Revoke(10L, 2L), json.readValue("{\"grant_id\":\"10\",\"expected_revision\":2}", Revoke.class));
        assertThrows(JsonProcessingException.class, () -> json.readValue("{\"grant_id\":10,\"expected_revision\":2}", Revoke.class));
    }

    /** 真实总部和当前直属关系的标准创建命令。 */
    private Create create(String action) { return new Create(7L, 8L, action, 3L); }
    /** 固定安全许可行，生命周期由各测试显式指定。 */
    private InstitutionDeploymentManagementMapper.GrantRow grant(String status, long revision) {
        return new InstitutionDeploymentManagementMapper.GrantRow(10, 7, 8, READ, status, revision, 80, 3);
    }
    /** 在内存夹具中复用持久回执的三元唯一键语义。 */
    private String historyKey(String actor, String operation, String key) { return actor + ":" + operation + ":" + key; }
}
