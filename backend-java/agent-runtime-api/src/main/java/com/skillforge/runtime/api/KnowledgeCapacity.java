package com.skillforge.runtime.api;

/** 单次知识检索默认容量与证据段资源边界，所有消费者使用同一事实。 */
public final class KnowledgeCapacity {
    public static final int DEFAULT_MAX_QUERIES = 4;
    public static final int DEFAULT_MAX_ITEMS = 100;
    public static final int DEFAULT_MAX_TOKENS = 65_536;
    public static final int MAX_SEGMENT_CHARACTERS = 16_384;
    public static final int DEFAULT_MAX_CHARACTERS = 262_144;
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private KnowledgeCapacity() { }
}
