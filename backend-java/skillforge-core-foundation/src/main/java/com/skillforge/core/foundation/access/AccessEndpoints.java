package com.skillforge.core.foundation.access;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 共享 access HTTP 协议，宿主子类绑定固定前缀并强制账号体系一致。 */
@RequirePermission(AccessPermissions.READ)
public abstract class AccessEndpoints {
    private final FoundationOrganizationService service;
    private final FoundationAccess access;
    private final boolean platform;
    /** 宿主指定账号体系，不能由客户端传入 tenant/platform 字段。 */
    protected AccessEndpoints(FoundationOrganizationService service,FoundationAccess access,boolean platform) { this.service=service; this.access=access; this.platform=platform; }
    /** 已认证账号必须进入自己体系的接口。 */
    private void host() { if(access.currentSubject().platform()!=platform) throw new ApiException(HttpStatus.FORBIDDEN,"账号体系不匹配"); }
    /** 当前真实角色、权限及版本。 */
    @GetMapping("/snapshot") public ApiResponse<FoundationAccess.Snapshot> snapshot() { host(); return ApiResponses.wrap(access.currentSnapshot()); }
    /** 本体系权限目录。 */
    @GetMapping("/permissions") public ApiResponse<List<FoundationPermission>> permissions() { host(); return ApiResponses.wrap(service.permissions()); }
    /** 部门树。 */
    @GetMapping("/departments") public ApiResponse<List<AccessViews.Department>> departments() { host(); return ApiResponses.wrap(service.departments()); }
    /** 创建部门。 */
    @PostMapping("/department") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<AccessViews.Department> createDepartment(@RequestBody AccessCommands.DepartmentCreate request) { host(); return ApiResponses.wrap(service.createDepartment(request)); }
    /** 完整替换部门。 */
    @PutMapping("/department") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<AccessViews.Department> updateDepartment(@RequestBody AccessCommands.DepartmentUpdate request) { host(); return ApiResponses.wrap(service.updateDepartment(request)); }
    /** 删除无引用部门。 */
    @DeleteMapping("/department") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<Void> deleteDepartment(@RequestBody AccessCommands.Delete request) { host(); service.deleteDepartment(request); return ApiResponses.wrap((Void)null); }
    /** 本体系岗位。 */
    @GetMapping("/positions") public ApiResponse<List<AccessViews.Position>> positions() { host(); return ApiResponses.wrap(service.positions()); }
    /** 创建岗位。 */
    @PostMapping("/position") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<AccessViews.Position> createPosition(@RequestBody AccessCommands.PositionCreate request) { host(); return ApiResponses.wrap(service.createPosition(request)); }
    /** 替换岗位。 */
    @PutMapping("/position") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<AccessViews.Position> updatePosition(@RequestBody AccessCommands.PositionUpdate request) { host(); return ApiResponses.wrap(service.updatePosition(request)); }
    /** 删除无引用岗位。 */
    @DeleteMapping("/position") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<Void> deletePosition(@RequestBody AccessCommands.Delete request) { host(); service.deletePosition(request); return ApiResponses.wrap((Void)null); }
    /** 当前数据范围成员页。 */
    @GetMapping("/members") public ApiResponse<AccessViews.MemberPage> members(@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int pageSize) { host(); return ApiResponses.wrap(service.members(keyword,page,pageSize)); }
    /** 创建密码成员或待邀请成员，密码缺省时必须提供邮箱。 */
    @PostMapping("/member") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<AccessViews.Member> createMember(@RequestBody AccessCommands.MemberCreate request) { host(); return ApiResponses.wrap(service.createMember(request)); }
    /** 完整替换成员资料，最后管理员受保护。 */
    @PutMapping("/member") @RequirePermission(AccessPermissions.ORGANIZATION_MANAGE) public ApiResponse<AccessViews.Member> updateMember(@RequestBody AccessCommands.MemberUpdate request) { host(); return ApiResponses.wrap(service.updateMember(request)); }
    /** 完整替换正式多角色关系。 */
    @PostMapping("/member-roles") @RequirePermission(AccessPermissions.ROLE_MANAGE) public ApiResponse<AccessViews.Member> memberRoles(@RequestBody AccessCommands.MemberRoles request) { host(); return ApiResponses.wrap(service.assignRoles(request)); }
    /** 本体系角色定义。 */
    @GetMapping("/roles") public ApiResponse<List<AccessViews.Role>> roles() { host(); return ApiResponses.wrap(service.roles()); }
    /** 新建自定义角色。 */
    @PostMapping("/role") @RequirePermission(AccessPermissions.ROLE_MANAGE) public ApiResponse<AccessViews.Role> createRole(@RequestBody AccessCommands.RoleCreate request) { host(); return ApiResponses.wrap(service.createRole(request)); }
    /** 替换自定义角色。 */
    @PutMapping("/role") @RequirePermission(AccessPermissions.ROLE_MANAGE) public ApiResponse<AccessViews.Role> updateRole(@RequestBody AccessCommands.RoleUpdate request) { host(); return ApiResponses.wrap(service.updateRole(request)); }
    /** 删除无成员引用的自定义角色。 */
    @DeleteMapping("/role") @RequirePermission(AccessPermissions.ROLE_MANAGE) public ApiResponse<Void> deleteRole(@RequestBody AccessCommands.Delete request) { host(); service.deleteRole(request); return ApiResponses.wrap((Void)null); }
    /** 当前机构资源授权；平台账号不会获机构权限。 */
    @GetMapping("/resource") @RequirePermission(AccessPermissions.RESOURCE_MANAGE) public ApiResponse<AccessViews.Resource> resource(@RequestParam String kind,@RequestParam(name="resource_id") String resourceId) { host(); return ApiResponses.wrap(service.resource(kind,resourceId)); }
    /** 以版本完整替换资源所有权和共享条目。 */
    @PutMapping("/resource-authorization") @RequirePermission(AccessPermissions.RESOURCE_MANAGE) public ApiResponse<AccessViews.Resource> resourceAuthorization(@RequestBody AccessCommands.ResourceAuthorization request) { host(); return ApiResponses.wrap(service.authorizeResource(request)); }
}
