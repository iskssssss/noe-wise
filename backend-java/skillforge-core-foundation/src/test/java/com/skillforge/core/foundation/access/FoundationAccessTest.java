package com.skillforge.core.foundation.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 授权计算的边界测试，覆盖实时撤权、目录体系隔离、范围联合及会话独立授权。 */
class FoundationAccessTest {
    private FoundationAccessMapper mapper;
    private FoundationAccess access;
    private AccessRows.Member member;
    private ReadTransactions transactions;
    private final FoundationAccess.Subject subject=new FoundationAccess.Subject(false,"7","11");
    /** 每例建立明确 active 主体，不使用 JWT role 或任意权限默认放行。 */
    @BeforeEach void setup(){
        mapper=mock(FoundationAccessMapper.class); member=new AccessRows.Member(); member.setId(11L); member.setStatus("active"); member.setDepartmentId(31L); member.setAuthorizationVersion(2);
        when(mapper.member("7",11L)).thenReturn(member); when(mapper.scopeVersion("7")).thenReturn(3L);
        when(mapper.memberRoles(anyString(),anyLong())).thenReturn(List.of()); when(mapper.memberPermissions(anyString(),anyLong())).thenReturn(List.of());
        FoundationPermissionContributor extra=()->List.of(new FoundationPermission("foundation.platform_test.manage","平台专用",FoundationPermission.Audience.PLATFORM),new FoundationPermission("foundation.security.self","个人安全",FoundationPermission.Audience.BOTH),new FoundationPermission("foundation.sessions.read","会话读取",FoundationPermission.Audience.TENANT));
        transactions=new ReadTransactions();
        access=new FoundationAccess(mapper,new FoundationPermissionCatalogue(List.of(new AccessPermissions(),extra)),transactions);
    }
    /** ThreadLocal 夹具不泄漏到其他测试。 */ @AfterEach void clear(){TenantContext.clear();}
    /** 未注册编码和其他账号体系动作，即使存在旧权限行也拒绝。 */
    @Test void filtersUnknownAndPlatformPermissionsAndGrantsSelfOnly(){
        when(mapper.memberPermissions("7",11L)).thenReturn(List.of(AccessPermissions.READ,"foundation.unregistered.manage","foundation.platform_test.manage"));
        var current=access.snapshot(subject);
        assertThat(current.permissions()).containsExactly(AccessPermissions.READ,"foundation.security.self"); assertThat(current.authorizationVersion()).isEqualTo(5L);
        assertThat(access.allowed(subject,"foundation.platform_test.manage")).isFalse();
    }
    /** 停用数据库事实在下一次授权读取立即生效。 */
    @Test void disabledSubjectIsRejectedAndTenantContextIsRestored(){
        TenantContext.setTenantId("99"); assertThat(access.snapshot(subject).permissions()).contains("foundation.security.self");
        member.setStatus("disabled"); assertThatThrownBy(()->access.snapshot(subject)).isInstanceOf(ApiException.class);
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }
    /** 只有 tenant ThreadLocal，没有 HTTP 登录或受信主体时明确返回 401。 */
    @Test void tenantContextWithoutAuthenticatedSubjectIsUnauthorized(){
        TenantContext.setTenantId("7");
        assertThatThrownBy(access::currentSubject).isInstanceOfSatisfying(ApiException.class,
                failure->assertThat(failure.getStatus().value()).isEqualTo(401));
    }
    /** 多角色范围取并集，但本人、指定部门和下级范围不变成全租户。 */
    @Test void combinesDepartmentDescendantsAndSpecifiedScopes(){
        when(mapper.enabledDepartment("7",31L)).thenReturn(true);
        when(mapper.memberRoles("7",11L)).thenReturn(List.of(role("department"),role("descendants"),role("specified")));
        when(mapper.descendantDepartments("7",31L)).thenReturn(List.of(31L,32L)); when(mapper.specifiedDepartments("7",11L)).thenReturn(List.of(35L));
        var range=access.dataScope(subject); assertThat(range.all()).isFalse(); assertThat(range.departmentIds()).containsExactlyInAnyOrder("31","32","35");
        assertThat(range.includes("11",null)).isTrue(); assertThat(range.includes("44","32")).isTrue(); assertThat(range.includes("44","88")).isFalse();
    }
    /** 主部门停用时，本部门角色只能保留本人范围。 */
    @Test void disabledDepartmentDoesNotGrantDepartmentScope(){
        when(mapper.memberRoles("7",11L)).thenReturn(List.of(role("department")));
        assertThat(access.dataScope(subject).departmentIds()).isEmpty();
        assertThat(access.dataScope(subject).includes("11",null)).isTrue();
    }
    /** 资源分享不补齐主体的动作权限，跨租户对象不存在时仍拒绝。 */
    @Test void sharingRequiresActionAndRealSameTenantObject(){
        when(mapper.hasResourceGrant("7",11L,31L,"agent","71","execute")).thenReturn(true);
        assertThat(access.canAccessResource(subject,"agent","71","execute")).isFalse();
        when(mapper.memberPermissions("7",11L)).thenReturn(List.of("foundation.resource.agent.execute"));
        assertThat(access.canAccessResource(subject,"agent","71","execute")).isFalse();
        when(mapper.resourceExists(7L,"agent",71L)).thenReturn(true); assertThat(access.canAccessResource(subject,"agent","71","execute")).isTrue();
        when(mapper.hasResourceGrant("7",11L,31L,"agent","71","execute")).thenReturn(false); assertThat(access.canAccessResource(subject,"agent","71","execute")).isFalse();
    }
    /** Agent共享不能授予会话访问；会话只检查自身所有权或范围。 */
    @Test void sessionNeedsItsOwnOwnershipAndSupportsOpaqueBusinessId(){
        when(mapper.memberPermissions("7",11L)).thenReturn(List.of("foundation.sessions.read")); when(mapper.sessionPrimaryId("7","sess_history")).thenReturn(81L);
        assertThatThrownBy(()->access.requireSession(subject,"sess_history","read")).isInstanceOf(ApiException.class);
        AccessRows.Resource ownership=new AccessRows.Resource(); ownership.setOwnerMemberId(11L); when(mapper.resource("7","session","81")).thenReturn(ownership);
        access.requireSession(subject,"sess_history","read");
    }
    /** 后台绑定完成或异常后不污染下一次任务。 */
    @Test void trustedBackgroundContextIsClearedAfterFailure(){
        TenantContext.setTenantId("99");
        assertThatThrownBy(()->access.withSubject(subject,()->{assertThat(access.currentSubject()).isEqualTo(subject); throw new IllegalStateException("synthetic failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    /** 本类内部调用也须先绑定租户再开短事务，任意业务回调在授权事务结束后执行。 */
    @Test void selfInvokedSnapshotEndsItsTransactionBeforeTrustedBusinessCallback(){
        TenantContext.setTenantId("99");
        doAnswer(call->{
            assertThat(TenantContext.getTenantId()).isEqualTo("7");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return member;
        }).when(mapper).member("7",11L);
        access.withSubject(subject,()->{
            assertThat(transactions.commits).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TenantContext.getTenantId()).isEqualTo("7");
            access.currentSnapshot();
            access.require("foundation.security.self");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        });
        assertThat(transactions.commits).isEqualTo(3);
        assertThat(transactions.scopes).containsExactly("7","7","7");
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
        assertThatThrownBy(()->access.require(subject,"foundation.resource.agent.execute")).isInstanceOf(ApiException.class);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    /** 资源详情、资源列表与会话列表每次只加载一次当前成员/角色，不建立跨调用授权缓存。 */
    @Test void eachObjectAuthorizationSharesOnlyItsOwnCurrentMemberAndRoles(){
        when(mapper.memberPermissions("7",11L)).thenReturn(List.of("foundation.resource.agent.read","foundation.sessions.read"));
        when(mapper.resourceExists(7L,"agent",71L)).thenReturn(true);
        when(mapper.hasResourceGrant("7",11L,31L,"agent","71","read")).thenReturn(true);
        assertThat(access.canAccessResource(subject,"agent","71","read")).isTrue();
        verifyCurrentIdentityReadOnce();
        clearInvocations(mapper);
        access.resourceFilter(subject,"agent","read");
        verifyCurrentIdentityReadOnce();
        clearInvocations(mapper);
        access.visibleSessionIds(subject,"read");
        verifyCurrentIdentityReadOnce();
        clearInvocations(mapper);
        when(mapper.memberPermissions("7",11L)).thenReturn(List.of());
        assertThat(access.canAccessResource(subject,"agent","71","read")).isFalse();
        verifyCurrentIdentityReadOnce();
        assertThat(transactions.commits).isEqualTo(4);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void verifyCurrentIdentityReadOnce(){
        verify(mapper,times(1)).member("7",11L);
        verify(mapper,times(1)).memberRoles("7",11L);
    }

    /** 平台管理一次投影共用当前成员与角色，包含当前版本和范围而不依赖JWT角色。 */
    @Test void platformManagementProjectionLoadsIdentityAndRolesOnce(){
        var platform=new FoundationAccess.Subject(true,null,"11");
        when(mapper.member("platform",11L)).thenReturn(member);
        when(mapper.scopeVersion("platform")).thenReturn(5L);
        when(mapper.memberRoles("platform",11L)).thenReturn(List.of(role("self")));
        when(mapper.memberPermissions("platform",11L)).thenReturn(List.of("foundation.platform_test.manage"));
        TenantContext.setTenantId("99");
        access.withSubject(platform,()->{
            clearInvocations(mapper);
            var current=access.currentPlatformAuthorization();
            assertThat(current.subject()).isEqualTo(platform);
            assertThat(current.snapshot().permissions()).contains("foundation.platform_test.manage");
            assertThat(current.snapshot().authorizationVersion()).isEqualTo(7L);
            assertThat(current.dataScope().all()).isFalse();
            assertThat(current.dataScope().memberId()).isEqualTo("11");
            verify(mapper,times(1)).member("platform",11L);
            verify(mapper,times(1)).memberRoles("platform",11L);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        });
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    /** 使用真实 Spring 事务同步验证事务边界；不模拟数据库或提交结果。 */
    private static final class ReadTransactions extends AbstractPlatformTransactionManager {
        final java.util.ArrayList<String> scopes=new java.util.ArrayList<>();
        int commits;
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){
            assertThat(definition.isReadOnly()).isTrue();
            scopes.add(TenantContext.getTenantId());
        }
        @Override protected void doCommit(DefaultTransactionStatus status){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){}
    }
    /** 构造明确的非管理员范围角色。 */
    private AccessRows.Role role(String scope){AccessRows.Role role=new AccessRows.Role();role.setCode(scope);role.setDataScope(scope);role.setEnabled(true);return role;}
}
