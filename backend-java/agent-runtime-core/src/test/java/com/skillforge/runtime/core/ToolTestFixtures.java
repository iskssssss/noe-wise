package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;

/** Tool 测试统一使用明确的单次 Provider 路由身份。 */
final class ToolTestFixtures {
    private ToolTestFixtures() {
    }

    static ToolBinding binding() {
        return new ToolBinding(ToolBinding.ProviderType.TEST_MOCK, "fixture", "lookup");
    }
}
