package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.TypedPayload;
import java.util.List;
import java.util.Objects;

/** 七种无副作用的决策值；构造成功不是模型结果提交、能力可见性、引用存在或 Policy 授权证明。 */
public sealed interface AgentAction permits AgentAction.Respond, AgentAction.Dispatch, AgentAction.Delegate,
        AgentAction.Plan, AgentAction.ToolCall, AgentAction.ActivateSkill, AgentAction.ReadSkillResource,
        AgentAction.KnowledgeAccess, AgentAction.AskUser {

    sealed interface KnowledgeAccess extends AgentAction permits KnowledgeQuery, KnowledgeListDocuments, KnowledgeReadDocument {
        String knowledgeHandle();
        com.skillforge.runtime.api.KnowledgeOperation operation();
    }

    /** 输出须在引用提交后发布；仅持有模型建议的正文与引用列表。 */
    public record Respond(String output, List<String> evidenceItemIds) implements AgentAction {
        /** 文本不 trim，重复引用不静默合并，空列表仍与缺失引用事实区分。 */
        public Respond { output = text(output); evidenceItemIds = texts(evidenceItemIds); }
        /** 用户正文不进入默认日志。 */
        @Override public String toString() { return "RespondAction[REDACTED]"; }
    }
    /** Workflow 输入由 Runtime 使用原用户输入，模型只能选冻结 handle。 */
    public record Dispatch(String handle) implements AgentAction {
        /** 不接收模型注入的 params 或执行身份。 */
        public Dispatch { handle = text(handle); }
        /** 能力身份不进入默认日志。 */
        @Override public String toString() { return "DispatchAction[REDACTED]"; }
    }
    /** 原生委派操作由 Runtime 解析目录、冻结身份并持久受理；模型不能指定配置或预算。 */
    public record Delegate(com.skillforge.runtime.api.port.DelegationCommand command) implements AgentAction {
        public Delegate { Objects.requireNonNull(command, "command"); }
        @Override public String toString() { return "DelegateAction[REDACTED]"; }
    }
    /** 通用计划不依赖委派能力。 */
    record Plan(com.skillforge.runtime.api.port.PlanCommand command) implements AgentAction {
        public Plan { Objects.requireNonNull(command); }
    }
    /** Tool 选择携带标准结构化参数；句柄仍是唯一可执行身份。 */
    public record ToolCall(String toolHandle, TypedPayload arguments) implements AgentAction {
        /** 参数必须是规范 JSON object；具体字段仍由冻结 Tool inputSchema 在 Portal 前校验。 */
        public ToolCall {
            toolHandle = text(toolHandle);
            arguments = toolArguments(arguments);
        }
        /** 能力身份不进入默认日志。 */
        @Override public String toString() { return "ToolCallAction[REDACTED]"; }
    }
    /** Skill 激活只影响当前 Loop 上下文，不是 Tool 或 Workflow 调用。 */
    public record ActivateSkill(String skillHandle) implements AgentAction {
        /** 不补齐缺失句柄或默认 Skill。 */
        public ActivateSkill { skillHandle = text(skillHandle); }
        /** 能力身份不进入默认日志。 */
        @Override public String toString() { return "ActivateSkillAction[REDACTED]"; }
    }
    /** Knowledge 选择已授权句柄及有界检索文本；来源、算子和预算仍由冻结配置决定。 */
    public record KnowledgeQuery(String knowledgeHandle, String query) implements KnowledgeAccess {
        public static final int MAX_QUERY_CHARACTERS = 512;
        /** 不截断查询，非法或超长输入在决策边界拒绝。 */
        public KnowledgeQuery {
            knowledgeHandle = text(knowledgeHandle);
            query = text(query);
            if (query.codePointCount(0, query.length()) > MAX_QUERY_CHARACTERS) {
                throw new IllegalArgumentException("knowledge query too long");
            }
        }
        /** 能力身份不进入默认日志。 */
        @Override public String toString() { return "KnowledgeQueryAction[REDACTED]"; }
        @Override public com.skillforge.runtime.api.KnowledgeOperation.Search operation() { return new com.skillforge.runtime.api.KnowledgeOperation.Search(query); }
    }
    record KnowledgeListDocuments(String knowledgeHandle, com.skillforge.runtime.api.KnowledgeOperation.ListDocuments operation) implements KnowledgeAccess {
        public KnowledgeListDocuments { knowledgeHandle = text(knowledgeHandle); Objects.requireNonNull(operation); }
        @Override public String toString() { return "KnowledgeListDocuments[REDACTED]"; }
    }
    record KnowledgeReadDocument(String knowledgeHandle, com.skillforge.runtime.api.KnowledgeOperation.ReadDocument operation) implements KnowledgeAccess {
        public KnowledgeReadDocument { knowledgeHandle = text(knowledgeHandle); Objects.requireNonNull(operation); }
        @Override public String toString() { return "KnowledgeReadDocument[REDACTED]"; }
    }
    /** 请求由 Session 投影为受控 HITL，不能直接建立渠道交互。 */
    public record AskUser(String question) implements AgentAction {
        /** 问题不能为空，也不由 decoder 补写文案。 */
        public AskUser { question = text(question); }
        /** 用户问题不进入默认日志。 */
        @Override public String toString() { return "AskUserAction[REDACTED]"; }
    }

    /** 所有业务文本拒绝 null、空白和非法 Unicode，保留合法内容中的空格与原 Unicode 形式。 */
    private static String text(String value) {
        Objects.requireNonNull(value, "decision text");
        if (value.isBlank()) { throw new IllegalArgumentException("blank decision text"); }
        CanonicalJson.string(value);
        return value;
    }

    /** 文本列表防御性复制并保序；不以 Set 去重掩盖无效模型输出。 */
    private static List<String> texts(List<String> values) {
        var frozen = List.copyOf(values).stream().map(AgentAction::text).toList();
        if (frozen.stream().distinct().count() != frozen.size()) { throw new IllegalArgumentException("duplicate decision text"); }
        return frozen;
    }
    /** 只读取已激活、冻结版本内的资源；path 不是宿主文件系统路径。 */
    public record ReadSkillResource(String skillHandle, String path) implements AgentAction {
        public ReadSkillResource { skillHandle = text(skillHandle); path = text(path); }
        @Override public String toString() { return "ReadSkillResourceAction[REDACTED]"; }
    }

    /** 防止散装 Core 调用绕过 decoder 注入非对象或另一种参数 schema。 */
    private static TypedPayload toolArguments(TypedPayload value) {
        Objects.requireNonNull(value, "arguments");
        if (!"application/json".equals(value.schemaId())) {
            throw new IllegalArgumentException("tool arguments must use application/json");
        }
        int length = Math.max(1, value.canonicalJson().length());
        var parsed=CanonicalJson.parse(value.canonicalJson(), new CodecLimits(length, length, 128));
        parsed.objectFields();
        return new TypedPayload("application/json",CanonicalJson.encode(parsed));
    }
}
