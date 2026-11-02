package com.skillforge.core.workflow.constant;

/**
 * 可视化构建器常量。
 *
 * <p>定义构建器导出、快照与差异接口的默认值、状态与字段名。
 */
public final class BuilderConstants {

    /** 默认工作流业务 ID。 */
    public static final String DEFAULT_WORKFLOW_ID = "builder-workflow";

    /** 默认快照版本号。 */
    public static final String DEFAULT_VERSION = "1.0.0";

    /** 导出状态：已导出。 */
    public static final String STATUS_EXPORTED = "exported";

    /** 快照状态：已创建。 */
    public static final String STATUS_CREATED = "created";

    /** workflow.yaml 内容字段名（WORKFLOW.md 已合并进 meta 段，不再单独导出）。 */
    public static final String WORKFLOW_YAML = "workflow_yaml";

    /** 导出文件列表字段名。 */
    public static final String FILES = "files";

    /** workflow 文件名（导出包唯一声明文件）。 */
    public static final String WORKFLOW_YAML_FILE = "workflow.yaml";

    /** 差异摘要字段名。 */
    public static final String SUMMARY = "summary";
    /** 导出文件名字段名。 */
    public static final String FILE_NAME = "name";
    /** 导出文件内容字段名。 */
    public static final String FILE_CONTENT = "content";
    /** 差异结果字段：新增节点。 */
    public static final String NODES_ADDED = "nodes_added";
    /** 差异结果字段：移除节点。 */
    public static final String NODES_REMOVED = "nodes_removed";
    /** 差异结果字段：变更节点。 */
    public static final String NODES_CHANGED = "nodes_changed";
    /** 差异结果字段：新增边。 */
    public static final String EDGES_ADDED = "edges_added";
    /** 差异结果字段：移除边。 */
    public static final String EDGES_REMOVED = "edges_removed";
    /** 差异结果字段：变更边。 */
    public static final String EDGES_CHANGED = "edges_changed";
    /** workflow 配置字段：失败策略。 */
    public static final String WORKFLOW_STRATEGY = "strategy";
    /** workflow 失败策略：先重试后回退。 */
    public static final String STRATEGY_RETRY_THEN_FALLBACK = "retry_then_fallback";
    /** workflow 配置字段：最大重试次数。 */
    public static final String WORKFLOW_MAX_RETRIES = "max_retries";

    private BuilderConstants() {
    }
}
