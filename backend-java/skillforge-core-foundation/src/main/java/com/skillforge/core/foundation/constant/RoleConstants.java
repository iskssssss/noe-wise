package com.skillforge.core.foundation.constant;

/**
 * 用户角色常量。
 *
 * <p>与 {@code tenant_members.role} 列的取值保持一致，供鉴权注解
 * 与业务判断引用，禁止在代码中直接书写角色字符串。
 */
public final class RoleConstants {

    /** 平台管理员：拥有全部租户的业务角色权限。 */
    public static final String PLATFORM_ADMIN = "platform_admin";

    /** 租户管理员。 */
    public static final String ADMIN = "admin";

    /** 审计员：仅可读取审计与运营数据。 */
    public static final String AUDITOR = "auditor";

    /** 开发者：可维护工作流、知识库与构建器产物。 */
    public static final String DEVELOPER = "developer";

    private RoleConstants() {
    }
}
