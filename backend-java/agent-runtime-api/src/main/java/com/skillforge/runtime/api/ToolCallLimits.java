package com.skillforge.runtime.api;

/** Runtime 工具数量与身份区间的唯一常量；模型批次和程序回调不能重叠。 */
public final class ToolCallLimits {
    public static final int MIN_READ_ONLY_BATCH = 2;
    public static final int READ_ONLY_BATCH = 8;
    public static final int PROGRAM_CALLS = 1_024;
    public static final int MAX_CHILD_ORDINAL = READ_ONLY_BATCH + PROGRAM_CALLS;
    private ToolCallLimits() { }
}
