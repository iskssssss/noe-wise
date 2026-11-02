package com.skillforge.runtime.codec.spi;

/** 工具私有结果超过编码预算；不包含结果正文。 */
public final class ToolOutcomeCapacityExceeded extends IllegalArgumentException {
    public ToolOutcomeCapacityExceeded() { super("tool outcome capacity exceeded"); }
}
