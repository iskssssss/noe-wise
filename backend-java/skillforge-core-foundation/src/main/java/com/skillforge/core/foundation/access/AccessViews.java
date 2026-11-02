package com.skillforge.core.foundation.access;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** access API 查询投影，所有宿主 ID 均以十进制字符串传输。 */
public final class AccessViews {
    private AccessViews() { }
    /** @param id 部门 ID @param code 编码 @param name 名称 @param parentId 上级，可空 @param enabled 启用状态 @param lockVersion 编辑版本 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Department(String id, String code, String name, String parentId, boolean enabled, long lockVersion) { }
    /** @param id 岗位 ID @param code 编码 @param name 名称 @param enabled 启用状态 @param lockVersion 编辑版本 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Position(String id, String code, String name, boolean enabled, long lockVersion) { }
    /**
     * 成员列表不包含密码、令牌或 MFA 材料。
     * @param id 账号 ID @param username 登录名 @param displayName 显示名称 @param email 邮箱 @param status 状态
     * @param departmentId 部门 ID @param positionId 岗位 ID @param roleIds 当前角色 ID @param roles 当前角色编码
     * @param lockVersion 编辑版本 @param authorizationVersion 授权版本 @param createdAt 创建时间
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Member(String id, String username, String displayName, String email, String status,
            String departmentId, String positionId, List<String> roleIds, List<String> roles,
            long lockVersion, long authorizationVersion, Instant createdAt) { }
    /**
     * 角色完整查询投影。
     * @param id ID @param code 编码 @param name 名称 @param dataScope 数据范围 @param builtIn 内置标记
     * @param administrator 管理员标记 @param enabled 启用 @param permissions 权限 @param departmentIds 指定范围 @param lockVersion 版本
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Role(String id, String code, String name, String dataScope, boolean builtIn,
            boolean administrator, boolean enabled, List<String> permissions, List<String> departmentIds, long lockVersion) { }
    /** @param id 共享条目 ID @param granteeType 目标类型 @param granteeId 目标 ID @param action 共享动作 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Grant(String id, String granteeType, String granteeId, String action) { }
    /** @param kind 资源类型 @param resourceId ID @param ownerMemberId 所有者 @param departmentId 部门 @param grants 共享条目 @param lockVersion 版本 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Resource(String kind, String resourceId, String ownerMemberId, String departmentId, List<Grant> grants, long lockVersion) { }
    /** @param items 当前页成员 @param total 总数 @param page 页码 @param pageSize 页大小 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemberPage(List<Member> items, long total, int page, int pageSize) { }
}
