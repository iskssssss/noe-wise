package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * C 端用户实体，对应数据库表 {@code end_users}。
 *
 * <p>租户作用域内的逻辑用户。运行时入口必须显式携带其主键。
 */
@Data
@TableName("nw_end_users")
public class EndUserDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 外部渠道/集成标识；仅代理用户使用。 */
    private String externalIntegrationId;

    /** 外部渠道中的稳定用户引用；与 integration 共同构成唯一身份。 */
    private String externalUserRef;

    /** 显示名称，可空。 */
    private String displayName;

    /** 手机号加密密文，可空。 */
    private String phoneEncrypted;

    /** 用户状态：active / deleted。 */
    private String status;

    /** 首次出现时间。 */
    private Instant firstSeenAt;

    /** 最近活跃时间。 */
    private Instant lastActiveAt;

    /** 创建时间。 */
    private Instant createdAt;
}
