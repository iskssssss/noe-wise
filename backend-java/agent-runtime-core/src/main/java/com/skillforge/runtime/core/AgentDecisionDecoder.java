package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.KnowledgeOperation;
import com.skillforge.runtime.api.KnowledgeOperationLimits;
import com.skillforge.runtime.api.ToolCallLimits;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 七动作唯一当前 schema 的纯 decoder/快照 codec；不访问持久端口，也不将解析成功当作动作放行。 */
public final class AgentDecisionDecoder {
    static final String SCHEMA = "skillforge.runtime.agent-decision.v1";
    private final CodecLimits limits;

    /** 解析资源上限由宿主显式提供，不以默认无限制接纳模型输入。 */
    public AgentDecisionDecoder(CodecLimits limits) { this.limits = Objects.requireNonNull(limits, "limits"); }

    /** 标准 JSON 工具参数只在可信入境规范化；同配额校验 object，不放宽任何持久快照读取。 */
    public TypedPayload decodeToolArgumentsDocument(TypedPayload document) {
        if (document == null || !"application/json".equals(document.schemaId())) {
            throw new IllegalArgumentException("invalid_tool_arguments_document");
        }
        Value value = CanonicalJson.parseDocument(document.canonicalJson(), limits);
        value.objectFields();
        String canonical = CanonicalJson.encode(value);
        // 标准数字/转义的规范表示可能变长，输出仍须满足宿主提供的同一配额。
        CanonicalJson.parse(canonical, limits);
        return new TypedPayload("application/json", canonical);
    }

    /** 原始模型只允许当前 JSON 文档；标准布局可归一化，文本前缀、旧字段和非字符串整数均拒绝。 */
    public Result decodeWire(String wire) {
        if (wire == null) { return new Failure(Reason.INVALID_DECISION); }
        try {
            return decision(CanonicalJson.parseDocument(wire, limits));
        } catch (PlanDecisionCodec.SelfReplacementRejected rejected) {
            return new Failure(Reason.PLAN_TASK_SELF_REPLACEMENT);
        } catch (IllegalArgumentException exception) {
            // 预期语法/schema/值约束失败只携带固定原因，不保存输入、原异常或反射对象。
            return new Failure(Reason.INVALID_DECISION);
        }
    }

    /** 持久快照只允许精确 schema 与完整规范表示；不因损坏而退回原始 wire 或重新查询当前配置。 */
    public Result decodeSnapshot(TypedPayload payload) {
        if (payload == null || !SCHEMA.equals(payload.schemaId())) { return new Failure(Reason.UNSUPPORTED_SCHEMA); }
        try {
            var decoded = decision(CanonicalJson.parse(payload.canonicalJson(), limits));
            if (decoded instanceof Failure failure) return failure;
            // 规范 JSON 还须匹配类型化快照的 presence 规则，例如所有可选列表都显式写出。
            if (!encodeResult(decoded).equals(payload)) { return new Failure(Reason.INVALID_DECISION); }
            return decoded;
        } catch (PlanDecisionCodec.SelfReplacementRejected rejected) {
            return new Failure(Reason.PLAN_TASK_SELF_REPLACEMENT);
        } catch (IllegalArgumentException exception) {
            return new Failure(Reason.INVALID_DECISION);
        }
    }

    /** 原生多调用只形成有序工具决定，不允许混合控制动作或嵌套批次。 */
    private static Result decision(Value root) {
        if (root.optionalField("type").map(AgentDecisionDecoder::text).filter("decision_rejected"::equals).isPresent()) {
            root.requireFields("type", "reason");
            if ("CONTROL_ACTION_REQUIRES_SINGLE_CALL".equals(text(root, "reason"))) {
                return new Failure(Reason.CONTROL_ACTION_REQUIRES_SINGLE_CALL);
            }
            if ("TOOL_NOT_AVAILABLE".equals(text(root, "reason"))) {
                return new Failure(Reason.TOOL_NOT_AVAILABLE);
            }
            if ("TOOL_ARGUMENTS_INVALID_JSON".equals(text(root, "reason")))
                return new Failure(Reason.TOOL_ARGUMENTS_INVALID_JSON);
            if ("TOOL_ARGUMENTS_NOT_OBJECT".equals(text(root, "reason")))
                return new Failure(Reason.TOOL_ARGUMENTS_NOT_OBJECT);
            if ("CONTROL_ARGUMENTS_FIELDS_INVALID".equals(text(root, "reason"))) return new Failure(Reason.CONTROL_ARGUMENTS_FIELDS_INVALID);
            if ("CONTROL_COMMENTARY_INVALID".equals(text(root, "reason"))) return new Failure(Reason.CONTROL_COMMENTARY_INVALID);
            if ("CONTROL_ARGUMENTS_INVALID".equals(text(root, "reason"))) return new Failure(Reason.CONTROL_ARGUMENTS_INVALID);
            if ("PLAN_TASK_SELF_REPLACEMENT".equals(text(root, "reason"))) return new Failure(Reason.PLAN_TASK_SELF_REPLACEMENT);
            return new Failure(Reason.INVALID_DECISION);
        }
        var commentary = root.optionalField("commentary").map(AgentDecisionDecoder::text);
        root = CanonicalJson.object(root.objectFields().stream().filter(field -> !"commentary".equals(field.name())).toList());
        if (!"tool_batch".equals(text(root, "type"))) return new Decoded(action(root), commentary);
        root.requireFields("type", "calls");
        return new DecodedBatch(root.field("calls").arrayValue().stream().map(value -> {
            var decoded = action(value);
            if (!(decoded instanceof AgentAction.ToolCall tool)) throw new IllegalArgumentException("invalid_batch_member");
            return tool;
        }).toList(), commentary);
    }

    public TypedPayload encodeResult(Result result) {
        if (result instanceof Decoded decoded) return withCommentary(encode(decoded.action()), decoded.commentary());
        if (!(result instanceof DecodedBatch batch)) throw new IllegalArgumentException("cannot_encode_failure");
        var value = object(text("type", "tool_batch"), new Field("calls", CanonicalJson.array(batch.calls().stream()
                .map(call -> CanonicalJson.parse(encode(call).canonicalJson(), limits)).toList())));
        var json = CanonicalJson.encode(value);
        CanonicalJson.parse(json, limits);
        return withCommentary(new TypedPayload(SCHEMA, json), batch.commentary());
    }

    /** 说明随同一个已校验决策冻结，不创建额外动作或模型迭代。 */
    private TypedPayload withCommentary(TypedPayload payload, Optional<String> commentary) {
        if (commentary.isEmpty()) return payload;
        var fields = new java.util.ArrayList<>(CanonicalJson.parse(payload.canonicalJson(), limits).objectFields());
        fields.add(text("commentary", commentary.orElseThrow()));
        var json = CanonicalJson.encode(CanonicalJson.object(fields));
        CanonicalJson.parse(json, limits);
        return new TypedPayload(SCHEMA, json);
    }

    /** 无副作用地生成注册快照；大小/节点/深度仍受同一配额约束，不生成未经校验的 raw fragment。 */
    public TypedPayload encode(AgentAction action) {
        Objects.requireNonNull(action, "action");
        var value = switch (action) {
            case AgentAction.Respond response -> object(text("type", "respond"), text("output", response.output()),
                    new Field("evidence_item_ids", strings(response.evidenceItemIds())));
            case AgentAction.Dispatch dispatch -> object(text("type", "dispatch"), text("handle", dispatch.handle()));
            case AgentAction.ToolCall tool -> object(text("type", "tool_call"), text("tool_handle", tool.toolHandle()),
                    new Field("arguments", argumentObject(tool.arguments())));
            case AgentAction.ActivateSkill skill -> object(text("type", "activate_skill"), text("skill_handle", skill.skillHandle()));
            case AgentAction.ReadSkillResource resource -> object(text("type", "read_skill_resource"),
                    text("skill_handle", resource.skillHandle()), text("path", resource.path()));
            case AgentAction.KnowledgeQuery query -> object(text("type", "knowledge_query"),
                    text("knowledge_handle", query.knowledgeHandle()), text("query", query.query()));
            case AgentAction.KnowledgeListDocuments list -> object(text("type", "knowledge_list_documents"), text("knowledge_handle", list.knowledgeHandle()),
                    new Field("title_filter", CanonicalJson.string(list.operation().titleFilter())), new Field("page_cursor", CanonicalJson.string(list.operation().cursor().orElse(""))),
                    new Field("page_size", CanonicalJson.number(Integer.toString(list.operation().pageSize()))));
            case AgentAction.KnowledgeReadDocument read -> object(text("type", "knowledge_read_document"), text("knowledge_handle", read.knowledgeHandle()),
                    text("source_id", read.operation().sourceId()), text("document_id", read.operation().documentId()), text("source_version", read.operation().sourceVersion()),
                    new Field("offset", CanonicalJson.number(Integer.toString(read.operation().offset()))), new Field("max_characters", CanonicalJson.number(Integer.toString(read.operation().maxCharacters()))));
            case AgentAction.AskUser ask -> object(text("type", "ask_user"), text("question", ask.question()));
            case AgentAction.Delegate delegate -> delegation(delegate.command());
            case AgentAction.Plan plan -> PlanDecisionCodec.encode(plan.command());
        };
        var json = CanonicalJson.encode(value);
        CanonicalJson.parse(json, limits);
        return new TypedPayload(SCHEMA, json);
    }

    /** discriminator 决定封闭字段集；无通用 params、Map 或未知动作 fallback。 */
    private static AgentAction action(Value root) {
        return switch (text(root, "type")) {
            case "respond" -> {
                root.requireMembers(List.of("type", "output"), List.of("evidence_item_ids"));
                yield new AgentAction.Respond(text(root, "output"), optionalArray(root, "evidence_item_ids").stream().map(AgentDecisionDecoder::text).toList());
            }
            case "dispatch" -> { root.requireFields("type", "handle"); yield new AgentAction.Dispatch(text(root, "handle")); }
            case "tool_call" -> {
                root.requireFields("type", "tool_handle", "arguments");
                Value arguments = root.field("arguments");
                arguments.objectFields();
                yield new AgentAction.ToolCall(text(root, "tool_handle"),
                        new TypedPayload("application/json", CanonicalJson.encode(arguments)));
            }
            case "activate_skill" -> { root.requireFields("type", "skill_handle"); yield new AgentAction.ActivateSkill(text(root, "skill_handle")); }
            case "read_skill_resource" -> { root.requireFields("type", "skill_handle", "path");
                yield new AgentAction.ReadSkillResource(text(root, "skill_handle"), text(root, "path")); }
            case "knowledge_query" -> { root.requireFields("type", "knowledge_handle", "query");
                yield new AgentAction.KnowledgeQuery(text(root, "knowledge_handle"), text(root, "query")); }
            case "knowledge_list_documents" -> {
                root.requireMembers(List.of("type", "knowledge_handle"), List.of("title_filter", "page_cursor", "page_size"));
                String cursor = root.optionalField("page_cursor").map(Value::stringValue).orElse("");
                yield new AgentAction.KnowledgeListDocuments(text(root, "knowledge_handle"), new KnowledgeOperation.ListDocuments(
                        root.optionalField("title_filter").map(Value::stringValue).orElse(""), cursor.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(cursor), optionalInteger(root, "page_size", KnowledgeOperationLimits.DEFAULT_PAGE_SIZE)));
            }
            case "knowledge_read_document" -> {
                root.requireMembers(List.of("type", "knowledge_handle", "source_id", "document_id", "source_version"), List.of("offset", "max_characters"));
                yield new AgentAction.KnowledgeReadDocument(text(root, "knowledge_handle"), new KnowledgeOperation.ReadDocument(
                        text(root, "source_id"), text(root, "document_id"), text(root, "source_version"), optionalInteger(root, "offset", KnowledgeOperationLimits.DEFAULT_READ_OFFSET), optionalInteger(root, "max_characters", KnowledgeOperationLimits.DEFAULT_READ_CHARACTERS)));
            }
            case "ask_user" -> { root.requireFields("type", "question"); yield new AgentAction.AskUser(text(root, "question")); }
            case "plan" -> new AgentAction.Plan(PlanDecisionCodec.decode(root));
            case "delegate" -> new AgentAction.Delegate(decodeDelegation(root));
            default -> throw new IllegalArgumentException("unknown decision type");
        };
    }

    /** 可选列表只在缺失时为空；显式 null、字符串或对象均由 arrayValue 拒绝。 */
    private static List<Value> optionalArray(Value root, String name) {
        return root.optionalField(name).map(Value::arrayValue).orElseGet(List::of);
    }
    private static int optionalInteger(Value root, String field, int fallback) {
        return root.optionalField(field).map(value -> {
            String encoded = CanonicalJson.encode(value);
            if (!encoded.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("decision integer invalid");
            return Integer.parseInt(encoded);
        }).orElse(fallback);
    }

    /** 所有输入业务文本都必须非空白；不将数字/boolean 强转或修剪合法文本。 */
    private static String text(Value value) {
        var text = value.stringValue();
        if (text.isBlank()) { throw new IllegalArgumentException("blank decision text"); }
        return text;
    }

    /** 必填文本缺失与值类型错误都在当前 schema 层失败。 */
    private static String text(Value root, String name) { return text(root.field(name)); }

    private static com.skillforge.runtime.api.port.DelegationCommand decodeDelegation(Value root) {
        var operation = com.skillforge.runtime.api.port.DelegationCommand.Operation.valueOf(text(root, "operation").toUpperCase(java.util.Locale.ROOT));
        switch (operation) {
            case START -> root.requireFields("type", "operation", "target_handle", "task", "background");
            case TARGETS, LIST -> root.requireFields("type", "operation");
            case SEND -> root.requireFields("type", "operation", "delegation_ids", "message");
            case GET, WAIT, CANCEL -> root.requireFields("type", "operation", "delegation_ids");
        }
        var task = root.optionalField("task").map(AgentDecisionDecoder::decodeDelegationTask);
        return new com.skillforge.runtime.api.port.DelegationCommand(operation,
                root.optionalField("target_handle").map(Value::stringValue).orElse(""), task,
                optionalArray(root, "delegation_ids").stream().map(AgentDecisionDecoder::text).toList(),
                root.optionalField("message").map(Value::stringValue).orElse(""),
                operation != com.skillforge.runtime.api.port.DelegationCommand.Operation.START || root.field("background").booleanValue());
    }

    private static com.skillforge.runtime.api.port.DelegationCommand.Task decodeDelegationTask(Value value) {
        value.requireFields("objective", "context", "constraints", "expected_output");
        return new com.skillforge.runtime.api.port.DelegationCommand.Task(text(value, "objective"),
                value.field("context").stringValue(),
                value.field("constraints").arrayValue().stream().map(AgentDecisionDecoder::text).toList(),
                value.field("expected_output").stringValue());
    }

    private static Value delegation(com.skillforge.runtime.api.port.DelegationCommand command) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        fields.add(text("type", "delegate")); fields.add(text("operation", command.operation().name().toLowerCase(java.util.Locale.ROOT)));
        if (command.operation() == com.skillforge.runtime.api.port.DelegationCommand.Operation.START) fields.add(new Field("background", CanonicalJson.bool(command.background())));
        if (!command.targetHandle().isEmpty()) fields.add(text("target_handle", command.targetHandle()));
        command.task().ifPresent(task -> fields.add(new Field("task", delegationTask(task))));
        if (!command.delegationIds().isEmpty()) fields.add(new Field("delegation_ids", strings(command.delegationIds())));
        if (!command.message().isEmpty()) fields.add(text("message", command.message()));
        return CanonicalJson.object(fields);
    }

    private static Value delegationTask(com.skillforge.runtime.api.port.DelegationCommand.Task task) {
        return object(text("objective", task.objective()), new Field("context", CanonicalJson.string(task.context())),
                new Field("constraints", strings(task.constraints())), new Field("expected_output", CanonicalJson.string(task.expectedOutput())));
    }

    /** 文本输出同样验证非空白，阻止散装 API 对象绕过 decision 文本契约。 */
    private static Field text(String name, String value) {
        if (value.isBlank()) { throw new IllegalArgumentException("blank decision text"); }
        return new Field(name, CanonicalJson.string(value));
    }
    /** 不把 long 经过 double 或字符串格式器。 */
    private static Field integer(String name, long value) { return new Field(name, CanonicalJson.integer(value)); }
    /** 原生工具 Schema 的 integer 使用 JSON number，同时仍禁止 double 精度往返。 */
    private static Field jsonInteger(String name, long value) { return new Field(name, CanonicalJson.number(Long.toString(value))); }
    /** 类型化成员由有界原语排序和转义，禁止拼接 raw JSON。 */
    private static Value object(Field... fields) { return CanonicalJson.object(List.of(fields)); }
    /** 字符串列表保持原顺序，元素约束由 Action/API 构造器保证。 */
    private static Value strings(List<String> strings) { return CanonicalJson.array(strings.stream().map(CanonicalJson::string).toList()); }

    /** arguments 以 JSON object 直接进入 decision wire，不包成可由模型改写 schemaId 的通用 payload。 */
    private Value argumentObject(TypedPayload arguments) {
        if (!"application/json".equals(arguments.schemaId())) { throw new IllegalArgumentException("invalid tool arguments schema"); }
        Value value = CanonicalJson.parse(arguments.canonicalJson(), limits);
        value.objectFields();
        return value;
    }

    /** 解码结果既不是 Observation 提交证明，也不是 Loop 的持久状态。 */
    public sealed interface Result permits Decoded, DecodedBatch, Failure {
        default Optional<String> commentary() { return Optional.empty(); }
    }
    /** 类型化动作仅供后续预算、能力、重复与策略检查。 */
    public record Decoded(AgentAction action, Optional<String> commentary) implements Result {
        /** 不允许空动作伪装成功。 */
        public Decoded {
            Objects.requireNonNull(action, "action");
            commentary = Objects.requireNonNull(commentary).map(AgentDecisionDecoder::commentaryText);
            if (commentary.isPresent() && (action instanceof AgentAction.Respond || action instanceof AgentAction.AskUser))
                throw new IllegalArgumentException("terminal_action_commentary");
        }
        public Decoded(AgentAction action) { this(action, Optional.empty()); }
        /** 即便只有 handle，也不默认打印身份。 */
        @Override public String toString() { return "DecodedAgentAction[REDACTED]"; }
    }
    public record DecodedBatch(List<AgentAction.ToolCall> calls, Optional<String> commentary) implements Result {
        public DecodedBatch {
            calls = List.copyOf(calls);
            commentary = Objects.requireNonNull(commentary).map(AgentDecisionDecoder::commentaryText);
            if (calls.size() < ToolCallLimits.MIN_READ_ONLY_BATCH
                    || calls.size() > ToolCallLimits.READ_ONLY_BATCH) throw new IllegalArgumentException("invalid_tool_batch_size");
        }
        public DecodedBatch(List<AgentAction.ToolCall> calls) { this(calls, Optional.empty()); }
        @Override public String toString() { return "DecodedToolBatch[REDACTED]"; }
    }

    private static String commentaryText(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("blank_commentary");
        CanonicalJson.string(value);
        return value;
    }

    /** 失败由 Loop 后续形成审计事实并在预算内处理，不在 decoder 内重试模型。 */
    public record Failure(Reason reason) implements Result {
        /** 只允许稳定的封闭原因，不保存异常或原文。 */
        public Failure { Objects.requireNonNull(reason, "reason"); }
    }
    /** 区分版本不支持与当前版本内容损坏，二者均不回退旧 parser。 */
    public enum Reason { INVALID_DECISION, UNSUPPORTED_SCHEMA, CONTROL_ACTION_REQUIRES_SINGLE_CALL, TOOL_NOT_AVAILABLE, ANSWER_LENGTH_EXCEEDED, TOOL_ARGUMENTS_INVALID_JSON, TOOL_ARGUMENTS_NOT_OBJECT, CONTROL_ARGUMENTS_FIELDS_INVALID, CONTROL_COMMENTARY_INVALID, CONTROL_ARGUMENTS_INVALID, PLAN_TASK_SELF_REPLACEMENT }
}
