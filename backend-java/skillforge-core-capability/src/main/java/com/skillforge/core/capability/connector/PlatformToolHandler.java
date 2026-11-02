package com.skillforge.core.capability.connector;

import java.util.Map;

/**
 * 平台内置工具处理器。
 *
 * <p>平台内置工具由 Agent Runtime 的 PLATFORM Provider 分派；内置工具名为
 * 平台保留字，不可被外部连接器绑定占用。
 *
 * <p>实现类须注册为 Spring Bean（按 {@link #toolName()} 归入内置表）。
 */
public interface PlatformToolHandler {

    /**
     * 内置工具名（workflow tool 节点 {@code tool:} 字段引用）。
     *
     * @return 工具名
     */
    String toolName();

    /**
     * 执行内置工具调用。
     *
     * <p>身份、Run、调用 attempt、截止时间和取消信号只来自可信 Runtime 上下文；
     * Handler 不得从模型参数读取这些字段，也不接收可跨调用复用的 Action permit。
     *
     * @param args 工具参数
     * @param context 可信执行上下文
     * @return 工具执行结果
     * @throws Exception 已发出外部请求但无法确定对端结果时抛出，由 PLATFORM Provider 标为 unknown
     */
    Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context)
            throws Exception;
}
