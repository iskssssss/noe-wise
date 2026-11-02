package com.skillforge.core.foundation.access;

import java.util.List;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 组织与权限管理的完整替换命令；身份和租户由可信上下文提供。 */
public final class AccessCommands {
    private AccessCommands() { }
    /**
     * 创建部门；无父部门表示根。
     * @param code 稳定编码 @param name 名称 @param parentId 同体系上级 ID
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DepartmentCreate(String code, String name, String parentId) { }
    /**
     * 完整替换部门可编辑字段，编码不可变。
     * @param id 部门 ID @param name 名称 @param parentId 上级，可空 @param enabled 是否启用 @param lockVersion 预期编辑版本
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DepartmentUpdate(String id, String name, String parentId, Boolean enabled, long lockVersion) { }
    /** @param code 稳定编码 @param name 名称 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PositionCreate(String code, String name) { }
    /** @param id 岗位 ID @param name 名称 @param enabled 是否启用 @param lockVersion 预期版本 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PositionUpdate(String id, String name, Boolean enabled, long lockVersion) { }
    /** @param id 要删除的资源 ID @param lockVersion 预期编辑版本 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Delete(String id, long lockVersion) { }
    /**
     * 创建真实密码账号与正式角色，邀请路径可由 security 单独创建并调用 bootstrapMember。
     * @param username 登录名 @param displayName 显示名称 @param email 邮箱，可空 @param password 初始密码
     * @param departmentId 主部门，可空 @param positionId 主岗位，可空 @param roleIds 至少一个同体系角色 ID
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemberCreate(String username, String displayName, String email, String password,
            String departmentId, String positionId, List<String> roleIds) { }
    /**
     * 完整替换成员资料与状态，不通过此接口修改密码或角色。
     * @param id 成员 ID @param displayName 显示名称 @param email 邮箱，可空 @param departmentId 主部门，可空
     * @param positionId 主岗位，可空 @param status active/disabled @param lockVersion 预期资料版本
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemberUpdate(String id, String displayName, String email, String departmentId,
            String positionId, String status, long lockVersion) { }
    /**
     * 完整替换多角色关系，空集合明确表示撤销全部角色。
     * @param memberId 成员 ID @param roleIds 同体系角色 ID 集合 @param lockVersion 预期资料版本
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemberRoles(String memberId, List<String> roleIds, long lockVersion) { }
    /**
     * 新建非内置角色。
     * @param code 稳定编码 @param name 名称 @param dataScope self/department/descendants/specified/all
     * @param permissions 已注册且操作者拥有的编码 @param departmentIds 指定范围部门，其余模式必须为空
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoleCreate(String code, String name, String dataScope, List<String> permissions, List<String> departmentIds) { }
    /**
     * 完整替换自定义角色，内置角色不允许变更。
     * @param id 角色 ID @param name 名称 @param dataScope 数据范围 @param permissions 权限编码
     * @param departmentIds 指定范围部门 @param enabled 是否启用 @param lockVersion 预期版本
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoleUpdate(String id, String name, String dataScope, List<String> permissions,
            List<String> departmentIds, Boolean enabled, long lockVersion) { }
    /**
     * 明确资源共享目标。
     * @param granteeType member/role/department @param granteeId 同租户目标 ID @param action read/execute/manage
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResourceGrant(String granteeType, String granteeId, String action) { }
    /**
     * 完整替换资源授权；新登记 lockVersion 为 0，后续更新使用当前版本。
     * @param kind agent/workflow/knowledge @param resourceId 宿主资源 ID @param ownerMemberId 同租户所有者
     * @param departmentId 同租户部门，可空 @param grants 共享条目 @param lockVersion 预期版本
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResourceAuthorization(String kind, String resourceId, String ownerMemberId,
            String departmentId, List<ResourceGrant> grants, long lockVersion) { }
}
