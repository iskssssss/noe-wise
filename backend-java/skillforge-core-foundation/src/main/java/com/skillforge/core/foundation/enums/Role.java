package com.skillforge.core.foundation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 用户角色枚举。
 *
 * <p>与 {@code tenant_members.role} 列取值一一对应，JSON 序列化输出
 * code 值，反序列化按 code 匹配，保持线上契约不变。
 */
public enum Role {

    /** 平台管理员。 */
    PLATFORM_ADMIN("platform_admin"),

    /** 租户管理员。 */
    ADMIN("admin"),

    /** 审计员。 */
    AUDITOR("auditor"),

    /** 开发者。 */
    DEVELOPER("developer");

    /** 角色字符串值。 */
    private final String code;

    /**
     * 构造角色枚举。
     *
     * @param code 角色字符串值
     */
    Role(String code) {
        this.code = code;
    }

    /**
     * 获取角色字符串值。
     *
     * @return 角色字符串值
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 按字符串值解析角色，未知值返回 null。
     *
     * @param code 角色字符串值
     * @return 角色枚举；未匹配时返回 null
     */
    @JsonCreator
    public static Role fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (Role role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        return null;
    }
}
