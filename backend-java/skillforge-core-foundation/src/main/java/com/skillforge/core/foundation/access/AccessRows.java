package com.skillforge.core.foundation.access;

import java.time.Instant;
import lombok.Data;

/** access 关系模型的内部查询行，仅在所属包使用，不作为 API 响应。 */
public final class AccessRows {
    private AccessRows() { }

    /** 当前账号状态和授权版本，来源为真实成员表及基础资料表。 */
    @Data public static class Member {
        /** 账号 ID。 */ private Long id;
        /** 登录名。 */ private String username;
        /** 显示名称。 */ private String displayName;
        /** 邮箱，可空表示未设置。 */ private String email;
        /** 当前账号状态。 */ private String status;
        /** 主部门，可空表示尚未归属部门。 */ private Long departmentId;
        /** 主岗位，可空表示尚未分配。 */ private Long positionId;
        /** 成员授权版本，每次角色、状态或组织归属变更递增。 */ private long authorizationVersion;
        /** 资料编辑版本。 */ private long lockVersion;
        /** 创建时间。 */ private Instant createdAt;
    }

    /** 同一账号体系内的角色定义。 */
    @Data public static class Role {
        /** 角色 ID。 */ private Long id;
        /** 不可变稳定编码。 */ private String code;
        /** 显示名称。 */ private String name;
        /** 数据范围模式。 */ private String dataScope;
        /** 是否内置角色。 */ private boolean builtIn;
        /** 是否受保护的系统管理员角色。 */ private boolean administrator;
        /** 当前是否启用。 */ private boolean enabled;
        /** 并发编辑版本。 */ private long lockVersion;
    }

    /** 部门树节点；同租户父子关系由复合外键保护。 */
    @Data public static class Department {
        /** 部门 ID。 */ private Long id;
        /** 部门编码。 */ private String code;
        /** 名称。 */ private String name;
        /** 上级部门，可空表示根节点。 */ private Long parentId;
        /** 启用状态。 */ private boolean enabled;
        /** 编辑版本。 */ private long lockVersion;
    }

    /** 岗位定义，成员资料引用同一账号体系中的岗位。 */
    @Data public static class Position {
        /** 岗位 ID。 */ private Long id;
        /** 岗位编码。 */ private String code;
        /** 名称。 */ private String name;
        /** 启用状态。 */ private boolean enabled;
        /** 编辑版本。 */ private long lockVersion;
    }

    /** 资源所有权与部门归属，业务对象存在性由所属真实表校验。 */
    @Data public static class Resource {
        /** 资源类型。 */ private String kind;
        /** 十进制宿主资源 ID。 */ private String resourceId;
        /** 所有者账号 ID。 */ private Long ownerMemberId;
        /** 部门归属，可空表示仅本人归属。 */ private Long departmentId;
        /** 授权编辑版本。 */ private long lockVersion;
    }

    /** 资源共享条目，只向同租户的成员、角色或部门授权。 */
    @Data public static class Grant {
        /** 共享条目 ID。 */ private Long id;
        /** 授权目标类型。 */ private String granteeType;
        /** 授权目标 ID。 */ private Long granteeId;
        /** read、execute 或 manage 动作。 */ private String action;
    }

    /** 批量查询成员角色关系，避免成员列表逐行读取。 */
    @Data public static class MemberRole {
        /** 成员 ID。 */ private Long memberId;
        /** 角色 ID。 */ private Long roleId;
        /** 角色编码。 */ private String code;
    }

    /** 批量查询角色的权限明细。 */
    @Data public static class RolePermission {
        /** 角色 ID。 */ private Long roleId;
        /** 权限编码。 */ private String permissionCode;
    }

    /** 批量查询角色的指定部门。 */
    @Data public static class RoleDepartment {
        /** 角色 ID。 */ private Long roleId;
        /** 部门 ID。 */ private Long departmentId;
    }
}
