package com.skillforge.runtime.api;

/** 知识选择器的长度、分页与读取限额；标题和正文长度使用 Unicode 码点，游标使用字符数。 */
public final class KnowledgeOperationLimits {
    public static final int MAX_TITLE_FILTER_CHARACTERS = 512;
    public static final int MAX_CURSOR_CHARACTERS = 4096;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_READ_OFFSET = 0;
    public static final int DEFAULT_READ_CHARACTERS = 4000;
    public static final int MAX_READ_CHARACTERS = 16_000;

    private KnowledgeOperationLimits() { }
}
