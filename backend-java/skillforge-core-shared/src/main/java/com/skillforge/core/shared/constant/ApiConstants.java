package com.skillforge.core.shared.constant;

/**
 * 接口通用常量。
 *
 * <p>汇总各 REST 接口响应的通用字段名与默认分页值，业务常量按领域
 * 分散在各自的常量类中，避免魔法字符串散落。
 */
public final class ApiConstants {

    /** 响应状态字段名。 */
    public static final String STATUS = "status";

    /** 响应主键 ID 字段名。 */
    public static final String ID = "id";

    /** 列表响应数据项字段名。 */
    public static final String ITEMS = "items";

    /** 默认分页页号，从 1 开始。 */
    public static final int DEFAULT_PAGE = 1;

    /** 默认分页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 分页大小上限。 */
    public static final int MAX_PAGE_SIZE = 200;

    private ApiConstants() {
    }
}
