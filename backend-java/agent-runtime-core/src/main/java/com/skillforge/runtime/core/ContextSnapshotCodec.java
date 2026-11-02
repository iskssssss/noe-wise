package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import com.skillforge.runtime.core.FrozenContextSnapshot.ContextBlock;
import com.skillforge.runtime.core.FrozenContextSnapshot.ContextBudget;
import com.skillforge.runtime.core.FrozenContextSnapshot.ContextScope;
import com.skillforge.runtime.core.FrozenContextSnapshot.PersonaRef;
import com.skillforge.runtime.core.FrozenContextSnapshot.RelationshipRef;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 注册上下文快照的结构 codec；不代替 opaque payload registry、来源授权或 Session 提交验证。 */
final class ContextSnapshotCodec {
    static final String SCHEMA = "skillforge.runtime.agent.context-snapshot.v7";

    /** 无可变配置；每次解码均显式携带资源上限。 */
    private ContextSnapshotCodec() { }

    /** 编码完整快照而非引用，不包含自身摘要、permit 或调用期控制。 */
    static TypedPayload encode(FrozenContextSnapshot snapshot) {
        return new TypedPayload(SCHEMA, CanonicalJson.encode(value(snapshot)));
    }

    /** 摘要只证明字节身份，不证明策略允许或事件已落盘。 */
    static ResultRef reference(FrozenContextSnapshot snapshot) {
        return new ResultRef(SCHEMA + ":" + CanonicalJson.hash(SCHEMA, value(snapshot)));
    }

    /** 解码重走构造约束并要求精确重编码；所属 registry 必须在准备包消费边界另行验证 content。 */
    static FrozenContextSnapshot decode(TypedPayload payload, CodecLimits limits) {
        try {
            if (!SCHEMA.equals(payload.schemaId())) {
                throw new IllegalArgumentException("unknown context snapshot schema");
            }
            var root = CanonicalJson.parse(payload.canonicalJson(), limits).requireFields(
                    "operation", "contextScope", "executionRef", "capabilitySnapshot", "persona", "relationship",
                    "blocks", "evidenceRefs", "capabilityHandles", "modelRequest", "budget", "viewVersion", "callGroups", "createdAt");
            var execution = root.field("executionRef");
            var capability = root.field("capabilitySnapshot");
            var persona = root.field("persona");
            var relationship = root.field("relationship");
            var budget = root.field("budget").requireFields("maxTokens", "maxCharacters", "usedTokens", "usedCharacters");
            var snapshot = new FrozenContextSnapshot(ModelPreparationIdentity.decode(root.field("operation")),
                    ContextScope.valueOf(root.field("contextScope").stringValue()),
                    execution.isNull() ? Optional.empty() : Optional.of(decodeExecution(execution)),
                    capability.isNull() ? Optional.empty() : Optional.of(decodeCapability(capability)),
                    persona.isNull() ? Optional.empty() : Optional.of(decodePersona(persona)),
                    relationship.isNull() ? Optional.empty() : Optional.of(decodeRelationship(relationship)),
                    root.field("blocks").arrayValue().stream().map(ContextSnapshotCodec::decodeBlock).toList(),
                    root.field("evidenceRefs").arrayValue().stream().map(ContextSnapshotCodec::decodeEvidence).toList(),
                    strings(root.field("capabilityHandles")), FrozenModelRequest.decode(root.field("modelRequest")),
                    new ContextBudget(budget.field("maxTokens").longValue(), budget.field("maxCharacters").longValue(),
                            budget.field("usedTokens").longValue(), budget.field("usedCharacters").longValue()),
                    decodeViewVersion(root.field("viewVersion")), Instant.parse(root.field("createdAt").stringValue()));
            // Instant 别名等规范语义在 JSON 词法层不可见，必须按最终类型检查字节闭包。
            if (!payload.equals(encode(snapshot))) {
                throw new IllegalArgumentException("noncanonical context snapshot");
            }
            return snapshot;
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException exception) {
            // 所有预期载荷错误均脱敏；不吞掉未知系统异常，也不泄露原始引用或消息正文。
            throw new IllegalArgumentException("invalid context snapshot");
        }
    }

    /** 精确投影注册字段；列表保持冻结顺序，Optional 全部显式编码 null。 */
    private static Value value(FrozenContextSnapshot snapshot) {
        var budget = snapshot.budget();
        return CanonicalJson.object(List.of(
                new Field("operation", snapshot.operation().canonicalValue()),
                text("contextScope", snapshot.contextScope().name()),
                new Field("executionRef", snapshot.executionRef().map(ContextSnapshotCodec::execution).orElseGet(CanonicalJson::nil)),
                new Field("capabilitySnapshot", snapshot.capabilitySnapshot().map(ContextSnapshotCodec::capability).orElseGet(CanonicalJson::nil)),
                new Field("persona", snapshot.persona().map(ContextSnapshotCodec::persona).orElseGet(CanonicalJson::nil)),
                new Field("relationship", snapshot.relationship().map(ContextSnapshotCodec::relationship).orElseGet(CanonicalJson::nil)),
                new Field("blocks", CanonicalJson.array(snapshot.blocks().stream().map(ContextSnapshotCodec::block).toList())),
                new Field("evidenceRefs", CanonicalJson.array(snapshot.evidenceRefs().stream().map(ContextSnapshotCodec::evidence).toList())),
                new Field("capabilityHandles", stringArray(snapshot.capabilityHandles())),
                new Field("modelRequest", snapshot.modelRequest().canonicalValue()),
                new Field("budget", CanonicalJson.object(List.of(integer("maxTokens", budget.maxTokens()),
                        integer("maxCharacters", budget.maxCharacters()), integer("usedTokens", budget.usedTokens()),
                        integer("usedCharacters", budget.usedCharacters())))),
                new Field("viewVersion", viewVersion(snapshot.viewVersion())),
                new Field("callGroups", CanonicalJson.array(snapshot.callGroups().stream().map(group ->
                        CanonicalJson.object(List.of(new Field("messageIds", stringArray(group.messageIds())),
                                new Field("callIds", stringArray(group.callIds())),
                                new Field("sourceEventIds", stringArray(group.sourceEventIds().stream().map(EventId::value).toList()))))).toList())),
                text("createdAt", snapshot.createdAt().toString())));
    }

    private static Value viewVersion(FrozenContextSnapshot.ViewVersion version) {
        return CanonicalJson.object(List.of(text("policyRef", version.policyRef()),
                text("policyVersion", version.policyVersion()), text("rendererVersion", version.rendererVersion()),
                text("tokenizerProfileRef", version.tokenizerProfileRef()), text("measurementVersion", version.measurementVersion())));
    }

    private static FrozenContextSnapshot.ViewVersion decodeViewVersion(Value value) {
        value.requireFields("policyRef", "policyVersion", "rendererVersion", "tokenizerProfileRef", "measurementVersion");
        return new FrozenContextSnapshot.ViewVersion(value.field("policyRef").stringValue(),
                value.field("policyVersion").stringValue(), value.field("rendererVersion").stringValue(),
                value.field("tokenizerProfileRef").stringValue(), value.field("measurementVersion").stringValue());
    }

    /** 执行引用保留租户身份，不依赖外层 tenant 默认补齐。 */
    static Value execution(ExecutionRef ref) {
        return CanonicalJson.object(List.of(text("tenantId", ref.tenantId().value()), text("executionId", ref.executionId())));
    }

    /** SessionRef 按 API 组件编码；内部各嵌套引用都使用相同规则。 */
    private static Value session(SessionRef ref) {
        return CanonicalJson.object(List.of(text("tenantId", ref.tenantId().value()), text("sessionId", ref.sessionId())));
    }

    /** 能力引用包含原 input 与 generation，不能只保存 snapshotId。 */
    static Value capability(CapabilitySnapshotRef ref) {
        return CanonicalJson.object(List.of(new Field("sessionRef", session(ref.sessionRef())),
                text("inputId", ref.inputId().value()), text("snapshotId", ref.snapshotId()), integer("generation", ref.generation())));
    }

    /** 人格只保留归属与版本，不展开业务内容。 */
    private static Value persona(PersonaRef ref) {
        return CanonicalJson.object(List.of(text("tenantId", ref.tenantId().value()), text("agentId", ref.agentId().value()), text("version", ref.version())));
    }

    /** 用户身份和 revision 都以十进制字符串编码，避免超过 2^53 时损失精度。 */
    private static Value relationship(RelationshipRef ref) {
        return CanonicalJson.object(List.of(text("tenantId", ref.tenantId().value()), integer("userId", ref.userId().value()),
                text("agentId", ref.agentId().value()), text("schemaId", ref.schemaId()), text("schemaVersion", ref.schemaVersion()),
                integer("revision", ref.revision())));
    }

    /** 每块完整保存来源、顺序和费用；opaque 内容是字符串，不插入未经本 codec 解析的节点。 */
    private static Value block(ContextBlock block) {
        return CanonicalJson.object(List.of(text("blockId", block.blockId()), text("type", block.type()), text("providerId", block.providerId()),
                new Field("sourceEventIds", stringArray(block.sourceEventIds().stream().map(EventId::value).toList())),
                text("scope", block.scope()), text("trustLevel", block.trustLevel()),
                new Field("content", CanonicalJson.object(List.of(text("schemaId", block.content().schemaId()),
                        text("canonicalJson", block.content().canonicalJson())))),
                integer("priority", block.priority()), integer("sequence", block.sequence()), integer("tokenCost", block.tokenCost()),
                integer("charCost", block.charCost()), new Field("messageIds", stringArray(block.messageIds()))));
    }

    /** EvidenceRef 必须完整绑定来源事件与版本，itemIds 按 API List 保序。 */
    private static Value evidence(EvidenceRef ref) {
        return CanonicalJson.object(List.of(new Field("sessionRef", session(ref.sessionRef())), text("bundleId", ref.bundleId()),
                new Field("itemIds", stringArray(ref.itemIds())), integer("version", ref.version()), text("sourceEventId", ref.sourceEventId().value())));
    }

    /** 拒绝执行引用中的未知字段与缺省租户。 */
    static ExecutionRef decodeExecution(Value value) {
        value.requireFields("tenantId", "executionId");
        return new ExecutionRef(new TenantId(value.field("tenantId").stringValue()), value.field("executionId").stringValue());
    }

    /** 不从 operation 推导嵌套 SessionRef；调用方构造器会核对归属一致性。 */
    private static SessionRef decodeSession(Value value) {
        value.requireFields("tenantId", "sessionId");
        return new SessionRef(new TenantId(value.field("tenantId").stringValue()), value.field("sessionId").stringValue());
    }

    /** 能力 generation 精确恢复为 long，再由 API 检查正值。 */
    static CapabilitySnapshotRef decodeCapability(Value value) {
        value.requireFields("sessionRef", "inputId", "snapshotId", "generation");
        return new CapabilitySnapshotRef(decodeSession(value.field("sessionRef")), new InputId(value.field("inputId").stringValue()),
                value.field("snapshotId").stringValue(), value.field("generation").longValue());
    }

    /** 人格引用只允许精确字段集。 */
    private static PersonaRef decodePersona(Value value) {
        value.requireFields("tenantId", "agentId", "version");
        return new PersonaRef(new TenantId(value.field("tenantId").stringValue()), new AgentId(value.field("agentId").stringValue()),
                value.field("version").stringValue());
    }

    /** 关系引用保留 schema 的独立版本与 revision，不将二者合并或默认化。 */
    private static RelationshipRef decodeRelationship(Value value) {
        value.requireFields("tenantId", "userId", "agentId", "schemaId", "schemaVersion", "revision");
        return new RelationshipRef(new TenantId(value.field("tenantId").stringValue()), new EndUserId(value.field("userId").longValue()),
                new AgentId(value.field("agentId").stringValue()), value.field("schemaId").stringValue(),
                value.field("schemaVersion").stringValue(), value.field("revision").longValue());
    }

    /** 解析每块的字段闭包；跨块重复、消息覆盖与预算加和由快照构造器集中检查。 */
    private static ContextBlock decodeBlock(Value value) {
        value.requireFields("blockId", "type", "providerId", "sourceEventIds", "scope", "trustLevel", "content",
                "priority", "sequence", "tokenCost", "charCost", "messageIds");
        var content = value.field("content").requireFields("schemaId", "canonicalJson");
        return new ContextBlock(value.field("blockId").stringValue(), value.field("type").stringValue(), value.field("providerId").stringValue(),
                strings(value.field("sourceEventIds")).stream().map(EventId::new).toList(), value.field("scope").stringValue(),
                value.field("trustLevel").stringValue(), new TypedPayload(content.field("schemaId").stringValue(), content.field("canonicalJson").stringValue()),
                value.field("priority").intValue(), value.field("sequence").intValue(), value.field("tokenCost").longValue(),
                value.field("charCost").longValue(), strings(value.field("messageIds")));
    }

    /** 不重查知识源，完整恢复原 EvidenceRef 后由消费边界验证其已提交且已授权。 */
    private static EvidenceRef decodeEvidence(Value value) {
        value.requireFields("sessionRef", "bundleId", "itemIds", "version", "sourceEventId");
        return new EvidenceRef(decodeSession(value.field("sessionRef")), value.field("bundleId").stringValue(),
                strings(value.field("itemIds")), value.field("version").longValue(), new EventId(value.field("sourceEventId").stringValue()));
    }

    /** 文本数组逐元素检查类型，绝不靠 toString 转换非字符串输入。 */
    private static List<String> strings(Value value) {
        return value.arrayValue().stream().map(Value::stringValue).toList();
    }

    /** 仅封装显式字符串字段，不提供原始 JSON 拼接入口。 */
    private static Field text(String name, String value) {
        return new Field(name, CanonicalJson.string(value));
    }

    /** 全部整数使用同一精确十进制字符串规则。 */
    private static Field integer(String name, long value) {
        return new Field(name, CanonicalJson.integer(value));
    }

    /** 保留原始列表顺序，调用者负责决定哪些身份不允许重复。 */
    private static Value stringArray(List<String> values) {
        return CanonicalJson.array(values.stream().map(CanonicalJson::string).toList());
    }
}
