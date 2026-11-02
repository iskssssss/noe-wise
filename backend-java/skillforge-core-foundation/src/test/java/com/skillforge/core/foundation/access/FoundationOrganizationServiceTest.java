package com.skillforge.core.foundation.access;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.application.AuditService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 管理拒绝路径必须在真实写入之前结束，单测覆盖保护规则而不冒充数据库并发证据。 */
class FoundationOrganizationServiceTest {
    private FoundationAccess access;
    private FoundationOrganizationMapper mapper;
    private FoundationAccessMapper authorization;
    private FoundationOrganizationService service;
    private final FoundationAccess.Subject actor=new FoundationAccess.Subject(false,"7","11");
    /** 使用明确主体、版本与数据范围建立合法管理基线。 */
    @BeforeEach void setup(){
        access=mock(FoundationAccess.class); mapper=mock(FoundationOrganizationMapper.class); authorization=mock(FoundationAccessMapper.class);
        when(access.currentSubject()).thenReturn(actor); when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of("tenant_admin"),List.of(AccessPermissions.READ,AccessPermissions.ORGANIZATION_MANAGE,AccessPermissions.ROLE_MANAGE),3));
        when(access.dataScope(actor)).thenReturn(new FoundationAccess.DataScope(true,"11",Set.of())); when(mapper.lockScope("7")).thenReturn("7");
        when(mapper.rolePermissionRows("7")).thenReturn(List.of()); when(mapper.roleDepartmentRows("7")).thenReturn(List.of());
        AccessRows.Member member=new AccessRows.Member(); member.setId(11L); member.setStatus("active"); member.setDisplayName("管理员"); member.setLockVersion(1); when(authorization.member("7",11L)).thenReturn(member);
        AccessRows.Role administrator=new AccessRows.Role(); administrator.setId(21L); administrator.setCode("tenant_admin"); administrator.setEnabled(true); administrator.setAdministrator(true); administrator.setBuiltIn(true);
        when(authorization.memberRoles("7",11L)).thenReturn(List.of(administrator));
        service=new FoundationOrganizationService(access,mapper,authorization,new FoundationPermissionCatalogue(List.of(new AccessPermissions())),mock(PasswordEncoder.class),mock(AuditService.class),List.of(),mock(FeatureGate.class),mock(ResourceQuota.class));
    }
    @AfterEach void clearTenant(){TenantContext.clear();}
    /** 最后管理员停用不能产生资料或账号副作用。 */
    @Test void protectsLastActiveAdministratorBeforeAnyWrite(){
        assertThatThrownBy(()->service.updateMember(new AccessCommands.MemberUpdate("11","管理员",null,null,null,"disabled",1))).isInstanceOf(ApiException.class).hasMessageContaining("至少一个");
        verify(mapper,never()).updateProfile(anyString(),any()); verify(mapper,never()).updateMemberAccount(anyString(),any());
    }
    /** 最后管理员角色清空也不得先删关系。 */
    @Test void refusesRemovingLastAdministratorRoles(){
        assertThatThrownBy(()->service.assignRoles(new AccessCommands.MemberRoles("11",List.of(),1))).isInstanceOf(ApiException.class);
        verify(mapper,never()).deleteMemberRoles(anyString(),anyLong());
    }
    /** 部门树循环在事务锁内校验，拒绝前不发更新。 */
    @Test void refusesDepartmentCycle(){
        AccessRows.Department parent=new AccessRows.Department();parent.setId(31L);parent.setParentId(32L);parent.setEnabled(true);
        AccessRows.Department child=new AccessRows.Department();child.setId(32L);child.setParentId(31L);child.setEnabled(true);
        when(mapper.department("7",31L)).thenReturn(parent);when(mapper.department("7",32L)).thenReturn(child);
        assertThatThrownBy(()->service.updateDepartment(new AccessCommands.DepartmentUpdate("31","名称","32",true,1))).isInstanceOf(ApiException.class).hasMessageContaining("循环");
        verify(mapper,never()).updateDepartment(anyString(),any(),anyLong());
    }
    /** 未注册权限和操作者缺失权限均不允许写入新角色。 */
    @Test void refusesPrivilegeEscalation(){
        assertThatThrownBy(()->service.createRole(new AccessCommands.RoleCreate("new_role","名称","self",List.of("foundation.unregistered.manage"),List.of()))).isInstanceOf(ApiException.class);
        verify(mapper,never()).insertRole(anyString(),any());
    }
    /** 缺失布尔值不能默认停用对象。 */
    @Test void rejectsMissingEnabled(){
        AccessRows.Position position=new AccessRows.Position();position.setId(41L);when(mapper.position("7",41L)).thenReturn(position);
        assertThatThrownBy(()->service.updatePosition(new AccessCommands.PositionUpdate("41","名称",null,1))).isInstanceOf(ApiException.class).hasMessageContaining("enabled");
        verify(mapper,never()).updatePosition(anyString(),any(),anyLong());
    }
    /** 外部清理必须使用宿主真实绑定租户，不能通过显式参数跨机构清理。 */
    @Test void trustedResourceCleanupRejectsDifferentTenantBeforeDelete(){
        TenantContext.setTenantId("7");
        assertThatThrownBy(()->service.forgetResourceForTenant("8","agent","91")).isInstanceOf(ApiException.class);
        verify(mapper,never()).deleteResource(anyString(),anyString(),anyString());
        service.forgetResourceForTenant("7","agent","91"); verify(mapper).deleteResource("7","agent","91");
    }
    /** 资料预检不能允许把可见成员移到操作者未授权部门。 */
    @Test void profilePreflightRejectsNewDepartmentOutsideScope(){
        when(access.dataScope(actor)).thenReturn(new FoundationAccess.DataScope(false,"11",Set.of("31")));
        AccessRows.Member target=new AccessRows.Member();target.setId(12L);target.setStatus("active");target.setDepartmentId(31L);target.setLockVersion(1);
        when(authorization.member("7",12L)).thenReturn(target);
        AccessRows.Department outside=new AccessRows.Department();outside.setId(32L);outside.setEnabled(true);when(mapper.department("7",32L)).thenReturn(outside);
        assertThatThrownBy(()->service.validateMemberProfile(new AccessCommands.MemberUpdate("12","成员",null,"32",null,"active",1))).isInstanceOf(ApiException.class).hasMessageContaining("范围");
        verify(mapper,never()).updateProfile(anyString(),any());
    }
}
