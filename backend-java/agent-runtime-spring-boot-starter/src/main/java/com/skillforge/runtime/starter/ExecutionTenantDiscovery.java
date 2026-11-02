package com.skillforge.runtime.starter;

import java.util.List;

/** 唯一宿主租户发现事实源；严格按文本游标递增并在读取侧应用上限。 */
@FunctionalInterface
public interface ExecutionTenantDiscovery {
    List<String> page(String after, int limit);
}
