package com.skillforge.core.workflow.execution;

import java.util.List;
import java.util.Map;

/**
 * 工作流模型定义。
 *
 * <p>对应工作流包中的 workflow.yaml：入口节点、节点列表与条件边列表，
 * 是状态机引擎执行的数据结构。
 */
public final class WorkflowModels {

    private WorkflowModels() {
    }

    /**
     * 工作流。
     *
     * @param key      工作流标识（工作流业务 ID），checkpoint 落库用
     * @param entry    入口节点 ID
     * @param nodes    节点列表
     * @param edges  条件边列表
     */
    public record Workflow(String key, String entry, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
    }

    /**
     * 工作流节点。
     *
     * @param id       节点 ID
     * @param type     节点类型：取值见 {@link com.skillforge.core.workflow.execution.node.NodeTypes}，
     *                 引擎按类型分派到对应节点执行器
     * @param name     节点显示名称
     * @param prompt   LLM 节点提示词
     * @param tool     工具节点工具名
     * @param args     工具节点参数
     * @param resultTo 执行结果写入位置（state / extracted）
     * @param message  输出节点消息模板
     * @param card     人工确认卡片定义
     * @param branches parallel 节点的分支入口节点 ID 列表
     * @param workflow subgraph 节点引用的子工作流工作流 ID
     * @param extract  llm 节点输出 JSON 中提取并写入 resultTo 的键列表
     * @param onError       tool 节点失败策略：fail / goto:&lt;id&gt; / message，可空
     * @param actions       menu 节点行动点列表：{id, label, to}，可空
     * @param render        节点自定义渲染数据（任意键值，支持变量插值，平台原样透传），可空
     * @param mergeMessageId 卡片同 id merge 目标消息 ID（变量插值；命中时以
     *                       message.card.update 覆盖推送而非追加），可空
     * @param timeoutSeconds 挂起节点超时秒数（超时未恢复由超时扫描以
     *                       action="timeout" 恢复，条件边纯配置表达），可空
     * @param layout         节点画布位置 {x, y}（构建器导出/恢复用，引擎不消费），可空
     */
    public record WorkflowNode(
            String id,
            String type,
            String name,
            String prompt,
            String tool,
            Map<String, Object> args,
            String resultTo,
            String message,
            Map<String, Object> card,
            List<String> branches,
            String workflow,
            List<String> extract,
            String onError,
            List<Map<String, Object>> actions,
            Map<String, Object> render,
            String mergeMessageId,
            Integer timeoutSeconds,
            Map<String, Object> layout,
            ExternalNodeSpec external) {

    }

    /** 类型化外部节点配置；只承载显式输入，不含 Session 或宿主资源。 */
    public record ExternalNodeSpec(String requirement, String query, Map<String, Object> budget,
            String agentSlot, Map<String, Object> task) {
        public ExternalNodeSpec {
            budget = Map.copyOf(budget == null ? Map.of() : budget);
            task = Map.copyOf(task == null ? Map.of() : task);
        }
        public static ExternalNodeSpec empty() { return new ExternalNodeSpec(null, null, Map.of(), null, Map.of()); }
    }

    /**
     * 工作流条件边。
     *
     * @param from            起始节点 ID
     * @param to              目标节点 ID
     * @param condition       条件表达式；为空表示无条件
     * @param priority        优先级，数值越大越优先
     * @param transferContext 跨工作流流转参数：key → 变量路径映射，进入 subgraph 节点时
     *                        插值后合并进子流程初始 state，可空
     */
    public record WorkflowEdge(String from, String to, String condition, int priority,
                               Map<String, Object> transferContext) {

    }
}
