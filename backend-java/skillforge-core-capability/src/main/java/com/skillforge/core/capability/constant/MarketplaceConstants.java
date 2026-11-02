package com.skillforge.core.capability.constant;
/**
 * 工作流市场常量。
 *
 * <p>定义市场搜索分页、排序与审核状态映射，供 marketplace 查询及审核流程复用。
 */
public final class MarketplaceConstants {
    /** 审核 UI 状态：待审核。 */
    public static final String UI_STATUS_PENDING = "pending";
    /** 审核 UI 状态：已通过。 */
    public static final String UI_STATUS_APPROVED = "approved";
    /** 审核 UI 状态：已驳回。 */
    public static final String UI_STATUS_REJECTED = "rejected";
    /** 默认排序字段：安装量。 */
    public static final String DEFAULT_SORT = "install_count";
    /** 排序字段：发布时间。 */
    public static final String SORT_PUBLISHED_AT = "published_at";
    /** 默认页码。 */
    public static final int DEFAULT_PAGE = 1;
    /** 默认每页条数。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** 每页条数上限。 */
    public static final int MAX_PAGE_SIZE = 100;
    /** 审核列表条数上限。 */
    public static final int REVIEW_LIMIT = 100;
    /** 安装成功状态。 */
    public static final String STATUS_INSTALLED = "installed";
    /** 安全扫描结果字段：是否通过。 */
    public static final String SCAN_PASSED = "passed";
    /** 安全扫描结果字段：问题列表。 */
    public static final String SCAN_ISSUES = "issues";
    /** 安全扫描结果字段：提示消息。 */
    public static final String SCAN_MESSAGE = "message";
    private MarketplaceConstants() {
    }
}
