package com.skillforge.core.knowledge.constant;

/**
 * 知识库常量。
 *
 * <p>定义知识库/文档状态、来源类型、分块参数与检索请求响应字段名，
 * 作为当前 RAG 契约的权威常量来源。
 */
public final class KnowledgeConstants {

    /** 知识库状态：可用。 */
    public static final String BASE_STATUS_ACTIVE = "active";

    /** 知识库状态：已归档。 */
    public static final String BASE_STATUS_ARCHIVED = "archived";

    /** Knowledge Base revision 状态：草稿。 */
    public static final String REVISION_STATUS_DRAFT = "draft";

    /** Knowledge Base revision 状态：已就绪，等待发布。 */
    public static final String REVISION_STATUS_READY = "ready";

    /** Knowledge Base revision 状态：当前已发布。 */
    public static final String REVISION_STATUS_PUBLISHED = "published";

    /** Knowledge Base revision 状态：历史已归档。 */
    public static final String REVISION_STATUS_ARCHIVED = "archived";

    /** 文档状态：已就绪。 */
    public static final String DOC_STATUS_READY = "ready";

    /** 文档状态：处理中。 */
    public static final String DOC_STATUS_PROCESSING = "processing";

    /** 文档状态：处理失败。 */
    public static final String DOC_STATUS_FAILED = "failed";

    /** 文档来源：文件导入。 */
    public static final String SOURCE_FILE = "file";

    /** 文档来源：审核通过的机构知识投影。 */
    public static final String SOURCE_PROJECTION = "projection";

    /** 索引方式：经济（仅关键词，不生成向量）。 */
    public static final String INDEXING_MODE_ECONOMICAL = "economical";

    /** 检索模式：全文检索。 */
    public static final String RETRIEVAL_MODE_FULL_TEXT = "full_text";

    /** 检索模式：混合检索（向量 + 关键词加权融合）。 */
    public static final String RETRIEVAL_MODE_HYBRID = "hybrid";

    /** 默认检索返回条数。 */
    public static final int DEFAULT_TOP_K = 5;

    /** 文档正文请求字段名。 */
    public static final String CONTENT = "content";

    /** 文档 ID 响应字段名。 */
    public static final String DOCUMENT_ID = "document_id";

    /** 分块数量响应字段名。 */
    public static final String CHUNKS = "chunks";

    /** 分块 ID 响应字段名。 */
    public static final String CHUNK_ID = "chunk_id";

    /** 检索得分响应字段名。 */
    public static final String SCORE = "score";

    /** 重排前的相似度响应字段名（Rerank 场景保留原始分数）。 */
    public static final String VECTOR_SCORE = "vector_score";
    /** 主键 ID 响应字段名。 */
    public static final String FIELD_ID = "id";
    /** 名称响应字段名。 */
    public static final String FIELD_NAME = "name";
    /** 描述响应字段名。 */
    public static final String FIELD_DESCRIPTION = "description";
    /** 状态响应字段名。 */
    public static final String FIELD_STATUS = "status";

    private KnowledgeConstants() {
    }
}
