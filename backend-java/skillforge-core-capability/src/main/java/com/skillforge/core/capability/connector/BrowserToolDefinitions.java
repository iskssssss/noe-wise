package com.skillforge.core.capability.connector;

import static com.skillforge.core.capability.connector.ToolSchemas.objectSchema;
import static com.skillforge.core.capability.connector.ToolSchemas.property;

import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 个人浏览器工具的模型说明、参数和执行约束。 */
final class BrowserToolDefinitions {
    private BrowserToolDefinitions() { }

    static PlatformToolCatalog.Entry browserStart() {
        return PlatformToolCatalog.Entry.builder()
                .name("browser_start")
                .title("启动浏览器任务")
                .publishedVersionKey("builtin:9")
                .description(browserStartDescription())
                .parametersSchema(browserStartInput())
                .outputSchema(property("object"))
                .effect(ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(90_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String browserStartDescription() {
        return "browser_start 启动本人个人浏览器任务；未指定设备时传 {}。等待连接时尚无可用 task，不重复启动或调用页面操作；用户连接后由服务端继续同一调用。"
                + "成功启动回执中的完整 task UUID 才可操作；确定参数拒绝可纠正，UNKNOWN 先核对原回执，不重放。仅通过浏览器工具操作，不执行上游 CLI、Shell 或代码沙箱；不支持远程文件上传下载。";
    }

    static PlatformToolCatalog.Entry browserCall() {
        return PlatformToolCatalog.Entry.builder()
                .name("browser_call")
                .title("操作浏览器")
                .publishedVersionKey("builtin:13")
                .description(browserCallDescription())
                .parametersSchema(browserCallInput())
                .outputSchema(property("object"))
                .effect(ToolEffect.NON_IDEMPOTENT_WRITE)
                .idempotent(false)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(300_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String browserCallDescription() {
        return "browser_call 操作已成功启动且 active 的个人浏览器任务。动作须依据准确标签页的当前真实观察与新鲜 ref；页面变化后重新观察，不猜测选择器或复用旧 ref。"
                + "失败不得用 evaluate 绕过元素可见性、借用确认或授权边界；借用用户标签页须等待用户确认。evaluate 成功只证明求值成功，业务完成须核对真实页面证据。"
                + "动作已成功而观察 partial/unconfirmed 时先核对页面，不重复动作；new_tabs/candidate_only 只是候选，不新增控制授权。submitted 仅表示受理，录制须核对持久回执及产物。"
                + "交付链接前、browser_stop 前核对真实标题与完整 href；域名提示、猜测地址、省略号不能当作链接。截断时读取完整原来源或缩小读取范围，不重新执行页面动作。"
                + "截图返回真实图片，可通过 read_image 读取 media_id。UNKNOWN 沿原回执收敛，不自动重放副作用。仅通过本工具 command 操作，不执行上游 CLI 或 Shell。";
    }

    private static Map<String, Object> browserCallInput() {
        return objectSchema(
                Map.of(
                    "task", Map.of(
                        "type", "string",
                        "description", "实际成功启动回执或 browser_tasks 目录中同一目标 active 任务的完整 task UUID，必须原样复制；不是 skill: 句柄、session_id、run_id 或 browser_id。尚无已启动任务时先调用 browser_start {}，已有任务但缺少引用时先查询 browser_tasks。"),
                    "command", com.skillforge.execution.browser.BrowserCommandContract.schema()),
                List.of("task","command"));
    }

    static PlatformToolCatalog.Entry browserStop() {
        return PlatformToolCatalog.Entry.builder()
                .name("browser_stop")
                .title("停止浏览器任务")
                .publishedVersionKey("builtin:7")
                .description(browserStopDescription())
                .parametersSchema(browserStopInput())
                .outputSchema(property("object"))
                .effect(ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(90_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String browserStopDescription() {
        return "browser_stop 停止本次准确的既有个人浏览器任务，不为停止任务而新建 browser_start。预算收尾保留一次必要停止，须在成果最后冻结和最终答复之前执行；无已启动任务时不调用。"
                + "返回有界停止状态与安全清理字段，完整上游回执独立持久；未明确的清理状态保持未知，UNKNOWN 先核对原停止回执，不自动重放。仅通过本工具停止，不执行上游 CLI 或 Shell。";
    }

    private static Map<String, Object> browserStopInput() {
        return objectSchema(
                Map.of(
                    "task", Map.of(
                        "type", "string",
                        "description", "要停止的既有个人任务完整 task UUID，原样复制成功 browser_start 回执或 browser_tasks 的准确记录；不是 Skill 句柄、session_id 或 browser_id。缺少引用时查询已有任务，不创建新任务用于停止。")),
                List.of("task"));
    }

    static PlatformToolCatalog.Entry browserTasks() {
        return PlatformToolCatalog.Entry.builder()
                .name("browser_tasks")
                .title("查询浏览器任务")
                .publishedVersionKey("builtin:6")
                .description(browserTasksDescription())
                .parametersSchema(browserTasksInput())
                .outputSchema(property("object"))
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(30_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String browserTasksDescription() {
        return "browser_tasks 以 {} 只读查询本人任务与持久回执；按当前会话和目标定位，不按最新记录或列表位置选择。active 才可继续，inactive/binding_lost 不可续接；UNKNOWN 不表示可用或已清理，不授权重放。"
                + "目录摘要不含完整页面结果，按需回读原工具来源；仅通过本工具查询，不执行上游 CLI 或 Shell。";
    }

    private static Map<String, Object> browserTasksInput() {
        return objectSchema(Map.of(), List.of());
    }

    private static Map<String, Object> browserStartInput() {
        return Map.of("type", "object", "oneOf", List.of(
                objectSchema(Map.of(), List.of()),
                objectSchema(Map.of("browser", Map.of("type", "string", "minLength", 1, "description",
                        "可选。仅在已获得本人设备目录返回的真实 browser_id 时指定；未指定设备时省略此字段并传 {}。空字符串、default、chrome 或 edge 不是默认设备选择方式。")), List.of("browser"))));
    }
}
