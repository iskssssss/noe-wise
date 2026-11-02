package com.skillforge.core.capability.connector;

import static com.skillforge.core.capability.connector.ToolSchemas.arraySchema;
import static com.skillforge.core.capability.connector.ToolSchemas.objectSchema;
import static com.skillforge.core.capability.connector.ToolSchemas.property;

import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.session.SessionGoal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 记忆、持续任务、计划、Goal 与能力发现的发布定义。 */
final class TaskToolDefinitions {
    private TaskToolDefinitions() { }

    static PlatformToolCatalog.Entry searchMemory() {
        return PlatformToolCatalog.Entry.builder()
                .name(MemorySearchToolContract.NAME)
                .title("检索历史记忆")
                .publishedVersionKey("builtin:4")
                .description(searchMemoryDescription())
                .parametersSchema(searchMemoryInput())
                .outputSchema(searchMemoryOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String searchMemoryDescription() {
        return "仅在当前请求确需过去的偏好或任务事实、且当前上下文不足时检索记忆。群聊默认检索当前发言者的本群事实和相关群公共事实；"
                + "明确涉及其他成员时才传 subject_end_user_id，使用本群已知成员ID（可已退出）；不能换群或读取私聊记忆，私聊不传该字段。结果是历史参考，"
                + "不能覆盖当前要求或证明当前外部状态；普通问答、转换已有内容无需先检索。";
    }

    private static Map<String, Object> searchMemoryInput() {
        return objectSchema(
                Map.of(
                    MemorySearchToolContract.QUERY, Map.of("type", "string", "minLength", 1, "maxLength", 2000, "description", "当前目标的检索关键词。"),
                    MemorySearchToolContract.LIMIT, Map.of("type", "integer", "minimum", 1, "maximum", 16, "default", 8),
                    MemorySearchToolContract.SUBJECT_END_USER_ID, Map.of(
                        "type", "string",
                        "pattern", "^[1-9][0-9]{0,18}$",
                        "description", "明确涉及本群其他成员时使用的已知内部成员ID；保持字符串，可查询已退出成员的历史事实。")),
                List.of(MemorySearchToolContract.QUERY));
    }

    private static Map<String, Object> searchMemoryOutput() {
        return objectSchema(
                Map.of(
                    MemorySearchToolContract.SUCCESS, property("boolean"),
                    MemorySearchToolContract.QUERY_ID, property("string"),
                    MemorySearchToolContract.RETRIEVAL_EVENT_ID, property("string"),
                    MemorySearchToolContract.AS_OF, property("string"),
                    MemorySearchToolContract.RETRIEVAL_MODE, property("string"),
                    MemorySearchToolContract.RETRIEVAL_STATUS, property("string"),
                    MemorySearchToolContract.DEGRADATION_REASON, property("string"),
                    MemorySearchToolContract.NOTICE, property("string"),
                    MemorySearchToolContract.FACTS, arraySchema(property("object")),
                    MemorySearchToolContract.ERROR, property("string")),
                List.of(MemorySearchToolContract.SUCCESS));
    }

    static PlatformToolCatalog.Entry searchTasks() {
        return PlatformToolCatalog.Entry.builder()
                .name("search_tasks")
                .title("查找历史任务")
                .publishedVersionKey("builtin:3")
                .description("仅在需要查找用户要继续的持久任务时搜索；不会启动任务，普通聊天或单次产物请求无需先搜索。page_full 只表示本页已满，是否继续取决于请求是否已满足。")
                .parametersSchema(searchTasksInput())
                .outputSchema(searchTasksOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> searchTasksInput() {
        return objectSchema(
                Map.of(
                    "query", Map.of("type", "string", "maxLength", 1000, "default", "", "description", "当前目标关键词。"),
                    "offset", Map.of("type", "integer", "minimum", 0, "maximum", 10000, "default", 0),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 30)),
                List.of());
    }

    private static Map<String, Object> searchTasksOutput() {
        return objectSchema(
                Map.of(
                    "success", property("boolean"),
                    "tasks", arraySchema(property("object")),
                    "offset", property("integer"),
                    "next_offset", property("integer"),
                    "page_full", property("boolean"),
                    "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry readTask() {
        return PlatformToolCatalog.Entry.builder()
                .name("read_task")
                .title("读取任务详情")
                .publishedVersionKey("builtin:2")
                .description("按已知 task_id 读取持久任务及事实来源；它是当前请求的参考，不授予工具执行权，不自动续接旧计划。")
                .parametersSchema(readTaskInput())
                .outputSchema(readTaskOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> readTaskInput() {
        return objectSchema(Map.of("task_id", property("string")), List.of("task_id"));
    }

    private static Map<String, Object> readTaskOutput() {
        return objectSchema(
                Map.of("success", property("boolean"), "task", property("object"), "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry updateTask() {
        return PlatformToolCatalog.Entry.builder()
                .name("update_task")
                .title("更新任务记录")
                .publishedVersionKey("builtin:3")
                .description(updateTaskDescription())
                .parametersSchema(updateTaskInput())
                .outputSchema(updateTaskOutput())
                .effect(ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String updateTaskDescription() {
        return "仅为用户要求的持续目标保存任务进展；单次查询、生成页面和格式转换无需创建任务。任务完成不等于外部业务成功，取消不撤销业务操作。";
    }

    private static Map<String, Object> updateTaskInput() {
        return objectSchema(
                Map.of(
                    "task_id", property("string"),
                    "expected_revision", Map.of("type", "integer", "minimum", 1, "description", "先读取目标记录取得当前 revision；更新已有记录时必须提供。"),
                    "title", property("string"),
                    "summary", property("string"),
                    "facts", arraySchema(
                        objectSchema(
                            Map.of(
                                "key", property("string"),
                                "value", property("string"),
                                "source_event_id", Map.of("type", "string", "description", "支持该事实的真实来源事件 ID，不得编造。"),
                                "confirmed", property("boolean")),
                            List.of("key", "value", "source_event_id"))),
                    "pending", arraySchema(property("string")),
                    "status", Map.of("type", "string", "enum", List.of("OPEN", "WAITING_USER", "COMPLETED", "CANCELLED"))),
                List.of());
    }

    private static Map<String, Object> updateTaskOutput() {
        return objectSchema(
                Map.of("success", property("boolean"), "task", property("object"), "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry exitPlanMode() {
        return PlatformToolCatalog.Entry.builder()
                .name("exit_plan_mode")
                .title("提交执行计划")
                .publishedVersionKey("builtin:4")
                .description("仅在计划模式中使用。提交计划供用户评审。用户批准后从下一模型步骤开始退出计划模式；用户选择继续规划时，"
                        + "根据返回反馈修订并再次提交。")
                .parametersSchema(exitPlanModeInput())
                .outputSchema(exitPlanModeOutput())
                .effect(ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> exitPlanModeInput() {
        return objectSchema(
                Map.of(
                    "plan", Map.of("type", "string", "minLength", 1, "description", "完整 Markdown 计划，第一行必须是命名计划的标题")),
                List.of("plan"));
    }

    private static Map<String, Object> exitPlanModeOutput() {
        return objectSchema(
                Map.of(
                    "success", property("boolean"),
                    "approved", property("boolean"),
                    "feedback", property("string"),
                    "error", property("string")),
                List.of("success", "approved"));
    }

    static PlatformToolCatalog.Entry getGoal() {
        return PlatformToolCatalog.Entry.builder()
                .name("get_goal")
                .title("查看任务目标")
                .publishedVersionKey("builtin:4")
                .description(getGoalDescription())
                .parametersSchema(getGoalInput())
                .outputSchema(goalOutputSchema())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String getGoalDescription() {
        return "读取用户显式创建的持续 Goal、revision、completion_pending 与成果 publication。没有 Goal 时返回空，不从普通请求推断目标；"
                + "pending 只表示等待正式发布，publication.published 的真实成果引用才证明发布成功。";
    }

    private static Map<String, Object> getGoalInput() {
        return objectSchema(Map.of(), List.of());
    }

    static PlatformToolCatalog.Entry updateGoal() {
        return PlatformToolCatalog.Entry.builder()
                .name("update_goal")
                .title("更新任务目标")
                .publishedVersionKey("builtin:6")
                .description(updateGoalDescription())
                .parametersSchema(updateGoalInput())
                .outputSchema(goalOutputSchema())
                .effect(ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String updateGoalDescription() {
        return "仅更新当前 Run 的显式 Goal。先 get_goal 读取 revision；无待交付要求且成功条件全部满足时才标记 COMPLETE，确实受阻时标记 BLOCKED "
                + "并说明原因。需要正式交付文件时使用 finalize_task_outputs.goal_completion.expected_revision 提交完成意图，"
                + "由宿主在发布后结算，不提前 COMPLETE。本工具不创建 Goal 或启用续行。";
    }

    private static Map<String, Object> updateGoalInput() {
        return objectSchema(
                Map.of(
                    "expected_revision", Map.of("type", "integer", "minimum", 1, "description", "先读取目标记录取得当前 revision；更新已有记录时必须提供。"),
                    "state", Map.of("type", "string", "enum", List.of("COMPLETE", "BLOCKED")),
                    "reason", Map.of("type", "string", "maxLength", 512)),
                List.of("expected_revision", "state"));
    }

    static PlatformToolCatalog.Entry searchTools() {
        return PlatformToolCatalog.Entry.builder()
                .name("search_tools")
                .title("查找可用工具")
                .publishedVersionKey("builtin:5")
                .description(searchToolsDescription())
                .parametersSchema(searchToolsInput())
                .outputSchema(searchToolsOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String searchToolsDescription() {
        return "按当前请求需要的能力搜索本次冻结的已授权工具目录；结果是目录摘录，has_more=true 表示确有匹配条目未列出。"
                + "调用时只使用当前模型请求已显示的完整工具定义及其实际函数名；未显示完整定义时不能猜测句柄或参数。搜索不执行工具、不扩大授权，也不追加工具定义。已有可用工具足够时直接调用。";
    }

    private static Map<String, Object> searchToolsInput() {
        return objectSchema(
                Map.of(
                    "query", Map.of("type", "string", "minLength", 1, "maxLength", 200, "description", "简短能力关键词，多词以空格分隔。"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 12)),
                List.of("query"));
    }

    private static Map<String, Object> searchToolsOutput() {
        return objectSchema(
                Map.of(
                    "success", property("boolean"),
                    "tools", arraySchema(
                        objectSchema(
                            Map.of(
                                "handle", Map.of("type", "string", "description", "实际原生函数名，与当前完整工具定义的 name 对应；run_code 的 tools.call 也使用此值。"),
                                "name", Map.of("type", "string", "description", "用于理解与消歧的限定业务名称，不是调用别名。"),
                                "description", Map.of(
                                    "type", "string",
                                    "maxLength", 400,
                                    "description", "标题与描述的完整短文或标明摘录的开头及命中片段；不替代完整工具定义及调用限制。")),
                            List.of("handle", "name", "description"))),
                    "has_more", Map.of("type", "boolean", "description", "成功搜索时返回；仅在确有额外匹配条目未列出时为 true。"),
                    "hint", property("string"),
                    "error", property("string")),
                List.of("success", "tools"));
    }

    private static Map<String, Object> goalOutputSchema() {
        Map<String, Object> goal = new LinkedHashMap<>(objectSchema(
                Map.of(
                        "goal_id", property("string"),
                        "revision", Map.of("type", "integer", "minimum", 1),
                        "objective", property("string"),
                        "state", Map.of("type", "string", "enum", List.of("ACTIVE", "PAUSED", "COMPLETE", "BLOCKED")),
                        "max_rounds", Map.of(
                                "type", "integer",
                                "minimum", SessionGoal.MIN_ROUNDS,
                                "maximum", SessionGoal.MAX_ROUNDS),
                        "rounds_started", Map.of("type", "integer", "minimum", 0),
                        "pending_round_input_id", Map.of("type", List.of("string", "null")),
                        "last_run_id", Map.of("type", List.of("string", "null")),
                        "reason", Map.of("type", List.of("string", "null"))),
                List.of("goal_id", "revision", "objective", "state", "max_rounds", "rounds_started",
                        "pending_round_input_id", "last_run_id", "reason")));
        goal.put("type", List.of("object", "null"));

        Map<String, Object> publication = new LinkedHashMap<>(objectSchema(
                Map.of(
                        "run_id", property("string"),
                        "result_ref", property("string"),
                        "status", Map.of("type", "string", "enum", List.of("pending", "published", "failed", "cancelled")),
                        "reason", Map.of("type", List.of("string", "null")),
                        "revision_refs", arraySchema(property("string"))),
                List.of("run_id", "result_ref", "status", "reason", "revision_refs")));
        publication.put("type", List.of("object", "null"));

        return objectSchema(
                Map.of(
                        "success", property("boolean"),
                        "error", property("string"),
                        "goal", Map.copyOf(goal),
                        "continuation_enabled", property("boolean"),
                        "completion_pending", property("boolean"),
                        "publication", Map.copyOf(publication)),
                List.of("success"));
    }
}
