package com.skillforge.core.capability.connector;

import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import java.util.Map;
import java.util.Optional;

/**
 * 需要 Runtime 持久化输入等待并以新 attempt 恢复的平台内置工具。
 *
 * <p>实现仍通过 {@link PlatformToolHandler} 的唯一 Handler 表发现；同步入口固定拒绝，
 * 避免不支持 continuation 的调用方误把一次交互调用当成普通工具完成。
 */
public interface PlatformInteractiveToolHandler extends PlatformToolHandler {

    /** 发起调用，允许返回一个需要持久化的输入请求。 */
    PlatformToolInteractionResult invoke(
            Map<String, Object> arguments,
            PlatformToolExecutionContext context) throws Exception;

    /** 只消费 Runtime 从原等待事实冻结并复核后的响应与 opaque 状态。 */
    PlatformToolInteractionResult resume(
            Map<String, Object> arguments,
            Map<String, ToolInputResponse> inputResponses,
            Optional<String> requestState,
            PlatformToolExecutionContext context) throws Exception;

    /** 交互工具不能降级为同步执行，否则会绕过 durable Tool continuation。 */
    @Override
    default Map<String, Object> execute(
            Map<String, Object> arguments,
            PlatformToolExecutionContext context) {
        return Map.of(
                "success", false,
                "error", "interactive_tool_requires_runtime_continuation");
    }
}
