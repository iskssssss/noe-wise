package com.skillforge.core.agent.constant;
/**
 * Agent 域常量。
 *
 * <p>定义 Agent 状态、默认 Key 前缀与操作状态，
 * 与 {@code agents} 列取值保持一致。
 */
public final class AgentConstants {
    /** Agent 状态：草稿。 */
    public static final String STATUS_DRAFT = "draft";
    /** Agent 状态：已发布。 */
    public static final String STATUS_PUBLISHED = "published";
    /** Agent 状态：已下架（模板生命周期，新版本发布后旧版本自动置此状态）。 */
    public static final String STATUS_DEPRECATED = "deprecated";
    /** Agent 状态：已归档。 */
    public static final String STATUS_ARCHIVED = "archived";
    /** 删除状态：已删除。 */
    public static final String STATUS_DELETED = "deleted";
    /** 操作状态：已发布。 */
    public static final String ACTION_PUBLISHED = "published";
    /** 模板定义字段：系统提示词。 */
    public static final String DEFINITION_SYSTEM_PROMPT = "system_prompt";
    /** 模板定义字段：角色描述。 */
    public static final String DEFINITION_ROLE_DESCRIPTION = "role_description";
    /** 模板定义字段：LLM 配置。 */
    public static final String DEFINITION_LLM_CONFIG = "llm_config";
    /** 模板定义字段：规划规则。 */
    public static final String DEFINITION_PLANNING_RULES = "planning_rules";
    private AgentConstants() {
    }
}
