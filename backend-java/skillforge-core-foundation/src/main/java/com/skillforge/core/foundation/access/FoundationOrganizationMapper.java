package com.skillforge.core.foundation.access;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 组织权限管理 XML 写入；服务持有事务与账号体系锁，所有更新有 CAS 版本。 */
@Mapper
public interface FoundationOrganizationMapper {
    /** 解析当前组织范围内启用收件人，最多读取上限加一用于完整拒绝。 */
    List<AccessRows.Member> recipients(@Param("scope") String scope, @Param("targetType") String targetType,
            @Param("targetIds") List<String> targetIds, @Param("filter") FoundationAccess.DataScope filter);
    /** 锁定账号体系，串行最后管理员、树与角色关系调整。 */
    String lockScope(@Param("scope") String scope);
    /** 创建机构的授权隔离根；目标机构存在性由 FK 强制保护。 */
    void ensureScope(@Param("scope") String scope, @Param("tenantId") Long tenantId);
    /** 提升全域授权版本。 */
    void bumpScope(@Param("scope") String scope);
    /** 当前体系版本参与实时授权版本。 */
    long scopeVersion(@Param("scope") String scope);
    /** 列出本体系部门。 */
    List<AccessRows.Department> departments(@Param("scope") String scope);
    /** 返回单个本体系部门。 */
    AccessRows.Department department(@Param("scope") String scope, @Param("id") long id);
    /** 插入部门。 */
    void insertDepartment(@Param("scope") String scope, @Param("row") AccessRows.Department row, @Param("actorId") long actorId);
    /** CAS 替换部门字段。 */
    int updateDepartment(@Param("scope") String scope, @Param("row") AccessRows.Department row, @Param("actorId") long actorId);
    /** CAS 删除无引用部门。 */
    int deleteDepartment(@Param("scope") String scope, @Param("id") long id, @Param("version") long version);
    /** 列出本体系岗位。 */
    List<AccessRows.Position> positions(@Param("scope") String scope);
    /** 返回单个本体系岗位。 */
    AccessRows.Position position(@Param("scope") String scope, @Param("id") long id);
    /** 插入岗位。 */
    void insertPosition(@Param("scope") String scope, @Param("row") AccessRows.Position row, @Param("actorId") long actorId);
    /** CAS 替换岗位字段。 */
    int updatePosition(@Param("scope") String scope, @Param("row") AccessRows.Position row, @Param("actorId") long actorId);
    /** CAS 删除无引用岗位。 */
    int deletePosition(@Param("scope") String scope, @Param("id") long id, @Param("version") long version);
    /** 查询授权过滤后的成员页；scope.all 由服务从实时角色得出。 */
    List<AccessRows.Member> members(@Param("scope") String scope, @Param("filter") FoundationAccess.DataScope filter,
            @Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);
    /** 查询同一授权过滤条件下成员总数。 */
    long memberCount(@Param("scope") String scope, @Param("filter") FoundationAccess.DataScope filter, @Param("keyword") String keyword);
    /** 批量读取成员角色，不读取密码。 */
    List<AccessRows.MemberRole> memberRoleRows(@Param("scope") String scope, @Param("memberIds") List<Long> memberIds);
    /** 创建对应账号体系的真实密码账号。 */
    void insertMember(@Param("scope") String scope, @Param("row") AccessRows.Member row, @Param("passwordHash") String passwordHash);
    /** 初始化正式组织资料；仅可传入实际账号 ID。 */
    void ensureProfile(@Param("scope") String scope, @Param("memberId") long memberId, @Param("displayName") String displayName);
    /** CAS 替换资料并递增授权版本。 */
    int updateProfile(@Param("scope") String scope, @Param("row") AccessRows.Member row);
    /** 对所属真实成员表写入状态和邮箱，不改变密码。 */
    void updateMemberAccount(@Param("scope") String scope, @Param("row") AccessRows.Member row);
    /** 成员授权关系变化后递增版本。 */
    int bumpMember(@Param("scope") String scope, @Param("memberId") long memberId, @Param("version") long version);
    /** 列出本体系角色。 */
    List<AccessRows.Role> roles(@Param("scope") String scope);
    /** 返回本体系单个角色。 */
    AccessRows.Role role(@Param("scope") String scope, @Param("id") long id);
    /** 按稳定编码返回角色。 */
    AccessRows.Role roleByCode(@Param("scope") String scope, @Param("code") String code);
    /** 插入角色。 */
    void insertRole(@Param("scope") String scope, @Param("row") AccessRows.Role row);
    /** CAS 更新自定义角色。 */
    int updateRole(@Param("scope") String scope, @Param("row") AccessRows.Role row);
    /** CAS 删除无成员引用的自定义角色。 */
    int deleteRole(@Param("scope") String scope, @Param("id") long id, @Param("version") long version);
    /** 读取全域权限明细，组装列表时批量处理。 */
    List<AccessRows.RolePermission> rolePermissionRows(@Param("scope") String scope);
    /** 读取全域指定部门明细。 */
    List<AccessRows.RoleDepartment> roleDepartmentRows(@Param("scope") String scope);
    /** 清除某角色权限。 */
    void deleteRolePermissions(@Param("scope") String scope, @Param("roleId") long roleId);
    /** 插入明确角色动作权限。 */
    void insertRolePermission(@Param("scope") String scope, @Param("roleId") long roleId, @Param("permission") String permission);
    /** 清除某角色指定范围。 */
    void deleteRoleDepartments(@Param("scope") String scope, @Param("roleId") long roleId);
    /** 插入指定范围部门，复合 FK 防止跨机构。 */
    void insertRoleDepartment(@Param("scope") String scope, @Param("roleId") long roleId, @Param("departmentId") long departmentId);
    /** 清除成员多角色关系。 */
    void deleteMemberRoles(@Param("scope") String scope, @Param("memberId") long memberId);
    /** 插入真实成员与同体系角色关系。 */
    void insertMemberRole(@Param("scope") String scope, @Param("memberId") long memberId, @Param("roleId") long roleId);
    /** 计算仍然 active 且拥有管理员角色的不同账号，排除本次被降级成员。 */
    long activeAdministratorsExcept(@Param("scope") String scope, @Param("memberId") long memberId);
    /** 返回该角色影响的成员 ID。 */
    List<Long> roleMembers(@Param("scope") String scope, @Param("roleId") long roleId);
    /** 登记真实资源的明确所有权。 */
    void insertResource(@Param("scope") String scope, @Param("row") AccessRows.Resource row);
    /** CAS 修改资源所有权和部门。 */
    int updateResource(@Param("scope") String scope, @Param("row") AccessRows.Resource row);
    /** 删除旧共享明细。 */
    void deleteResourceGrants(@Param("scope") String scope, @Param("kind") String kind, @Param("resourceId") String resourceId);
    /** 插入同体系共享目标，服务校验不同种类目标存在性。 */
    void insertResourceGrant(@Param("scope") String scope, @Param("kind") String kind, @Param("resourceId") String resourceId,
            @Param("row") AccessRows.Grant row, @Param("actorId") long actorId);
    /** 返回单资源全部共享关系。 */
    List<AccessRows.Grant> resourceGrants(@Param("scope") String scope, @Param("kind") String kind, @Param("resourceId") String resourceId);
    /** 删除已由领域确认删除的资源授权，禁止残留授权复用于后来的对象。 */
    void deleteResource(@Param("scope") String scope, @Param("kind") String kind, @Param("resourceId") String resourceId);
}
