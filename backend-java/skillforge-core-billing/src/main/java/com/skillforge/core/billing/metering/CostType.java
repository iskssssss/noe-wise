package com.skillforge.core.billing.metering;

/**
 * 用量事件类型（对应 {@code usage_events.event_type} 取值）。
 *
 * <p>「最小单元」口径：每次 AI 调用、知识源操作或 Workflow 外部动作
 * 与每条用户请求（request）各记一条原子事件；后续新增花费类型在此枚举扩展
 * （计费统一管理，服务商无关）。
 */
public enum CostType {

    /** 请求根事件：每条用户请求一条（quantity=1、tokens=0、无金额），配额计数与链路根。 */
    REQUEST("request"),

    /** 每次 LLM chat/completions 调用。 */
    LLM_CHAT("llm.chat"),

    /** 每次 embedding 调用（输入 token 估算计费）。 */
    LLM_EMBEDDING("llm.embedding"),

    /** 每次 rerank 调用（按文档数计费）。 */
    LLM_RERANK("llm.rerank"),

    /** 一次供应商图片生成或编辑操作；图片数量由独立实测字段表达。 */
    MEDIA_IMAGE("media.image"),
    /** 一次供应商视频生成操作。 */
    MEDIA_VIDEO("media.video"),
    /** 一次供应商语音识别操作。 */
    MEDIA_TRANSCRIPTION("media.transcription"),
    /** 一次供应商语音合成操作。 */
    MEDIA_SPEECH("media.speech"),

    /** 每次受治理知识源读取（按 source operation 数量计费）。 */
    KNOWLEDGE_SOURCE("knowledge.source"),

    /** 每次真实外部工具或 Connector 调用，不冒充 Workflow 动作。 */
    TOOL_CALL("tool.call"),

    /** 每次受治理外部 Workflow 副作用动作（按 action 数量计费）。 */
    WORKFLOW_EXTERNAL("workflow.external");

    /** 数据库取值。 */
    private final String value;

    CostType(String value) {
        this.value = value;
    }

    /**
     * 数据库取值。
     *
     * @return 事件类型字符串
     */
    public String value() {
        return value;
    }

    /** 数量列只保留实际调用单位；token 明细由独立 token 列表达。 */
    public String measurementUnit() {
        return switch (this) {
            case REQUEST -> "request";
            case LLM_RERANK -> "document";
            default -> "call";
        };
    }
}
