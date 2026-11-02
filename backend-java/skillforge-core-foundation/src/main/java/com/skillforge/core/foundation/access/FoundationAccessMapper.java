package com.skillforge.core.foundation.access;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只通过 XML 读取当前账号、角色、部门及对象授权，不读取 JWT 的角色快照。 */
@Mapper
public interface FoundationAccessMapper {
    /** 按同租户会话业务键找到真实宿主主键，未知/跨租户均返回 null。 */
    Long sessionPrimaryId(@Param("scope") String scope, @Param("sessionId") String sessionId);
    /** 会话不共享，仅按所有者和部门范围过滤真实会话业务键。 */
    List<String> visibleSessionIds(@Param("scope") String scope, @Param("memberId") long memberId, @Param("filter") FoundationAccess.DataScope filter);
    /** 角色、部门及资源授权变化形成的体系版本。 */
    long scopeVersion(@Param("scope") String scope);
    /** 一次查询当前资源所有权与共享联合范围内 ID，供列表 SQL 在分页前过滤。 */
    List<String> authorizedResourceIds(@Param("scope") String scope, @Param("kind") String kind,
            @Param("action") String action, @Param("memberId") long memberId,
            @Param("departmentId") Long departmentId, @Param("filter") FoundationAccess.DataScope filter);
    /** 返回当前真实账号及其资料；不存在时返回 null。 */
    AccessRows.Member member(@Param("scope") String scope, @Param("memberId") long memberId);
    /** 读取已经分配且启用的当前角色。 */
    List<AccessRows.Role> memberRoles(@Param("scope") String scope, @Param("memberId") long memberId);
    /** 读取当前角色中的明确动作权限。 */
    List<String> memberPermissions(@Param("scope") String scope, @Param("memberId") long memberId);
    /** 读取指定部门范围的所有有效部门 ID。 */
    List<Long> specifiedDepartments(@Param("scope") String scope, @Param("memberId") long memberId);
    /** 停用部门不继续扩张本部门范围。 */
    boolean enabledDepartment(@Param("scope") String scope, @Param("departmentId") long departmentId);
    /** 读取主部门及其启用的后代，用于范围联合。 */
    List<Long> descendantDepartments(@Param("scope") String scope, @Param("departmentId") long departmentId);
    /** 校验资源在同一租户真实业务表中存在；不接受公共资源隐式变成私有资源。 */
    boolean resourceExists(@Param("tenantId") long tenantId, @Param("kind") String kind, @Param("resourceId") long resourceId);
    /** 返回资源所有权，未登记时返回 null。 */
    AccessRows.Resource resource(@Param("scope") String scope, @Param("kind") String kind, @Param("resourceId") String resourceId);
    /** 读取成员本人、当前角色或所属部门得到的共享动作。 */
    boolean hasResourceGrant(@Param("scope") String scope, @Param("memberId") long memberId,
            @Param("departmentId") Long departmentId, @Param("kind") String kind,
            @Param("resourceId") String resourceId, @Param("action") String action);
}
