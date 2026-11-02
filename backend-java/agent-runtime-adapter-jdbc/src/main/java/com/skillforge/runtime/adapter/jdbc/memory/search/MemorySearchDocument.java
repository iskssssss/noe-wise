package com.skillforge.runtime.adapter.jdbc.memory.search;

import java.util.Objects;

/** 所属类型提供的正文投影；JDBC 不读取 JSON 字段来重建业务文本。 */
public record MemorySearchDocument(String text, String projectionVersion) {
    public static final String VERSION = "memory-body-v1";

    /** 空正文允许表达没有可索引内容；版本必须显式给出，不能推断或回退旧投影。 */
    public MemorySearchDocument {
        text = Objects.requireNonNull(text, "text");
        projectionVersion = Objects.requireNonNull(projectionVersion, "projectionVersion");
        if (projectionVersion.isBlank()) throw new IllegalArgumentException("memory_search_projection_version_required");
    }
}
