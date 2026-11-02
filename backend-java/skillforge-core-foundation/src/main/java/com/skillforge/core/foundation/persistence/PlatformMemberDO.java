package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 平台运营后台账号实体，对应数据库表 {@code platform_members}。
 *
 * <p>与租户账号完全隔离：无 {@code tenant_id}，不参与租户 RLS；
 * 平台管理员通过独立登录接口认证，角色固定为 platform_admin。
 */
@Data
@TableName(value = "nw_platform_members", autoResultMap = true)
public class PlatformMemberDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 登录用户名，全局唯一。 */
    private String username;

    /** 密码哈希（BCrypt）。 */
    private String passwordHash;

    /** 邮箱，可空。 */
    private String email;

    /** 显示名称，可空。 */
    private String displayName;

    /** 账号状态：active / disabled。 */
    private String status;

    /** 最近登录时间，可空。 */
    private Instant lastLoginAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
