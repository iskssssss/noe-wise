package com.skillforge.core.workflow.constant;
/**
 * 工作流域常量。
 *
 * <p>定义工作流模板状态、实例状态、价格类型、作者类型与默认值，
 * 与 {@code workflow_templates} / {@code workflow_instances} 列取值保持一致。
 */
public final class WorkflowConstants {
    /** 模板状态：草稿。 */
    public static final String STATUS_DRAFT = "draft";
    /** 模板状态：待审核。 */
    public static final String STATUS_REVIEW = "review";
    /** 模板状态：已发布。 */
    public static final String STATUS_PUBLISHED = "published";
    /** 实例状态：已发布。 */
    public static final String INSTANCE_STATUS_PUBLISHED = "published";
    /** 实例状态：已归档。 */
    public static final String INSTANCE_STATUS_ARCHIVED = "archived";
    /** 价格类型：免费。 */
    public static final String PRICE_TYPE_FREE = "free";
    /** 作者类型：租户。 */
    public static final String AUTHOR_TYPE_TENANT = "tenant";
    /** 工作流类型：状态机。 */
    public static final String TYPE_STATE_MACHINE = "state-machine";
    /** 默认版本号。 */
    public static final String DEFAULT_VERSION = "1.0.0";
    /** 上传成功状态。 */
    public static final String STATUS_UPLOADED = "uploaded";
    /** 安装成功状态。 */
    public static final String STATUS_INSTALLED = "installed";
    /** 工作流包上传大小上限（字节）。 */
    public static final long MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    /** zip 包单条目解压大小上限（字节），防 zip 炸弹内存耗尽。 */
    public static final int ZIP_MAX_ENTRY_BYTES = 512 * 1024;
    /** zip 包解压总字节上限（条目累计，防压缩炸弹整体内存放大）。 */
    public static final long ZIP_MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    /** zip 包条目数量上限。 */
    public static final int ZIP_MAX_ENTRIES = 200;
    /** workflow.yaml meta 段键：工作流名称。 */
    public static final String KEY_NAME = "name";
    /** workflow.yaml meta 段键：工作流类型。 */
    public static final String KEY_TYPE = "type";
    /** workflow.yaml meta 段键：版本号（包版本 SemVer）。 */
    public static final String KEY_VERSION = "version";
    /** workflow.yaml meta 段键：工作流描述。 */
    public static final String KEY_DESCRIPTION = "description";
    /** workflow.yaml meta 段键：显示名称。 */
    public static final String KEY_DISPLAY_NAME = "display_name";
    /** workflow.yaml meta 段键：作者。 */
    public static final String KEY_AUTHOR = "author";
    /** workflow.yaml meta 段键：分类。 */
    public static final String KEY_CATEGORY = "category";
    /** workflow.yaml meta 段键：标签。 */
    public static final String KEY_TAGS = "tags";
    /** workflow.yaml meta 段键：配置 schema。 */
    public static final String KEY_CONFIG_SCHEMA = "config_schema";
    /** workflow.yaml meta 段键：所需权限。 */
    public static final String KEY_REQUIRED_PERMISSIONS = "required_permissions";
    /** workflow.yaml meta 段键：连接器要求。 */
    public static final String KEY_CONNECTOR_REQUIREMENTS = "connector_requirements";
    /** workflow.yaml meta 段键：工具列表。 */
    public static final String KEY_TOOLS = "tools";
    /** workflow.yaml 顶层元数据段名（承接原 WORKFLOW.md frontmatter 全字段）。 */
    public static final String KEY_META = "meta";
    /** 配置 schema 项字段：配置键名。 */
    public static final String CONFIG_KEY = "key";
    /** 配置 schema 项字段：是否必填。 */
    public static final String CONFIG_REQUIRED = "required";
    private WorkflowConstants() {
    }
}
