package com.skillforge.core.billing.invocation;

/** 所有可能产生外部费用或副作用的受控调用类别。 */
public enum ExternalOperationType {

    MODEL_CHAT("model.chat"),
    MODEL_EMBEDDING("model.embedding"),
    MODEL_RERANK("model.rerank"),
    MODEL_IMAGE("media.image"),
    MODEL_VIDEO("media.video"),
    MODEL_TRANSCRIPTION("media.transcription"),
    MODEL_SPEECH("media.speech"),
    KNOWLEDGE_SOURCE("knowledge.source"),
    TOOL_CALL("tool.call"),
    CONNECTOR_CALL("connector.call"),
    WORKFLOW_EXTERNAL("workflow.external");

    private final String value;

    ExternalOperationType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
