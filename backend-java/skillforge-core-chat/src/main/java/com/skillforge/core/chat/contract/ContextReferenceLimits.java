package com.skillforge.core.chat.contract;

/** 原文读取的请求范围与模型消费容量；分页不能作为整个任务的读取总量上限。 */
public final class ContextReferenceLimits {
    /** 模型可消费的单页上限，保证首次回读不会一次锁住长篇全文；按 Unicode code point 计。 */
    public static final int MAX_MODEL_PAGE_CHARACTERS = 4096;
    public static final int DEFAULT_PAGE_CHARACTERS = MAX_MODEL_PAGE_CHARACTERS;
    /** 允许提交的读取 limit 上界；实际返回页仍受模型消费容量限制。 */
    public static final int MAX_PAGE_CHARACTERS = 32768;
    /** 历史记录内容预览的单条 Unicode code point 上限；窗口最多 101 条。 */
    public static final int MAX_HISTORY_RECORD_CHARACTERS = 128;
    /** 历史页所有记录正文预览的 Unicode code point 总预算，不包含元数据。 */
    public static final int MAX_HISTORY_PAGE_CHARACTERS = 4096;
    private ContextReferenceLimits() { }
}
