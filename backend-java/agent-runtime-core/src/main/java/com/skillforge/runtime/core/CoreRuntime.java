package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec;
import java.util.Objects;

/** Core 的纯计算和协议编码入口，不持有数据库、事务、Provider 或执行循环。 */
public final class CoreRuntime {
    private final CodecLimits limits;
    private final ModelOutcomeCodec outcomes;

    /** 装配模型结果的真实业务校验器；其他结果族在 portalResults 中各自注入所属 registry。 */
    public CoreRuntime(CodecLimits limits, PayloadValidator modelOutputs) {
        this.limits = Objects.requireNonNull(limits);
        this.outcomes = new ModelOutcomeCodec(limits, Objects.requireNonNull(modelOutputs));
    }

    /** 装配唯一文档规范编码实现；宿主消费 SPI，不依赖 Core 的实现类型。 */
    public static com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec knowledgeDocuments(CodecLimits limits) {
        return new KnowledgeDocumentCodec(limits);
    }

    /** 解码原模型结果，不重新调用模型。 */
    public ModelResult decodeModelOutcome(com.skillforge.runtime.api.port.PortalOutcomeSnapshot snapshot,
            com.skillforge.runtime.api.port.PortalCallKind kind) {
        return outcomes.decode(snapshot, kind);
    }

    public PortalResults portalResults(PayloadValidator toolObservations,
                                       PayloadValidator workflowOutputs,
                                       PayloadValidator evidenceContents) {
        return new PortalResults(
                outcomes, limits, toolObservations, workflowOutputs, evidenceContents);
    }

    /**
     * 无副作用的强类型 artifact 入口，返回原 API 联合类型而非镜像 DTO。
     * 编解码不证明结果已持久化、已脱敏或被当前 owner 授权，不能据此构造调用许可或 Committed。
     */
    public static final class PortalResults implements PortalOutcomeCodec {
        private final ModelOutcomeCodec models;
        private final ToolOutcomeCodec tools;
        private final WorkflowOutcomeCodec workflows;
        private final KnowledgeOutcomeCodec knowledge;
        private final PortalOutcomeResolutionRegistry resolutions;

        /** 只能经显式装配取得入口；既有模型 codec、资源上限与独立业务 registry 均不可省略。 */
        private PortalResults(ModelOutcomeCodec models, CodecLimits limits,
                              PayloadValidator toolObservations,
                              PayloadValidator workflowOutputs,
                              PayloadValidator evidenceContents) {
            this.models = Objects.requireNonNull(models, "models");
            tools = new ToolOutcomeCodec(limits, toolObservations);
            workflows = new WorkflowOutcomeCodec(limits, workflowOutputs);
            knowledge = new KnowledgeOutcomeCodec(limits, evidenceContents);
            resolutions = new PortalOutcomeResolutionRegistry(
                    models, tools, workflows, knowledge);
        }

        /** 返回 resolved-index 与终结所需的注册语义，不证明 attempt 资格或投影 ACK。 */
        @Override
        public OutcomeSemantics semantics(
                PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind) {
            return resolutions.semantics(snapshot, expectedCallKind);
        }

        /** first-winner 永不替换；本方法只识别登记的等价与 Workflow 兼容关系。 */
        @Override
        public ResolutionRelation compareResolutions(
                PortalOutcomeSnapshot firstCommitted,
                PortalCallKind expectedFirstCallKind,
                PortalOutcomeSnapshot laterCandidate,
                PortalCallKind expectedLaterCallKind) {
            return resolutions.compare(
                    firstCommitted, expectedFirstCallKind,
                    laterCandidate, expectedLaterCallKind);
        }

        /** 模型生成与流式终态复用 CoreRuntime 已注入的模型输出 registry。 */
        @Override
        public PortalOutcomeSnapshot encodeModel(PortalCallKind kind, ModelResult result) {
            return models.encode(kind, result);
        }

        /** 模型回放同样按调用方预期 kind 严格解码，不从 snapshot 反推调用意图。 */
        @Override
        public ModelResult decodeModel(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
            return models.decode(snapshot, expectedKind);
        }

        /** Tool 的四类私有结果无损持有原 RetryMode，不签发重试权。 */
        @Override
        public PortalOutcomeSnapshot encodeTool(PortalCallKind kind, ToolOutcome result) { return tools.encode(kind, result); }

        /** 当前调用种类由读取方显式提供，不从载荷推断准入意图。 */
        @Override
        public ToolOutcome decodeTool(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) { return tools.decode(snapshot, expectedKind); }

        /** dispatch 与 resume 保持各自外层调用种类，不能因共用 body 而混用。 */
        @Override
        public PortalOutcomeSnapshot encodeWorkflow(PortalCallKind kind, WorkflowResult result) { return workflows.encodeInvoke(kind, result); }

        /** 读取完整 invoke 结果，不以 executionRef 替代已冻结结果字段。 */
        @Override
        public WorkflowResult decodeWorkflow(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) { return workflows.decodeInvoke(snapshot, expectedKind); }

        /** recover 的 Absent 是查询屏障证明结构，不是原执行成功回执。 */
        @Override
        public PortalOutcomeSnapshot encodeWorkflowRecovery(PortalCallKind kind, WorkflowRecoveredResult result) { return workflows.encodeRecover(kind, result); }

        /** 四种真实终态保留 result 包装；查询拒绝不会被转换为原执行失败。 */
        @Override
        public WorkflowRecoveredResult decodeWorkflowRecovery(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) { return workflows.decodeRecover(snapshot, expectedKind); }

        /** 取消控制结果保留完整快照，编码不等于取消已应用。 */
        @Override
        public PortalOutcomeSnapshot encodeWorkflowControl(PortalCallKind kind, WorkflowControlResult result) { return workflows.encodeControl(kind, result); }

        /** 重放、冲突和 unknown 的 API 约束由相同的注册结构读取路径检查。 */
        @Override
        public WorkflowControlResult decodeWorkflowControl(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) { return workflows.decodeControl(snapshot, expectedKind); }

        /** 完整知识证据逐项经过独立 content registry，不据此证明来源授权或结果提交。 */
        @Override
        public PortalOutcomeSnapshot encodeKnowledge(PortalCallKind kind, KnowledgeOutcome result) { return knowledge.encode(kind, result); }

        /** 读取整个冻结 bundle 与引用闭包，不用 bundleId 代替证据，也不隐式重做检索。 */
        @Override
        public KnowledgeOutcome decodeKnowledge(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) { return knowledge.decode(snapshot, expectedKind); }
    }

    /** 只编码本次实际模型返回的结果，不保存状态或消费预算。 */
    public PortalOutcomeSnapshot encodeOutcome(PortalCallKind kind, ModelResult result) { return outcomes.encode(kind, result); }

    /** 解码已保存的实际 Tool 结果；不查询、采纳或重新派发。 */
    public ToolOutcome decodeToolOutcomeForInspection(PortalOutcomeSnapshot snapshot, PortalCallKind kind,
            PayloadValidator payloads) {
        return new ToolOutcomeCodec(limits, payloads).decode(snapshot, kind);
    }

    /** 解码原知识检索结果与证据材料，不重建旧准备来源。 */
    public KnowledgeOutcome decodeKnowledgeOutcome(PortalOutcomeSnapshot snapshot, PortalCallKind kind,
            PayloadValidator payloads) {
        return new KnowledgeOutcomeCodec(limits, payloads).decode(snapshot, kind);
    }
}
