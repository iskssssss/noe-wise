package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 冻结 Workflow Execution 运行模式的公开类型位置、结构与枚举值。 */
class ExecutionModeContractTest {

    /** 验证运行模式只能使用事实源规定的前台与后台两个稳定公开值。 */
    @Test
    void exposesExactExecutionModes() {
        assertEquals("com.skillforge.runtime.api.ExecutionMode", ExecutionMode.class.getName());
        assertEquals(
                List.of("FOREGROUND", "BACKGROUND"),
                Arrays.stream(ExecutionMode.values()).map(Enum::name).toList());
    }

    /** 验证公共枚举不是可扩展、嵌套或继承其他 SDK 契约的替代结构。 */
    @Test
    void keepsExecutionModeAsStandaloneEnum() {
        assertTruePublicTopLevelEnum(ExecutionMode.class);
        assertEquals(0, ExecutionMode.class.getInterfaces().length);
        assertFalse(ExecutionMode.class.isSealed());
    }

    /** 集中验证公开顶层 enum 结构，避免测试只比较枚举值而漏掉 ABI 形状。 */
    private static void assertTruePublicTopLevelEnum(Class<?> type) {
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(type.isEnum());
        assertEquals(null, type.getEnclosingClass());
    }
}
