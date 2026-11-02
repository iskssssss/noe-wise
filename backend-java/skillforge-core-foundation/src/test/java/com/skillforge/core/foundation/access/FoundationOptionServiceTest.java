package com.skillforge.core.foundation.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.security.SecurityPermissions;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** 候选查询的动作授权、精确身份解析和短事务范围，数据库过滤另由真实 XML 测试验证。 */
class FoundationOptionServiceTest {
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final FoundationOptionMapper mapper = mock(FoundationOptionMapper.class);
    private final FoundationOptionService service = new FoundationOptionService(access, mapper);
    private final FoundationAccess.Subject actor = new FoundationAccess.Subject(false, "7", "11");
    private final FoundationAccess.DataScope range = new FoundationAccess.DataScope(false, "11", Set.of("31"));

    @BeforeEach void subject() {
        when(access.currentSubject()).thenReturn(actor);
        when(access.dataScope(actor)).thenReturn(range);
    }
    @AfterEach void clear() { TenantContext.clear(); }

    /** 安全管理者无需组织读取权限；已选身份保持精度并沿用当前数据范围。 */
    @Test void selectedMembersKeepExactIdentityAndDoNotRequireOrganizationRead() {
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(SecurityPermissions.REVOKE), 1));
        var securityRange = new FoundationAccess.DataScope(true, actor.memberId(), Set.of());
        var option = new FoundationOptionService.Option("9223372036854775806", "成员", "member", "active");
        when(mapper.members(eq("7"), eq(securityRange), eq(false), any())).thenReturn(List.of(option));
        TenantContext.setTenantId("99");
        var result = service.members("security", "", List.of(option.id()), 1);
        assertThat(result.items()).containsExactly(option);
        assertThat(result.hasMore()).isFalse();
        verify(access, never()).require(AccessPermissions.READ);
        var query = ArgumentCaptor.forClass(FoundationOptionService.Query.class);
        verify(mapper).members(eq("7"), eq(securityRange), eq(false), query.capture());
        assertThat(query.getValue().ids()).containsExactly(option.id());
        assertThat(TenantContext.getTenantId()).isEqualTo("99");
    }

    /** 无安全管理动作时，不执行候选 SQL；拒绝不能伪装为可选空目录。 */
    @Test void missingSecurityActionRejectsBeforeDirectoryRead() {
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(AccessPermissions.READ), 1));
        assertThatThrownBy(() -> service.members("security", "", null, 1)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    /** 公告目录以发布权限鉴权，组织读取权限不能替代发布动作。 */
    @Test void publisherPermissionIsRequiredBeforeRecipientQueries() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无发布权限")).when(access).require("foundation.messaging.publish");
        assertThatThrownBy(() -> service.members("messaging", "", null, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.recipientGroups("role", "", null, 1)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    /** 平台发送与真实收件目录一样覆盖平台账号域，不套用机构部门范围。 */
    @Test void platformPublisherDirectoryUsesItsEntireRealm() {
        var platform = new FoundationAccess.Subject(true, null, "21");
        var platformRange = new FoundationAccess.DataScope(true, "21", Set.of());
        when(access.currentSubject()).thenReturn(platform);
        when(mapper.members(eq("platform"), eq(platformRange), eq(true), any())).thenReturn(List.of());
        when(mapper.recipientGroups(eq("platform"), eq("role"), eq(platformRange), any())).thenReturn(List.of());
        service.members("messaging", "", null, 1);
        service.recipientGroups("role", "", null, 1);
        verify(mapper).members(eq("platform"), eq(platformRange), eq(true), any());
        verify(mapper).recipientGroups(eq("platform"), eq("role"), eq(platformRange), any());
        verify(access, never()).currentPlatformAuthorization();
    }

    /** 已选解析最多100项但不受普通50项候选页截断。 */
    @Test void selectedResolutionDoesNotTruncateToSearchPageSize() {
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(SecurityPermissions.REVOKE), 1));
        var selected = IntStream.rangeClosed(1, 75).mapToObj(Integer::toString).toList();
        var rows = selected.stream().map(id -> new FoundationOptionService.Option(id, "成员", "member-" + id, "active")).toList();
        when(mapper.members(eq("7"), any(), eq(false), any())).thenReturn(rows);
        assertThat(service.members("security", "", selected, 1).items()).hasSize(75);
    }

    /** 所有者限定活跃且在当前部门范围内，不借组织目录扩大授权。 */
    @Test void resourceOwnerCandidatesUseActualManageTargetAndActiveDataScope() {
        var selected = new FoundationOptionService.Option("11", "资源所有者", "owner", "active");
        when(mapper.members(eq("7"), eq(range), eq(true), any())).thenReturn(List.of(selected));
        assertThat(service.resourceMembers("resource-owner", "agent", "91", "", List.of("11"), 1).items()).containsExactly(selected);
        verify(access).require(AccessPermissions.RESOURCE_MANAGE);
        verify(access).requireResource("agent", "91", "manage");
        verify(access, never()).require(AccessPermissions.READ);
    }

    /** 共享服务允许同机构停用成员，候选和已选解析保留同样范围。 */
    @Test void resourceGranteeCandidatesIncludeSameRealmDisabledMembers() {
        var tenantRange = new FoundationAccess.DataScope(true, actor.memberId(), Set.of());
        var disabled = new FoundationOptionService.Option("12", "已停用成员", "disabled", "disabled");
        when(mapper.members(eq("7"), eq(tenantRange), eq(false), any())).thenReturn(List.of(disabled));
        assertThat(service.resourceMembers("resource-grantee", "agent", "91", "", List.of("12"), 1).items()).containsExactly(disabled);
        verify(access).requireResource("agent", "91", "manage");
        verify(access, never()).dataScope(actor);
    }

    /** 不能管理目标或属于平台账号时，资源成员目录在 SQL 前拒绝。 */
    @Test void resourceMemberDirectoryRejectsUnauthorizedTargetAndPlatform() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无资源管理权")).when(access).requireResource("agent", "91", "manage");
        assertThatThrownBy(() -> service.resourceMembers("resource-owner", "agent", "91", "", null, 1)).isInstanceOf(ApiException.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "21"));
        assertThatThrownBy(() -> service.resourceMembers("resource-grantee", "agent", "92", "", null, 1)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    /** 资源过滤必须传入 SQL，探测的第51项只形成后续页标志。 */
    @Test void resourceManageFilterIsAppliedBeforePagination() {
        var filter = new FoundationAccess.ResourceFilter(false, List.of("91", "92"));
        when(access.resourceFilter("agent", "manage")).thenReturn(filter);
        var rows = IntStream.range(0, 51).mapToObj(i -> new FoundationOptionService.Option(Integer.toString(i + 1), "资源", "agent-" + i, "active")).toList();
        when(mapper.resources(eq("7"), eq("agent"), eq(filter), any())).thenReturn(rows);
        var result = service.resources("agent", "资源", null, 2);
        assertThat(result.items()).hasSize(50);
        assertThat(result.hasMore()).isTrue();
        var query = ArgumentCaptor.forClass(FoundationOptionService.Query.class);
        verify(mapper).resources(eq("7"), eq("agent"), eq(filter), query.capture());
        assertThat(query.getValue().offset()).isEqualTo(50);
        assertThat(query.getValue().limit()).isEqualTo(51);
    }

    /** 非法类型、非法身份、跨账号域和机构访问平台目录均在 SQL 前拒绝。 */
    @Test void rejectsMalformedAndCrossRealmInputs() {
        assertThatThrownBy(() -> service.requireHost(true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.tenants("", null, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resources("unknown", "", null, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resources("agent", "", List.of("9007199254740993.0"), 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resources("agent", "", null, 0)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
}
