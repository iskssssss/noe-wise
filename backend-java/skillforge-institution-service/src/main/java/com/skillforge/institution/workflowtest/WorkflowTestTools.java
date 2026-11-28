package com.skillforge.institution.workflowtest;

import java.util.Set;

/** 测试用途允许的基础平台工具；只读副作用本身不授予记忆、任务或文件访问。 */
public final class WorkflowTestTools {
    private static final Set<String> PLATFORM_TOOLS=Set.of(
            "get_current_time","calculate_expression","convert_datetime","json_query","json_schema_validate","template_render");

    private WorkflowTestTools() { }

    public static boolean allowsPlatform(String toolName,String providerRef) {
        return PLATFORM_TOOLS.contains(toolName)&&toolName.equals(providerRef);
    }
}
