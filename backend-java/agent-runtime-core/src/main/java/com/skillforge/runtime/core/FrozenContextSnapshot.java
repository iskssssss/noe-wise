package com.skillforge.runtime.core;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 冻结实际模型输入及可重建来源；构造器只验证字段闭包，不伪造来源已提交或 Policy 授权。 */
public record FrozenContextSnapshot(
        ModelPreparationIdentity operation,
        ContextScope contextScope,
        Optional<ExecutionRef> executionRef,
        Optional<CapabilitySnapshotRef> capabilitySnapshot,
        Optional<PersonaRef> persona,
        Optional<RelationshipRef> relationship,
        List<ContextBlock> blocks,
        List<EvidenceRef> evidenceRefs,
        List<String> capabilityHandles,
        FrozenModelRequest modelRequest,
        ContextBudget budget,
        ViewVersion viewVersion,
        Instant createdAt) {

    /** 所有显式范围、引用、顺序和费用必须一致；不查询 current registry 或补齐缺失身份。 */
    public FrozenContextSnapshot {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(contextScope, "contextScope");
        Objects.requireNonNull(executionRef, "executionRef");
        Objects.requireNonNull(capabilitySnapshot, "capabilitySnapshot");
        Objects.requireNonNull(persona, "persona");
        Objects.requireNonNull(relationship, "relationship");
        blocks = List.copyOf(blocks);
        evidenceRefs = List.copyOf(evidenceRefs);
        capabilityHandles = List.copyOf(capabilityHandles);
        Objects.requireNonNull(modelRequest, "modelRequest");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(viewVersion, "viewVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        var owner = operation.runIdentity().owner();
        TenantId tenant = owner.sessionRef().tenantId();
        if (!modelRequest.target().binding().tenantId().equals(tenant)) {
            throw new IllegalArgumentException("model target tenant must match context owner");
        }
        if (executionRef.isPresent() && !executionRef.orElseThrow().tenantId().equals(tenant)) {
            throw new IllegalArgumentException("execution tenant must match context owner");
        }
        if (contextScope == ContextScope.AGENT_INPUT) {
            CapabilitySnapshotRef snapshot = capabilitySnapshot.orElseThrow(
                    () -> new IllegalArgumentException("Agent context requires capability snapshot"));
            if (!snapshot.sessionRef().equals(owner.sessionRef())
                    || !snapshot.inputId().equals(operation.runIdentity().causationInputId())) {
                throw new IllegalArgumentException("capability snapshot must match context input and session");
            }
        } else if (executionRef.isEmpty() || capabilitySnapshot.isPresent() || persona.isPresent()
                || relationship.isPresent() || !evidenceRefs.isEmpty() || !capabilityHandles.isEmpty()) {
            throw new IllegalArgumentException("restricted Workflow context must not carry Agent-only facts");
        }
        if (persona.isPresent() && (!persona.orElseThrow().tenantId().equals(tenant)
                || !persona.orElseThrow().agentId().equals(operation.runIdentity().agentId()))) {
            throw new IllegalArgumentException("persona must match context owner");
        }
        if (relationship.isPresent() && (!relationship.orElseThrow().tenantId().equals(tenant)
                || !relationship.orElseThrow().agentId().equals(operation.runIdentity().agentId())
                || !(owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User user)
                || !relationship.orElseThrow().userId().equals(user.endUserId()))) {
            throw new IllegalArgumentException("relationship must match context owner");
        }
        if (new HashSet<>(evidenceRefs).size() != evidenceRefs.size()) {
            throw new IllegalArgumentException("duplicate context evidence reference");
        }
        for (EvidenceRef evidence : evidenceRefs) {
            if (!evidence.sessionRef().equals(owner.sessionRef())) {
                throw new IllegalArgumentException("evidence must belong to context session");
            }
        }
        requireDistinctText(capabilityHandles);
        if (blocks.isEmpty() || modelRequest.messages().isEmpty()) {
            throw new IllegalArgumentException("context must contain sourced model messages");
        }
        // 仅使用方法内索引核对最终消息，不把 Map 作为边界对象、codec 或持久权威。
        var messagesById = new HashMap<String, ModelMessage>();
        for (ModelMessage message : modelRequest.messages()) {
            messagesById.put(message.messageId(), message);
        }
        HashSet<String> blockIds = new HashSet<>();
        HashSet<String> usedMessageIds = new HashSet<>();
        int previousSequence = -1;
        long tokens = 0;
        long characters = 0;
        for (ContextBlock block : blocks) {
            if (!blockIds.add(block.blockId()) || block.sequence() <= previousSequence) {
                throw new IllegalArgumentException("context blocks must have unique ids and increasing sequence");
            }
            previousSequence = block.sequence();
            long blockCharacters = usedMessageIds.isEmpty() ? modelRequest.tools().stream()
                    .mapToLong(tool -> tool.accountingText().codePointCount(0, tool.accountingText().length())).sum() : 0;
            for (String messageId : block.messageIds()) {
                ModelMessage message = messagesById.get(messageId);
                if (message == null || !usedMessageIds.add(messageId)) {
                    throw new IllegalArgumentException("each model message must belong to exactly one block");
                }
                blockCharacters = Math.addExact(blockCharacters, message.accountingText().codePointCount(0, message.accountingText().length()));
            }
            if (block.charCost() != blockCharacters) {
                throw new IllegalArgumentException("block charCost must equal final message code point count");
            }
            tokens = Math.addExact(tokens, block.tokenCost());
            characters = Math.addExact(characters, block.charCost());
        }
        if (usedMessageIds.size() != messagesById.size() || tokens != budget.usedTokens() || characters != budget.usedCharacters()) {
            throw new IllegalArgumentException("context budget and sources must cover exactly the final messages");
        }
    }

    /** 两种显式上下文来源；不能按是否存在某字段猜测 Workflow/Agent 模式。 */
    public enum ContextScope { AGENT_INPUT, RESTRICTED_WORKFLOW }

    /** 视图策略与计量来源随请求冻结，恢复不得读取当前默认配置替代。 */
    public record ViewVersion(String policyRef, String policyVersion, String rendererVersion,
                              String tokenizerProfileRef, String measurementVersion) {
        public ViewVersion {
            requireText(policyRef);
            requireText(policyVersion);
            requireText(rendererVersion);
            requireText(tokenizerProfileRef);
            requireText(measurementVersion);
        }
    }

    /** 每个调用组使用精确消息、调用及来源身份；结果内容相同不会合并不同执行。 */
    public record CallGroup(List<String> messageIds, List<String> callIds, List<EventId> sourceEventIds) {
        public CallGroup {
            messageIds = List.copyOf(messageIds);
            callIds = List.copyOf(callIds);
            sourceEventIds = List.copyOf(sourceEventIds);
            requireDistinctText(messageIds);
            requireDistinctText(callIds);
            if (messageIds.isEmpty() || sourceEventIds.isEmpty()
                    || new HashSet<>(sourceEventIds).size() != sourceEventIds.size()) {
                throw new IllegalArgumentException("invalid context call group");
            }
        }
    }

    /** 已校验原生协议后按完整调用与结果组建边界，不以条数相等推断配对。 */
    public List<CallGroup> callGroups() {
        var sources = new HashMap<String, List<EventId>>();
        for (var block : blocks) for (var id : block.messageIds()) sources.put(id, block.sourceEventIds());
        var groups = new ArrayList<CallGroup>();
        var messages = modelRequest.messages();
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            var ids = new ArrayList<String>();
            var events = new LinkedHashSet<EventId>();
            ids.add(message.messageId());
            events.addAll(sources.get(message.messageId()));
            List<String> calls = message.toolCalls().stream().map(call -> call.callId()).toList();
            for (var callId : calls) {
                var result = messages.get(++index);
                ids.add(result.messageId());
                events.addAll(sources.get(result.messageId()));
            }
            groups.add(new CallGroup(ids, calls, List.copyOf(events)));
        }
        return List.copyOf(groups);
    }

    /** 人格仅冻结版本化引用，不把业务人格对象引入 Core。 */
    public record PersonaRef(TenantId tenantId, AgentId agentId, String version) {
        /** 人格引用必须完整且 version 非空。 */
        public PersonaRef {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(agentId, "agentId");
            requireText(version);
        }
    }

    /** 关系状态只引用 schema/version/revision，不复制画像内容或跨 Agent 默认共享。 */
    public record RelationshipRef(TenantId tenantId, EndUserId userId, AgentId agentId,
                           String schemaId, String schemaVersion, long revision) {
        /** 拒绝缺失引用分量与非正 revision。 */
        public RelationshipRef {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
            requireText(schemaId);
            requireText(schemaVersion);
            if (revision <= 0) {
                throw new IllegalArgumentException("relationship revision must be positive");
            }
        }
    }

    /** 每块保存来源、信任标签及最终消息关联；tokenCost 的权威测量由 Session 提供。 */
    public record ContextBlock(String blockId, String type, String providerId, List<EventId> sourceEventIds,
                        String scope, String trustLevel, TypedPayload content, int priority, int sequence,
                        long tokenCost, long charCost, List<String> messageIds) {
        /** 成本不得为负；来源和消息关联必须明确，sequence 非负且在快照中严格递增。 */
        public ContextBlock {
            requireText(blockId);
            requireText(type);
            requireText(providerId);
            sourceEventIds = List.copyOf(sourceEventIds);
            requireText(scope);
            requireText(trustLevel);
            Objects.requireNonNull(content, "content");
            messageIds = List.copyOf(messageIds);
            requireDistinctText(messageIds);
            if (sourceEventIds.isEmpty() || new HashSet<>(sourceEventIds).size() != sourceEventIds.size()
                    || messageIds.isEmpty() || sequence < 0 || tokenCost < 0 || charCost < 0) {
                throw new IllegalArgumentException("invalid context block sources, sequence or costs");
            }
        }

        /** 单块或 blocks 列表可能被独立记录，不能只依赖外层快照的日志脱敏。 */
        @Override
        public String toString() {
            return "ContextBlock[REDACTED]";
        }
    }

    /** 显式选择预算与已选成本，不混用模型供应商的真实 usage。 */
    public record ContextBudget(long maxTokens, long maxCharacters, long usedTokens, long usedCharacters) {
        /** 已选成本可以为零，但预算须为正，且所有成本必须位于预算内。 */
        public ContextBudget {
            if (maxTokens <= 0 || maxCharacters <= 0 || usedTokens < 0 || usedCharacters < 0
                    || usedTokens > maxTokens || usedCharacters > maxCharacters) {
                throw new IllegalArgumentException("invalid context selection budget");
            }
        }
    }

    /** 内部标签和身份仍必须是有效非空 Unicode，不能在编码时才静默修补。 */
    private static void requireText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("context text identity must be nonblank");
        }
        CanonicalJson.string(text);
    }

    /** 列表可为空，但包含的每个引用必须明确且不得靠去重改变冻结输入。 */
    private static void requireDistinctText(List<String> values) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("duplicate context text reference");
        }
        for (String value : values) {
            requireText(value);
        }
    }

    /** 快照可能含用户消息和证据，不输出到普通日志。 */
    @Override
    public String toString() {
        return "FrozenContextSnapshot[REDACTED]";
    }
}
