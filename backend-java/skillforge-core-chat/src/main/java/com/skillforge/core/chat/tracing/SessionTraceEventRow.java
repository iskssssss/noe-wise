package com.skillforge.core.chat.tracing;

import lombok.Data;

/** 受控事件内容读取的持久行；不承载另一套执行分组或运行状态。 */
@Data
public class SessionTraceEventRow {
    private String eventType;
    private String payloadSchemaId;
    private String payloadCanonicalJson;
}
