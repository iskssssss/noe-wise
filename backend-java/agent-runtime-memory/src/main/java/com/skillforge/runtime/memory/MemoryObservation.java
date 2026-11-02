package com.skillforge.runtime.memory;

import com.skillforge.runtime.api.TypedPayload;
import java.util.Objects;

/** 已授权记忆事件的安全元数据读取器；不返回事实正文、查询文本或授权材料。 */
public final class MemoryObservation {
    private MemoryObservation() { }

    public enum Kind { RETRIEVAL, MEMORY }

    /** operationId 只用于同一真实Run内归并；RECORDED不表示业务目标达成或当前仍可读正文。 */
    public record Metadata(String operationId, Kind kind, String operation, String phase, String status) {
        public Metadata {
            if (operationId == null || operationId.isBlank() || operation == null || operation.isBlank()
                    || status == null || status.isBlank()) throw new IllegalArgumentException("memory_observation_invalid");
            Objects.requireNonNull(kind);
        }
    }

    /** 必须先核事件所属Session、Run和当前读取权限；本方法只解释当前版本协议。 */
    public static Metadata read(String eventType, TypedPayload payload) {
        Objects.requireNonNull(payload);
        return MemoryEventCodec.observation(eventType, payload);
    }
}
