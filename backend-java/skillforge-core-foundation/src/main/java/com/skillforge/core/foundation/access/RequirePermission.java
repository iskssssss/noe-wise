package com.skillforge.core.foundation.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** HTTP 入口声明的稳定权限；真正的业务写入仍由所属服务检查。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /** 必须具备的已注册权限编码。 */
    String value();
}
