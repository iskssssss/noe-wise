package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Data;

/**
 * 租户成员实体，对应数据库表 {@code tenant_members}。
 *
 * <p>表示某个租户下的 B 端后台用户，采用应用内密码登录，并通过角色与权限数组
 * 实现 RBAC。
 */
@Data
@TableName(value = "nw_tenant_members", autoResultMap = true)
public class TenantMemberDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 登录用户名，租户内唯一。 */
    private String username;

    /** 手机号（AES 加密存储），可能为 null。 */
    private String phoneEncrypted;

    /** 角色：admin / developer / operator 等。 */
    private String role;

    /** 扩展权限数组，JSONB 存储；通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<Object> permissions;

    /** 成员状态：active / disabled 等。 */
    private String status;

    /** 最后登录时间。 */
    private Instant lastLoginAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 密码哈希（BCrypt），可能为 null（外部身份时）。 */
    private String passwordHash;

    /** 邮箱。 */
    private String email;

    /** 外部身份标识（IdP sub），可能为 null。 */
    private String externalSub;
}
