package com.skillforge.core.foundation.constant;

/**
 * 审计日志常量。
 *
 * <p>定义审计操作类型与资源类型取值，供 {@code AuditService.record} 调用方
 * 与审计查询接口统一引用。
 */
public final class AuditConstants {

    /** 平台开通机构操作。 */
    public static final String ACTION_TENANT_PROVISION = "admin.tenant.provision";

    /** 平台更新机构信息操作。 */
    public static final String ACTION_TENANT_UPDATE = "admin.tenant.update";

    /** 机构停用操作。 */
    public static final String ACTION_TENANT_SUSPEND = "admin.tenant.suspend";

    /** 机构启用操作。 */
    public static final String ACTION_TENANT_ACTIVATE = "admin.tenant.activate";

    /** 平台新增机构成员操作。 */
    public static final String ACTION_TENANT_MEMBER_CREATE = "admin.tenant_member.create";

    /** 平台更新机构成员操作。 */
    public static final String ACTION_TENANT_MEMBER_UPDATE = "admin.tenant_member.update";

    /** 平台重置机构成员密码操作。 */
    public static final String ACTION_TENANT_MEMBER_RESET_PASSWORD = "admin.tenant_member.reset_password";

    /** 用户登录操作。 */
    public static final String ACTION_AUTH_LOGIN = "auth.login";

    /** 刷新令牌操作。 */
    public static final String ACTION_AUTH_REFRESH = "auth.refresh";

    /** 用户自助修改密码操作。 */
    public static final String ACTION_AUTH_CHANGE_PASSWORD = "auth.password_change";

    /** 租户资源类型。 */
    public static final String RESOURCE_TENANT = "tenant";

    /** 租户成员资源类型。 */
    public static final String RESOURCE_TENANT_MEMBER = "tenant_member";

    /** 审计日志列表默认返回条数。 */
    public static final int DEFAULT_LIMIT = 50;

    private AuditConstants() {
    }
}
