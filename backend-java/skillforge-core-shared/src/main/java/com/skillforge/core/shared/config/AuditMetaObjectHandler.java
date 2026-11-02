package com.skillforge.core.shared.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Instant;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * 审计字段自动填充处理器。
 *
 * <p>对标注了 {@code @TableField(fill = ...)} 的 createdAt / updatedAt 字段，
 * 在插入与更新时自动写入当前时间，避免业务代码重复维护时间戳。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /** 创建时间字段名。 */
    private static final String CREATED_AT = "createdAt";

    /** 更新时间字段名。 */
    private static final String UPDATED_AT = "updatedAt";

    /**
     * 插入时自动填充创建时间与更新时间。
     *
     * @param metaObject MyBatis 元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        this.strictInsertFill(metaObject, CREATED_AT, Instant.class, now);
        this.strictInsertFill(metaObject, UPDATED_AT, Instant.class, now);
    }

    /**
     * 更新时自动填充更新时间。
     *
     * @param metaObject MyBatis 元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, UPDATED_AT, Instant.class, Instant.now());
    }
}
