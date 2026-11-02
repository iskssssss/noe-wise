package com.skillforge.core.workflow.execution.node;

/**
 * 工作流节点类型常量。
 *
 * <p>节点类型为 {@code WorkflowNode.type()} 上的字符串取值，引擎按类型分派到
 * 对应 {@link WorkflowNodeExecutor}；本类为全部类型的唯一真源（新增节点类型
 * 须同步注册到引擎注册表与文档）。
 */
public final class NodeTypes {

    /** LLM 生成节点。 */
    public static final String LLM = "llm";

    /** 工具调用节点（经连接器执行）。 */
    public static final String TOOL = "tool";

    /** Agentic 引擎节点。 */
    public static final String AGENTIC = "agentic";

    /** 受守卫知识查询或冻结 EvidenceRef 解析节点。 */
    public static final String KNOWLEDGE_QUERY = "knowledge_query";

    /** 人工确认节点（挂起，等待 HITL 响应）。 */
    public static final String HUMAN_INPUT = "human_input";

    /** 行动点节点（select 卡片，挂起，点选后按 action 路由）。 */
    public static final String MENU = "menu";

    /** 异步任务节点（发起挂起，非 HITL：无交互卡；完成由外部回调/超时恢复）。 */
    public static final String ASYNC_TASK = "async_task";

    /** 展示卡节点（type=info，不挂起）。 */
    public static final String CARD = "card";

    /** Docker 沙箱脚本节点。 */
    public static final String SCRIPT = "script";

    /** 赋值节点（args 为 target → source 映射）。 */
    public static final String ASSIGN = "assign";

    /** 输出消息节点（message 插值后追加助手消息）。 */
    public static final String OUTPUT = "output";

    /** 并行分支节点（fork 后按 join 边收敛）。 */
    public static final String PARALLEL = "parallel";

    /** 子工作流节点（引用另一个工作流工作流）。 */
    public static final String SUBGRAPH = "subgraph";

    private NodeTypes() {
    }
}
